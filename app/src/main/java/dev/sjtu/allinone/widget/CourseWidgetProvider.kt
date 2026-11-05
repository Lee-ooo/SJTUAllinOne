package dev.sjtu.allinone.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, CourseWidgetProvider::class.java))
            ids.forEach { id ->
                scope.launch {
                    updateLock.withLock {
                        updateWidget(appContext, manager, id)
                        manager.notifyAppWidgetViewDataChanged(intArrayOf(id), R.id.course_list)
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
            views.setViewVisibility(R.id.widget_semester, if (compact) android.view.View.GONE else android.view.View.VISIBLE)
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
            views.setViewVisibility(R.id.widget_status, if (status.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            val overrides = container.db.courses().overridesNow(container.activeAccount).associateBy { it.id }
            val courses = container.db.courses().onDateNow(container.activeAccount, today.toEpochDay())
                .filter { it.year == year && it.semester == semester }.map { Course(it, overrides[it.id]) }
            val now = ZonedDateTime.now(SCHOOL_ZONE)
            val selection = selectWidgetCourses(courses, settings.upcomingOnly, now.hour * 60 + now.minute,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180),
                settings.fontScale * context.resources.configuration.fontScale, compact)
            views.setTextViewText(R.id.widget_empty, if (selection.remaining > 0) "请调大组件"
                else if (profile == null) "请设置学期并导入"
                else if (settings.upcomingOnly) "今日无后续课程" else "今日无课程")
            views.setTextViewText(R.id.widget_overflow, "还有 ${selection.remaining} 门课程")
            views.setTextColor(R.id.widget_overflow, secondary)
            views.setTextViewTextSize(R.id.widget_overflow, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 10f else 12f) * settings.fontScale)
            views.setViewVisibility(R.id.widget_overflow, if (selection.remaining > 0) android.view.View.VISIBLE else android.view.View.GONE)
            val adapter = Intent(context, CourseWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("sjtu://widget/$id"))
            views.setRemoteAdapter(R.id.course_list, adapter)
            views.setEmptyView(R.id.course_list, R.id.widget_empty)
            val template = PendingIntent.getActivity(context, 1000,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            views.setPendingIntentTemplate(R.id.course_list, template)
            val refresh = Intent(context, CourseWidgetProvider::class.java).setAction(REFRESH_ACTION)
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 1001, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id, views)
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
