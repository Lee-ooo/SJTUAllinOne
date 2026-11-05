package dev.sjtu.allinone.domain

import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.local.CourseOverrideEntity
import org.junit.Assert.*
import org.junit.Test

class WidgetSelectionTest {
    private fun course(id: String, section: Int, time: String? = null) = Course(CourseEntity(
        id, "account", "school", "2026-2027", 1, 1, 0, id, startSection = section, endSection = section, detailTime = time))

    @Test fun `actual time determines order upcoming filter and cancellation`() {
        val earlier = course("earlier", 14, "08:00-09:00")
        val later = course("later", 1, "20:00-21:00")
        val cancelled = later.copy(override = CourseOverrideEntity("later", "account", cancelled = true, updatedAt = 0))
        assertEquals(listOf(earlier, later), selectWidgetCourses(listOf(later, earlier), false, 600, 1000, 1f).items)
        assertEquals(listOf(later), selectWidgetCourses(listOf(later, earlier), true, 600, 1000, 1f).items)
        assertTrue(selectWidgetCourses(listOf(cancelled, earlier), true, 600, 1000, 1f).items.isEmpty())
    }

    @Test fun `small and large widgets account for hidden courses without forcing a clipped row`() {
        val courses = (1..5).map { course("course$it", it) }
        val small = selectWidgetCourses(courses, false, 0, 204, 1.4f)
        val large = selectWidgetCourses(courses, false, 0, 464, 1.4f)
        assertEquals(5, small.items.size + small.remaining)
        assertTrue(large.items.size > small.items.size)
        val tiny = selectWidgetCourses(courses, false, 0, 90, 1.4f)
        assertTrue(tiny.items.isEmpty())
        assertEquals(5, tiny.remaining)
    }

    @Test fun `two by two compact widget shows a course at minimum size`() {
        val courses = (1..5).map { course("course$it", it) }
        val compact = selectWidgetCourses(courses, false, 0, 110, 1f, compact = true)
        assertEquals(1, compact.items.size)
        assertEquals(4, compact.remaining)
    }
}
