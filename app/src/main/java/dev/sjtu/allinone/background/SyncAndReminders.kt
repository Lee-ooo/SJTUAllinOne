package dev.sjtu.allinone.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.sjtu.allinone.MainActivity
import dev.sjtu.allinone.R
import dev.sjtu.allinone.SJTUApplication
import dev.sjtu.allinone.data.local.AssignmentEntity
import dev.sjtu.allinone.data.remote.AuthRequiredException
import dev.sjtu.allinone.domain.isDone
import java.util.concurrent.TimeUnit

object AppSyncScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "campus-background-sync", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}

/** Deliberately excludes the timetable: it is imported only by an explicit user action. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SJTUApplication).container
        var shouldRetry = false
        suspend fun run(source: String, block: suspend () -> Unit) {
            if (container.db.sync().get(container.activeAccount, source)?.authState == "REQUIRED") return
            try { block() } catch (error: Exception) {
                if (error !is AuthRequiredException) shouldRetry = true
            }
        }
        run("canvas") { container.repository.syncAssignments() }
        run("exams") { container.repository.syncExams(container.selectedYear(), container.selectedSemester()) }
        run("grades") { container.repository.syncGrades() }
        ReminderScheduler.refresh(applicationContext, container.db.assignments().allNow(container.activeAccount))
        return if (shouldRetry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

object ReminderScheduler {
    private const val TAG = "assignment-reminder"

    fun schedule(context: Context, assignment: AssignmentEntity) {
        val manager = WorkManager.getInstance(context)
        val name = "assignment-${assignment.id}"
        manager.cancelUniqueWork(name)
        context.getSystemService(NotificationManager::class.java).cancel(assignment.id.hashCode())
        if (assignment.isDone()) return
        val reminderAt = assignment.reminderAt ?: return
        val delay = reminderAt - System.currentTimeMillis()
        if (delay < 0) return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("assignmentId" to assignment.id,
                "accountId" to assignment.accountId, "reminderAt" to reminderAt))
            .addTag(TAG).build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun refresh(context: Context, assignments: List<AssignmentEntity>) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        assignments.filter { it.reminderAt != null }.forEach { schedule(context, it) }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SJTUApplication).container
        val id = inputData.getString("assignmentId") ?: return Result.success()
        // Existing WorkManager requests contain only assignmentId.
        val account = inputData.getString("accountId") ?: container.activeAccount
        if (account != container.activeAccount) return Result.success()
        val assignment = container.db.assignments().byId(container.activeAccount, id) ?: return Result.success()
        if (assignment.isDone() || assignment.reminderAt == null ||
            assignment.reminderAt != inputData.getLong("reminderAt", assignment.reminderAt ?: -1)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return Result.success()
        ensureChannel()
        val intent = PendingIntent.getActivity(applicationContext, id.hashCode(),
            MainActivity.assignmentIntent(applicationContext, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "assignment-reminders")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(assignment.name)
            .setContentText("${assignment.courseName} · 作业提醒")
            .setContentIntent(intent).setAutoCancel(true).build()
        manager.notify(id.hashCode(), notification)
        return Result.success()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel("assignment-reminders", "作业提醒", NotificationManager.IMPORTANCE_DEFAULT)
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
