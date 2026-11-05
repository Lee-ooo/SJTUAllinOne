package dev.sjtu.allinone

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.sjtu.allinone.background.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed

/** Emulator only. Run the denied branch after externally revoking notification permission. */
class ReminderDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun legacyScheduledReminderSurvivesUpgrade(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val container = (context.applicationContext as SJTUApplication).container
        val task = container.repository.newLocalAssignment("旧版提醒兼容", "测试", System.currentTimeMillis() + 3_600_000, "")
            .copy(reminderAt = System.currentTimeMillis() - 1_000)
        container.repository.saveAssignmentLocal(task)
        val work = androidx.work.OneTimeWorkRequestBuilder<dev.sjtu.allinone.background.ReminderWorker>()
            .setInputData(androidx.work.workDataOf("assignmentId" to task.id)).build()
        val manager = WorkManager.getInstance(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            manager.enqueue(work).result.get()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (notifications.activeNotifications.none { it.id == task.id.hashCode() } && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(100)
            assertTrue(notifications.activeNotifications.any { it.id == task.id.hashCode() })
        } finally {
            manager.cancelWorkById(work.id).result.get()
            notifications.cancel(task.id.hashCode())
            container.repository.deleteLocalAssignment(task.id)
        }
    }
    @Test fun notificationDeliveryAndCompletion(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun command(value: String) {
            instrumentation.uiAutomation.executeShellCommand(
                value,
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        val task = container.repository.newLocalAssignment("提醒投递回归", "测试", System.currentTimeMillis() + 3_600_000, "")
        try {
            command("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            command("appops set ${context.packageName} POST_NOTIFICATION allow")
            val scheduled = task.copy(reminderAt = System.currentTimeMillis() + 1_500)
            container.repository.saveAssignmentLocal(scheduled)
            ReminderScheduler.schedule(context, scheduled)
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (notifications.activeNotifications.none { it.id == task.id.hashCode() } &&
                android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
            val notification = notifications.activeNotifications.single { it.id == task.id.hashCode() }
            assertEquals(task.name, notification.notification.extras.getString("android.title"))
            assertNotNull(notification.notification.contentIntent)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
            try { notification.notification.contentIntent.send() }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            compose.onNodeWithText("本地完成（不等于平台提交）").assertIsDisplayed()
            val completed = scheduled.copy(localCompleted = true)
            container.repository.saveAssignmentLocal(completed)
            ReminderScheduler.schedule(context, completed)
            val cancelDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (notifications.activeNotifications.any { it.id == task.id.hashCode() } &&
                android.os.SystemClock.elapsedRealtime() < cancelDeadline) android.os.SystemClock.sleep(100)
            assertTrue(notifications.activeNotifications.none { it.id == task.id.hashCode() })
            val works = WorkManager.getInstance(context).getWorkInfosForUniqueWork("assignment-${task.id}").get()
            assertTrue(works.all { it.state.isFinished })
        } finally {
            container.repository.deleteLocalAssignment(task.id)
            WorkManager.getInstance(context).cancelUniqueWork("assignment-${task.id}").result.get()
            notifications.cancel(task.id.hashCode())
            command("appops set ${context.packageName} POST_NOTIFICATION allow")
        }
    }

    @Test fun deniedPermissionDoesNotDeliver(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        val container = (context.applicationContext as SJTUApplication).container
        val task = container.repository.newLocalAssignment("拒绝权限回归", "测试", System.currentTimeMillis() + 3_600_000, "")
            .copy(reminderAt = System.currentTimeMillis() + 1_000)
        try {
            container.repository.saveAssignmentLocal(task)
            ReminderScheduler.schedule(context, task)
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            var finished = false
            while (!finished && android.os.SystemClock.elapsedRealtime() < deadline) {
                val states = WorkManager.getInstance(context).getWorkInfosForUniqueWork("assignment-${task.id}").get()
                finished = states.isNotEmpty() && states.all { it.state.isFinished }
                if (!finished) android.os.SystemClock.sleep(100)
            }
            assertTrue(finished)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == task.id.hashCode() })
        } finally {
            container.repository.deleteLocalAssignment(task.id)
            WorkManager.getInstance(context).cancelUniqueWork("assignment-${task.id}").result.get()
        }
    }
}
