package dev.sjtu.allinone

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.sjtu.allinone.background.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Two-phase emulator test; shell forces idle between prepare and verify. */
class DozeReminderTest {
    @Test fun prepareDozeReminder(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val task = container.repository.newLocalAssignment("休眠提醒回归", "测试", System.currentTimeMillis() + 3_600_000, "")
            .copy(reminderAt = System.currentTimeMillis() + 10_000)
        container.repository.saveAssignmentLocal(task)
        context.getSharedPreferences("doze-regression", 0).edit().putString("task", task.id).commit()
        ReminderScheduler.schedule(context, task)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (WorkManager.getInstance(context).getWorkInfosForUniqueWork("assignment-${task.id}").get().isEmpty() &&
            android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
        assertFalse(WorkManager.getInstance(context).getWorkInfosForUniqueWork("assignment-${task.id}").get().isEmpty())
    }

    @Test fun verifyDozeReminder(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val prefs = context.getSharedPreferences("doze-regression", 0)
        val task = prefs.getString("task", null)!!
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            while (notifications.activeNotifications.none { it.id == task.hashCode() } &&
                android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
            assertTrue("Pending reminder should deliver after exiting idle", notifications.activeNotifications.any { it.id == task.hashCode() })
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("assignment-$task").result.get()
            notifications.cancel(task.hashCode())
            container.repository.deleteLocalAssignment(task)
            prefs.edit().clear().commit()
        }
    }
}
