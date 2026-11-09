package dev.sjtu.allinone

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.sjtu.allinone.widget.CourseWidgetProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed

/** Exercises the real RemoteViews host, without changing the launcher. */
class WidgetHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun staleCoursesOverflowAndOpenTimetable(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val previousYear = container.selectedYear()
        val previousSemester = container.selectedSemester()
        val today = dev.sjtu.allinone.core.schoolToday()
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val year = "2097-2098"
        val repository = container.repository
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 9032)
        val id = host.allocateAppWidgetId()
        val courses = (1..5).map { index -> dev.sjtu.allinone.data.local.CourseEntity(
            "widget-regression-$index", container.activeAccount, "school", year, 1, 1,
            today.toEpochDay(), "组件课程$index", location = "测试教室", startSection = index * 2 - 1, endSection = index * 2,
        ) }
        try {
            repository.saveSemester(year, 1, monday.toEpochDay())
            repository.saveTimetable(year, 1, dev.sjtu.allinone.data.remote.TimetableSnapshot(courses, 5))
            val dao = container.db.courses()
            dao.saveSemester(dao.semester(container.activeAccount, year, 1)!!.copy(importedAt = System.currentTimeMillis() - 31L * 86_400_000))
            container.settings.saveWidget(id, dev.sjtu.allinone.data.WidgetSettings(dark = true, fontScale = 1.4f))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, CourseWidgetProvider::class.java))) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            ActivityScenario.launch(WidgetTestHostActivity::class.java).use { scenario ->
                lateinit var view: AppWidgetHostView
                scenario.onActivity { activity ->
                    view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                    val frame = android.widget.FrameLayout(activity)
                    val scale = activity.resources.displayMetrics.density
                    frame.addView(view, android.widget.FrameLayout.LayoutParams((250 * scale).toInt(), (220 * scale).toInt()).apply {
                        gravity = android.view.Gravity.CENTER
                    })
                    activity.setContentView(frame)
                    host.startListening()
                    view.updateAppWidgetSize(null, 250, 220, 250, 220)
                }
                fun await(check: () -> Boolean) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                    var ready = false
                    while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { ready = check() }
                        if (!ready) android.os.SystemClock.sleep(100)
                    }
                    assertTrue("Widget condition timed out; options=${manager.getAppWidgetOptions(id)}", ready)
                }
                CourseWidgetProvider.updateAll(context)
                await {
                    view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)?.let {
                        it.childCount == 1
                    } == true
                }
                scenario.onActivity {
                    assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains("超过30天"))
                    assertEquals("还有 4 门课程", view.findViewById<TextView>(R.id.widget_overflow).text.toString())
                    assertEquals(android.view.View.VISIBLE, view.findViewById<TextView>(R.id.widget_overflow).visibility)
                    val list = view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)
                    assertEquals("组件课程1", list.getChildAt(0).findViewById<TextView>(R.id.course_name).text.toString())
                }
                scenario.onActivity { view.updateAppWidgetSize(null, 250, 480, 250, 480) }
                await { view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows).childCount > 1 }
                container.settings.saveWidget(id, dev.sjtu.allinone.data.WidgetSettings(dark = true, fontScale = 1.4f, upcomingOnly = true))
                CourseWidgetProvider.updateAll(context)
                val now = java.time.ZonedDateTime.now(dev.sjtu.allinone.core.SCHOOL_ZONE)
                val remaining = courses.filter { dev.sjtu.allinone.core.sectionEnd(it.endSection) > now.hour * 60 + now.minute }
                await {
                    val list = view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)
                    if (remaining.isEmpty()) list.childCount == 0 else
                        list.getChildAt(0)?.findViewById<TextView>(R.id.course_name)?.text?.toString() == remaining.first().name
                }
                container.settings.saveWidget(id, dev.sjtu.allinone.data.WidgetSettings(dark = true, fontScale = 1.4f))
                scenario.onActivity { view.updateAppWidgetSize(null, 250, 220, 250, 220) }
                CourseWidgetProvider.updateAll(context)
                await {
                    val list = view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)
                    list.childCount == 1 && list.getChildAt(0)?.findViewById<TextView>(R.id.course_name)?.text?.toString() == courses.first().name &&
                        view.findViewById<TextView>(R.id.widget_overflow).text == "还有 4 门课程" &&
                        view.findViewById<TextView>(R.id.widget_overflow).visibility == android.view.View.VISIBLE
                }
                scenario.onActivity {
                    val row = view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows).getChildAt(0)
                    listOf(row.findViewById<TextView>(R.id.course_time), view.findViewById<TextView>(R.id.widget_overflow)).forEach { text ->
                        val visible = android.graphics.Rect()
                        assertTrue(text.getGlobalVisibleRect(visible))
                        assertEquals("Widget ${text.resources.getResourceEntryName(text.id)} must not be clipped", text.height, visible.height())
                    }
                    assertEquals(0, view.findViewById<TextView>(R.id.widget_semester).layout.getEllipsisCount(0))
                }
                java.io.File(context.getExternalFilesDir(null), "widget-courses-test.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                container.settings.saveWidget(id, dev.sjtu.allinone.data.WidgetSettings(dark = true))
                scenario.onActivity { activity ->
                    val side = (146 * activity.resources.displayMetrics.density).toInt()
                    view.layoutParams = view.layoutParams.apply { width = side; height = side }
                    view.updateAppWidgetSize(null, 146, 146, 146, 146)
                }
                CourseWidgetProvider.updateAll(context)
                await {
                    view.findViewById<TextView>(R.id.widget_semester).visibility == android.view.View.GONE &&
                        view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows).childCount == 1 &&
                        view.findViewById<TextView>(R.id.widget_overflow).text == "还有 4 门课程" &&
                        view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows).getChildAt(0)?.findViewById<TextView>(R.id.course_meta)?.visibility == android.view.View.GONE
                }
                scenario.onActivity {
                    val info = manager.getAppWidgetInfo(id)
                    assertEquals(2, info.targetCellWidth)
                    assertEquals(2, info.targetCellHeight)
                    val row = view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows).getChildAt(0)
                    assertEquals(android.view.View.GONE, row.findViewById<TextView>(R.id.course_meta).visibility)
                    listOf(row.findViewById<TextView>(R.id.course_name), row.findViewById<TextView>(R.id.course_time),
                        view.findViewById<TextView>(R.id.widget_overflow)).forEach { text ->
                        val visible = android.graphics.Rect()
                        assertTrue(text.getGlobalVisibleRect(visible))
                        assertEquals(text.height, visible.height())
                        assertEquals(0, text.layout.getEllipsisCount(0))
                    }
                }
                java.io.File(context.getExternalFilesDir(null), "widget-2x2-test.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                val courseBounds = Rect()
                scenario.onActivity {
                    val rows = view.findViewById<android.widget.LinearLayout>(R.id.widget_course_rows)
                    assertFalse(rows.getChildAt(0).isClickable)
                    assertTrue(rows.getChildAt(0).getGlobalVisibleRect(courseBounds))
                }
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        courseBounds.exactCenterX(), courseBounds.exactCenterY(), 0)
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                compose.onNodeWithContentDescription("课表设置").assertIsDisplayed()
            }
        } finally {
            host.stopListening()
            host.deleteAppWidgetId(id)
            container.settings.removeWidget(id)
            repository.deleteSemester(year, 1)
            repository.selectSemester(previousYear, previousSemester)
        }
    }
    @Test fun widgetBindsRendersAndRespondsToResize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 9031)
        val id = host.allocateAppWidgetId()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, CourseWidgetProvider::class.java)))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: AppWidgetHostView
                scenario.onActivity { activity ->
                    view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                    activity.setContentView(view)
                    host.startListening()
                    view.updateAppWidgetSize(null, 240, 180, 240, 180)
                }
                CourseWidgetProvider.updateAll(context)
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                var rendered = false
                while (!rendered && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        rendered = view.findViewById<TextView>(R.id.widget_semester)?.text?.contains("学期") == true
                    }
                    if (!rendered) android.os.SystemClock.sleep(100)
                }
                assertTrue("Widget should render its semester header", rendered)
                java.io.File(context.getExternalFilesDir(null), "widget-test.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                scenario.onActivity {
                    assertNotNull(view.findViewById<TextView>(R.id.widget_date))
                    view.updateAppWidgetSize(null, 320, 320, 320, 320)
                    assertTrue(view.findViewById<android.view.View>(R.id.widget_refresh).performClick())
                }
            }
        } finally {
            host.stopListening()
            host.deleteAppWidgetId(id)
            runBlocking { (context.applicationContext as SJTUApplication).container.settings.removeWidget(id) }
        }
    }
}
