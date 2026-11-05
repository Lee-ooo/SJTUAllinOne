package dev.sjtu.allinone

import android.app.NotificationManager
import android.appwidget.*
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.sjtu.allinone.background.ReminderScheduler
import dev.sjtu.allinone.data.WidgetSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit two-phase emulator reboot test; do not run as an unordered suite. */
class RestartPersistenceTest {
    @Test fun prepareRestartFixture(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val prefs = context.getSharedPreferences("restart-regression", 0)
        val host = AppWidgetHost(context, 9034)
        val widget = host.allocateAppWidgetId()
        prefs.edit().putString("oldYear", container.selectedYear()).putInt("oldSemester", container.selectedSemester())
            .putInt("widget", widget).commit()
        val today = dev.sjtu.allinone.core.schoolToday()
        container.repository.saveSemester("2095-2096", 1,
            today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay())
        container.settings.saveWidget(widget, WidgetSettings(dark = true, alpha = 0.7f, fontScale = 1.2f))
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(widget,
                ComponentName(context, dev.sjtu.allinone.widget.CourseWidgetProvider::class.java)))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        val task = container.repository.newLocalAssignment("重启提醒回归", "测试", System.currentTimeMillis() + 3_600_000, "")
            .copy(reminderAt = System.currentTimeMillis() + 90_000)
        container.repository.saveAssignmentLocal(task)
        prefs.edit().putString("task", task.id).putLong("reminderAt", task.reminderAt!!).commit()
        ReminderScheduler.schedule(context, task)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (WorkManager.getInstance(context).getWorkInfosForUniqueWork("assignment-${task.id}").get().isEmpty() &&
            android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
        assertFalse(WorkManager.getInstance(context).getWorkInfosForUniqueWork("assignment-${task.id}").get().isEmpty())
    }

    @Test fun verifyRestartFixture(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val prefs = context.getSharedPreferences("restart-regression", 0)
        val task = prefs.getString("task", null)!!
        val widget = prefs.getInt("widget", -1)
        try {
            assertEquals("2095-2096", container.selectedYear())
            assertNotNull(container.db.courses().semester(container.activeAccount, "2095-2096", 1))
            assertTrue(container.settings.widgetNow(widget).dark)
            assertEquals(0.7f, container.settings.widgetNow(widget).alpha)
            assertNotNull(AppWidgetManager.getInstance(context).getAppWidgetInfo(widget))
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            val notifications = context.getSystemService(NotificationManager::class.java)
            while (notifications.activeNotifications.none { it.id == task.hashCode() } && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(100)
            assertTrue("Reminder should be delivered after reboot", notifications.activeNotifications.any { it.id == task.hashCode() })
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("assignment-$task").result.get()
            context.getSystemService(NotificationManager::class.java).cancel(task.hashCode())
            container.repository.deleteLocalAssignment(task)
            AppWidgetHost(context, 9034).deleteAppWidgetId(widget)
            container.settings.removeWidget(widget)
            container.repository.deleteSemester("2095-2096", 1)
            container.repository.selectSemester(prefs.getString("oldYear", "2026-2027")!!, prefs.getInt("oldSemester", 1))
            prefs.edit().clear().commit()
        }
    }
}
