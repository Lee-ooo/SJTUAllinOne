package dev.sjtu.allinone.domain

import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.local.CourseOverrideEntity
import dev.sjtu.allinone.core.parseMinute
import dev.sjtu.allinone.core.sectionStart
import dev.sjtu.allinone.core.sectionEnd

data class Course(
    val entity: CourseEntity,
    val override: CourseOverrideEntity? = null,
) {
    val id get() = entity.id
    val name get() = override?.name ?: entity.name
    val teacher get() = override?.teacher ?: entity.teacher
    val location get() = override?.location ?: entity.location
    val startSection get() = override?.startSection ?: entity.startSection
    val endSection get() = override?.endSection ?: entity.endSection
    val cancelled get() = override?.cancelled == true
    val platformTime: Pair<Int, Int>? get() {
        if (startSection != entity.startSection || endSection != entity.endSection) return null
        return explicitCourseTime(entity.detailTime)
    }
    val startMinute get() = platformTime?.first ?: sectionStart(startSection)
    val endMinute get() = platformTime?.second ?: sectionEnd(endSection)
    val colorKey get() = entity.jxbId.ifBlank { entity.code.ifBlank { entity.name } }
}

private val TIME_RANGE = Regex("^\\s*(\\d{1,2}:\\d{2})\\s*[-–—~～至]\\s*(\\d{1,2}:\\d{2})\\s*$")

fun explicitCourseTime(text: String?): Pair<Int, Int>? {
    val match = text?.let(TIME_RANGE::matchEntire) ?: return null
    val start = match.groupValues[1].parseMinute() ?: return null
    val end = match.groupValues[2].parseMinute() ?: return null
    return (start to end).takeIf { end > start }
}

data class PlacedCourse(val course: Course, val lane: Int, val laneCount: Int)

/** Lanes are scoped to each connected overlap cluster, not to the whole day. */
fun layoutCourses(courses: List<Course>): List<PlacedCourse> {
    val visible = courses.sortedWith(compareBy({ it.startSection }, { it.endSection }, { it.id }))
    val result = mutableListOf<PlacedCourse>()
    var index = 0
    while (index < visible.size) {
        var end = index + 1
        var clusterEnd = visible[index].endSection
        while (end < visible.size && visible[end].startSection <= clusterEnd) {
            clusterEnd = maxOf(clusterEnd, visible[end].endSection)
            end++
        }
        val cluster = visible.subList(index, end)
        val laneEnds = mutableListOf<Int>()
        val lanes = cluster.map { course ->
            val free = laneEnds.indexOfFirst { it < course.startSection }
            if (free >= 0) {
                laneEnds[free] = course.endSection
                free
            } else {
                laneEnds += course.endSection
                laneEnds.lastIndex
            }
        }
        cluster.forEachIndexed { i, course -> result += PlacedCourse(course, lanes[i], laneEnds.size) }
        index = end
    }
    return result
}

fun intervalsOverlap(occupiedStart: Int, occupiedEnd: Int, queryStart: Int, queryEnd: Int): Boolean =
    occupiedStart < queryEnd && occupiedEnd > queryStart

enum class DeadlineGroup { OVERDUE, TODAY, NEXT_SEVEN_DAYS, LATER, NO_DUE_DATE }

fun deadlineGroup(dueAt: Long?, now: java.time.ZonedDateTime): DeadlineGroup {
    if (dueAt == null) return DeadlineGroup.NO_DUE_DATE
    val due = java.time.Instant.ofEpochMilli(dueAt).atZone(now.zone)
    return when {
        due.toInstant().isBefore(now.toInstant()) -> DeadlineGroup.OVERDUE
        due.toLocalDate() == now.toLocalDate() -> DeadlineGroup.TODAY
        due.toLocalDate() <= now.toLocalDate().plusDays(7) -> DeadlineGroup.NEXT_SEVEN_DAYS
        else -> DeadlineGroup.LATER
    }
}
