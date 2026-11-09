package dev.sjtu.allinone

import android.app.AlarmManager
import android.appwidget.*
import android.content.ComponentName
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.sjtu.allinone.core.SCHOOL_ZONE
import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.remote.TimetableSnapshot
import dev.sjtu.allinone.widget.CourseWidgetProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit emulator clock test; restores both wall clock and timezone in finally. */
class WidgetClockTest {
    @Test fun crossDayTimezoneAndCourseEndRefresh(): Unit = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val oldYear = container.selectedYear()
        val oldSemester = container.selectedSemester()
        val oldMillis = System.currentTimeMillis()
        val oldElapsed = SystemClock.elapsedRealtime()
        val oldZone = java.util.TimeZone.getDefault().id
        val tomorrow = java.time.Instant.ofEpochMilli(oldMillis).atZone(SCHOOL_ZONE).toLocalDate().plusDays(1)
        val year = "2096-2097"
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 9033)
        val id = host.allocateAppWidgetId()
        val alarm = context.getSystemService(AlarmManager::class.java)
        val oldAutoTime = android.provider.Settings.Global.getInt(context.contentResolver, "auto_time", 1)
        val oldAutoZone = android.provider.Settings.Global.getInt(context.contentResolver, "auto_time_zone", 1)
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        try {
            android.provider.Settings.Global.putInt(context.contentResolver, "auto_time", 0)
            android.provider.Settings.Global.putInt(context.contentResolver, "auto_time_zone", 0)
            val monday = tomorrow.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            container.repository.saveSemester(year, 1, monday.toEpochDay())
            val course = CourseEntity("clock-course", container.activeAccount, "school", year, 1, 1,
                tomorrow.toEpochDay(), "次日课程", startSection = 1, endSection = 1, detailTime = "08:00-08:20")
            container.repository.saveTimetable(year, 1, TimetableSnapshot(listOf(course), 1))
            container.settings.saveWidget(id, dev.sjtu.allinone.data.WidgetSettings(upcomingOnly = true))
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, CourseWidgetProvider::class.java)))
            ActivityScenario.launch(WidgetTestHostActivity::class.java).use { scenario ->
                lateinit var view: AppWidgetHostView
                scenario.onActivity {
                    view = host.createView(it, id, manager.getAppWidgetInfo(id))
                    it.setContentView(view)
                    host.startListening()
                    view.updateAppWidgetSize(null, 300, 300, 300, 300)
                }
                fun await(check: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var ready = false
                    while (!ready && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { ready = check() }
                        if (!ready) SystemClock.sleep(100)
                    }
                    assertTrue("Clock refresh timed out; schoolDate=${dev.sjtu.allinone.core.schoolToday()}", ready)
                }
                alarm.setTime(tomorrow.atTime(8, 10).atZone(SCHOOL_ZONE).toInstant().toEpochMilli())
                await { view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)?.getChildAt(0)?.findViewById<TextView>(R.id.course_name)?.text == "次日课程" }
                val dateLabel = "${tomorrow.monthValue}.${tomorrow.dayOfMonth}"
                alarm.setTimeZone("Pacific/Honolulu")
                await { view.findViewById<TextView>(R.id.widget_date)?.text?.startsWith(dateLabel) == true }
                alarm.setTime(tomorrow.atTime(8, 21).atZone(SCHOOL_ZONE).toInstant().toEpochMilli())
                await {
                    view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)?.childCount == 0 &&
                        view.findViewById<TextView>(R.id.widget_empty)?.visibility == android.view.View.VISIBLE
                }
            }
        } finally {
            alarm.setTimeZone(oldZone)
            alarm.setTime(oldMillis + SystemClock.elapsedRealtime() - oldElapsed)
            android.provider.Settings.Global.putInt(context.contentResolver, "auto_time", oldAutoTime)
            android.provider.Settings.Global.putInt(context.contentResolver, "auto_time_zone", oldAutoZone)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            host.stopListening()
            host.deleteAppWidgetId(id)
            container.settings.removeWidget(id)
            container.repository.deleteSemester(year, 1)
            container.repository.selectSemester(oldYear, oldSemester)
        }
    }
}
