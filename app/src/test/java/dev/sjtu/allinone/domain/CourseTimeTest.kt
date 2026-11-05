package dev.sjtu.allinone.domain

import dev.sjtu.allinone.core.sectionStart
import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.local.CourseOverrideEntity
import org.junit.Assert.*
import org.junit.Test

class CourseTimeTest {
    private val course = Course(CourseEntity("course", "account", "school", "2026-2027", 1, 1, 0,
        "晚课", startSection = 13, endSection = 14, detailTime = "20:00–22:00"))

    @Test fun `explicit platform times override default section times`() {
        assertEquals(1200, course.startMinute)
        assertEquals(1320, course.endMinute)
        assertEquals(480 to 580, explicitCourseTime(" 8:00 至 09:40 "))
    }

    @Test fun `unknown or malformed time stays unparsed`() {
        listOf(null, "13-14节", "24:00-25:00", "09:00-08:00", "8:60-10:00", "08:00-09:00/10:00-11:00").forEach {
            assertNull(explicitCourseTime(it))
        }
    }

    @Test fun `editing sections invalidates original platform time but editing location does not`() {
        val original = CourseOverrideEntity(course.id, "account", startSection = 1, endSection = 2, updatedAt = 0)
        assertEquals(sectionStart(1), course.copy(override = original).startMinute)
        assertNull(course.copy(override = original).platformTime)
        assertEquals(1320, course.copy(override = original.copy(startSection = null, endSection = null, location = "新教室")).endMinute)
    }
}
