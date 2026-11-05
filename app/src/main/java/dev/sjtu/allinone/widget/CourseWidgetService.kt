package dev.sjtu.allinone.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.sjtu.allinone.R
import dev.sjtu.allinone.SJTUApplication
import dev.sjtu.allinone.core.SCHOOL_ZONE
import dev.sjtu.allinone.core.minuteText
import dev.sjtu.allinone.core.schoolToday
import dev.sjtu.allinone.data.WidgetSettings
import dev.sjtu.allinone.domain.Course
import dev.sjtu.allinone.domain.selectWidgetCourses
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking

class CourseWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        CourseFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private class CourseFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {
    private val container by lazy { (context as SJTUApplication).container }
    private var items = listOf<Course>()
    private var settings = WidgetSettings()
    private var nowMinute = 0
    private var compact = false

    override fun onCreate() {}
    override fun onDataSetChanged() {
        settings = runBlocking { container.settings.widgetNow(appWidgetId) }
        val now = ZonedDateTime.now(SCHOOL_ZONE)
        nowMinute = now.hour * 60 + now.minute
        val year = runBlocking { container.selectedYear() }
        val semester = runBlocking { container.selectedSemester() }
        val entities = container.db.courses().onDateNow(container.activeAccount, schoolToday().toEpochDay())
            .filter { it.year == year && it.semester == semester }
        val overrides = container.db.courses().overridesNow(container.activeAccount).associateBy { it.id }
        val all = entities.map { Course(it, overrides[it.id]) }
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
        compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 220
        items = selectWidgetCourses(all, settings.upcomingOnly, nowMinute, height,
            settings.fontScale * context.resources.configuration.fontScale, compact).items
    }

    override fun getCount(): Int = items.size
    override fun getViewAt(position: Int): RemoteViews {
        val course = items[position]
        val color = WIDGET_COLORS[(course.colorKey.hashCode() and Int.MAX_VALUE) % WIDGET_COLORS.size]
        val textColor = if (settings.dark) Color.WHITE else Color.rgb(40, 40, 48)
        val secondary = if (settings.dark) Color.rgb(215, 215, 225) else Color.rgb(80, 80, 88)
        return RemoteViews(context.packageName, R.layout.widget_course_item).apply {
            setInt(R.id.course_color, "setBackgroundColor", color)
            setTextViewText(R.id.course_name, course.name)
            setTextViewText(R.id.course_meta, listOf(course.location, course.teacher).filter { it.isNotBlank() }.joinToString("  "))
            setTextViewText(R.id.course_time, "${course.startMinute.minuteText()} – ${course.endMinute.minuteText()}")
            setTextColor(R.id.course_name, textColor)
            setTextColor(R.id.course_meta, secondary)
            setTextColor(R.id.course_time, secondary)
            setTextViewTextSize(R.id.course_name, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 13f else 15f) * settings.fontScale)
            setTextViewTextSize(R.id.course_meta, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * settings.fontScale)
            setTextViewTextSize(R.id.course_time, android.util.TypedValue.COMPLEX_UNIT_SP, (if (compact) 11f else 12f) * settings.fontScale)
            setViewVisibility(R.id.course_meta, if (compact) android.view.View.GONE else android.view.View.VISIBLE)
            setFloat(R.id.widget_item_root, "setAlpha", if (course.endMinute <= nowMinute) 0.42f else 1f)
            setOnClickFillInIntent(R.id.widget_item_root, Intent().putExtra("courseId", course.id))
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: Long.MAX_VALUE - position
    override fun hasStableIds(): Boolean = true
    override fun onDestroy() {}

    companion object {
        private val WIDGET_COLORS = listOf(0xFF3567B7.toInt(), 0xFFE0788F.toInt(), 0xFF7D6AC8.toInt(),
            0xFF38988C.toInt(), 0xFFD39B3D.toInt(), 0xFFD87555.toInt())
    }
}
