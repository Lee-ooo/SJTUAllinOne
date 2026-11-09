package dev.sjtu.allinone.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import dev.sjtu.allinone.MainActivity
import dev.sjtu.allinone.R
import dev.sjtu.allinone.SJTUApplication
import dev.sjtu.allinone.core.SCHOOL_ZONE
import dev.sjtu.allinone.core.minuteText
import dev.sjtu.allinone.core.schoolToday
import dev.sjtu.allinone.core.weekdayName
import dev.sjtu.allinone.domain.widgetCacheStatus
import dev.sjtu.allinone.domain.Course
import dev.sjtu.allinone.domain.selectWidgetCourses
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CourseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) =
        updateAll(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in REFRESH_ACTIONS) updateAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val settings = (context.applicationContext as SJTUApplication).container.settings
        appWidgetIds.forEach { runBlocking { settings.removeWidget(it) } }
    }

    companion object {
        const val REFRESH_ACTION = "dev.sjtu.allinone.action.REFRESH_WIDGET"
        private val REFRESH_ACTIONS = setOf(REFRESH_ACTION, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val updateLock = Mutex()
        private val WIDGET_COLORS = listOf(0xFF3567B7.toInt(), 0xFFE0788F.toInt(), 0xFF7D6AC8.toInt(),
            0xFF38988C.toInt(), 0xFFD39B3D.toInt(), 0xFFD87555.toInt())

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, CourseWidgetProvider::class.java))
            ids.forEach { id ->
                scope.launch {
                    updateLock.withLock {
                        updateWidget(appContext, manager, id)
                        scheduleNextBoundary(appContext)
                    }
                }
            }
        }

        private suspend fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val container = (context as SJTUApplication).container
            val settings = container.settings.widgetNow(id)
            val year = container.selectedYear()
            val semester = container.selectedSemester()
            val today = schoolToday()
            val options = manager.getAppWidgetOptions(id)
            val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 220
            val views = RemoteViews(context.packageName, R.layout.widget_courses)
            val padding = ((if (compact) 6 else 10) * context.resources.displayMetrics.density).toInt()
            views.setViewPadding(R.id.widget_content, padding, padding, padding, padding)
            views.setViewVisibility(R.id.widget_semester, if (compact) View.GONE else View.VISIBLE)
            val darkText = if (settings.dark) Color.WHITE else Color.rgb(45, 45, 52)
            val secondary = if (settings.dark) Color.rgb(215, 215, 225) else Color.rgb(75, 75, 85)
            val base = if (settings.dark) Color.rgb(28, 28, 34) else Color.rgb(250, 250, 253)
            views.setInt(R.id.widget_background, "setColorFilter",
                Color.argb((settings.alpha.coerceIn(0.2f, 1f) * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)))
            views.setTextColor(R.id.widget_semester, darkText)
            views.setTextColor(R.id.widget_date, darkText)
            views.setTextColor(R.id.widget_empty, secondary)
            views.setInt(R.id.widget_refresh, "setColorFilter", darkText)
            views.setTextViewTextSize(R.id.widget_semester, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * settings.fontScale)
            views.setTextViewTextSize(R.id.widget_date, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 10f else 12f) * settings.fontScale)
            views.setTextViewTextSize(R.id.widget_empty, android.util.TypedValue.COMPLEX_UNIT_SP, 15f * settings.fontScale)
            views.setTextViewText(R.id.widget_semester, "$year · 学期$semester")
            views.setTextViewText(R.id.widget_date, "${today.monthValue}.${today.dayOfMonth} 周${weekdayName(today.dayOfWeek.value)}")
            val profile = container.db.courses().semesterNow(container.activeAccount, year, semester)
            val fullStatus = widgetCacheStatus(profile, System.currentTimeMillis())
            val status = if (!compact) fullStatus else when {
                profile == null -> ""
                profile.importedAt == null -> "未导入课表"
                fullStatus.isNotEmpty() -> "课表较旧"
                else -> ""
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setTextColor(R.id.widget_status, secondary)
            views.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 9f else 10f) * settings.fontScale)
            views.setViewVisibility(R.id.widget_status, if (status.isEmpty()) View.GONE else View.VISIBLE)
            val overrides = container.db.courses().overridesNow(container.activeAccount).associateBy { it.id }
            val courses = container.db.courses().onDateNow(container.activeAccount, today.toEpochDay())
                .filter { it.year == year && it.semester == semester }.map { Course(it, overrides[it.id]) }
            val now = ZonedDateTime.now(SCHOOL_ZONE)
            val nowMinute = now.hour * 60 + now.minute
            val selection = selectWidgetCourses(courses, settings.upcomingOnly, nowMinute,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180),
                settings.fontScale * context.resources.configuration.fontScale, compact)
            views.setTextViewText(R.id.widget_empty, if (selection.remaining > 0) "请调大组件"
                else if (profile == null) "请设置学期并导入"
                else if (settings.upcomingOnly) "今日无后续课程" else "今日无课程")
            views.setTextViewText(R.id.widget_overflow, "还有 ${selection.remaining} 门课程")
            views.setTextColor(R.id.widget_overflow, secondary)
            views.setTextViewTextSize(R.id.widget_overflow, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 10f else 12f) * settings.fontScale)
            views.setViewVisibility(R.id.widget_overflow, if (selection.remaining > 0) View.VISIBLE else View.GONE)
            views.removeAllViews(R.id.widget_course_rows)
            views.setViewVisibility(R.id.widget_course_rows, if (selection.items.isEmpty()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, if (selection.items.isEmpty()) View.VISIBLE else View.GONE)
            selection.items.forEach { course ->
                views.addView(R.id.widget_course_rows, courseRow(context, course, settings.dark, settings.fontScale, compact, nowMinute))
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1003,
                MainActivity.timetableIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val refresh = Intent(context, CourseWidgetProvider::class.java).setAction(REFRESH_ACTION)
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 1001, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id, views)
        }

        private fun courseRow(context: Context, course: Course, dark: Boolean, fontScale: Float,
            compact: Boolean, nowMinute: Int): RemoteViews {
            val color = WIDGET_COLORS[(course.colorKey.hashCode() and Int.MAX_VALUE) % WIDGET_COLORS.size]
            val textColor = if (dark) Color.WHITE else Color.rgb(40, 40, 48)
            val secondary = if (dark) Color.rgb(215, 215, 225) else Color.rgb(80, 80, 88)
            return RemoteViews(context.packageName, R.layout.widget_course_item).apply {
                setInt(R.id.course_color, "setBackgroundColor", color)
                setTextViewText(R.id.course_name, course.name)
                setTextViewText(R.id.course_meta, listOf(course.location, course.teacher).filter { it.isNotBlank() }.joinToString("  "))
                setTextViewText(R.id.course_time, "${course.startMinute.minuteText()} – ${course.endMinute.minuteText()}")
                setTextColor(R.id.course_name, textColor)
                setTextColor(R.id.course_meta, secondary)
                setTextColor(R.id.course_time, secondary)
                setTextViewTextSize(R.id.course_name, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 13f else 15f) * fontScale)
                setTextViewTextSize(R.id.course_meta, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * fontScale)
                setTextViewTextSize(R.id.course_time, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 11f else 12f) * fontScale)
                setViewVisibility(R.id.course_meta, if (compact) android.view.View.GONE else android.view.View.VISIBLE)
                setFloat(R.id.widget_item_root, "setAlpha", if (course.endMinute <= nowMinute) 0.42f else 1f)
            }
        }

        private suspend fun scheduleNextBoundary(context: Context) {
            val container = (context as SJTUApplication).container
            val now = ZonedDateTime.now(SCHOOL_ZONE)
            val today = now.toLocalDate().toEpochDay()
            val year = container.selectedYear()
            val semester = container.selectedSemester()
            val overrides = container.db.courses().overridesNow(container.activeAccount).associateBy { it.id }
            val courses = container.db.courses().onDateNow(container.activeAccount, today)
                .filter { it.year == year && it.semester == semester }
                .map { dev.sjtu.allinone.domain.Course(it, overrides[it.id]) }
                .filterNot { it.cancelled }
            var trigger = now.toLocalDate().plusDays(1).atStartOfDay(SCHOOL_ZONE).toInstant().toEpochMilli()
            courses.forEach {
                listOf(it.startMinute, it.endMinute).forEach { minute ->
                    val boundary = now.toLocalDate().atTime(minute / 60, minute % 60).atZone(SCHOOL_ZONE)
                        .toInstant().toEpochMilli()
                    if (boundary > now.toInstant().toEpochMilli()) trigger = minOf(trigger, boundary)
                }
            }
            val intent = Intent(context, CourseWidgetProvider::class.java).setAction(REFRESH_ACTION)
            val pending = PendingIntent.getBroadcast(context, 1002, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, trigger, pending)
        }
    }
}
