package dev.sjtu.allinone.domain

data class WidgetCourses(val items: List<Course>, val remaining: Int)

fun selectWidgetCourses(courses: List<Course>, upcomingOnly: Boolean, nowMinute: Int, height: Int, fontScale: Float, compact: Boolean = false): WidgetCourses {
    val all = courses.filter { !it.cancelled && (!upcomingOnly || it.endMinute > nowMinute) }
        .sortedWith(compareBy({ it.startMinute }, { it.endMinute }, { it.name }))
    val rows = (if (compact) (height - 58 - 9 * fontScale) / (42 * fontScale)
        else (height - 88 - 14 * fontScale) / (64 * fontScale)).toInt().coerceIn(0, 20)
    val items = all.take(rows)
    return WidgetCourses(items, all.size - items.size)
}
