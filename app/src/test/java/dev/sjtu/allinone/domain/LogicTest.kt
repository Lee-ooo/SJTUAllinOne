package dev.sjtu.allinone.domain

import dev.sjtu.allinone.core.SCHOOL_ZONE
import dev.sjtu.allinone.data.local.CourseEntity
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun `courses sharing their boundary section overlap`() {
        val placed = layoutCourses(listOf(course("a", 1, 2), course("b", 2, 3)))
        assertEquals(listOf(0, 1), placed.map { it.lane })
        assertTrue(placed.all { it.laneCount == 2 })
    }

    private fun course(id: String, start: Int, end: Int, cancelled: Boolean = false): Course {
        val entity = CourseEntity(id = id, accountId = "a", source = "local", year = "2026-2027",
            semester = 1, week = 1, date = 0, name = id, startSection = start, endSection = end)
        return Course(entity, if (cancelled) dev.sjtu.allinone.data.local.CourseOverrideEntity(
            id = id, accountId = "a", cancelled = true, updatedAt = 0,
        ) else null)
    }

    @Test fun `adjacent sections share a full-width lane`() {
        val placed = layoutCourses(listOf(course("a", 1, 2), course("b", 3, 4)))
        assertEquals(listOf(0, 0), placed.map { it.lane })
        assertTrue(placed.all { it.laneCount == 1 })
    }

    @Test fun `overlapping courses get distinct lanes only within their cluster`() {
        val placed = layoutCourses(listOf(course("a", 1, 4), course("b", 3, 6), course("c", 8, 9)))
        assertEquals(listOf(0, 1, 0), placed.map { it.lane })
        assertEquals(listOf(2, 2, 1), placed.map { it.laneCount })
    }

    @Test fun `three simultaneous courses produce three lanes`() {
        val placed = layoutCourses(listOf(course("a", 1, 4), course("b", 2, 4), course("c", 3, 4)))
        assertEquals(3, placed.map { it.lane }.distinct().size)
        assertTrue(placed.all { it.laneCount == 3 })
    }

    @Test fun `cancelled courses remain visible and clickable`() {
        val placed = layoutCourses(listOf(course("cancelled", 1, 2, cancelled = true)))
        assertEquals(1, placed.size)
        assertTrue(placed.single().course.cancelled)
    }

    @Test fun `half-open interval does not treat touching boundaries as overlap`() {
        assertFalse(intervalsOverlap(600, 660, 660, 720))
        assertFalse(intervalsOverlap(660, 720, 600, 660))
        assertTrue(intervalsOverlap(600, 661, 660, 720))
    }

    @Test fun `deadline groups respect Asia Shanghai day boundaries`() {
        val now = ZonedDateTime.of(2026, 11, 3, 23, 30, 0, 0, SCHOOL_ZONE)
        val todayAtOne = ZonedDateTime.of(2026, 11, 4, 0, 10, 0, 0, SCHOOL_ZONE).toInstant().toEpochMilli()
        assertEquals(DeadlineGroup.NEXT_SEVEN_DAYS, deadlineGroup(todayAtOne, now))
        assertEquals(DeadlineGroup.OVERDUE, deadlineGroup(now.minusMinutes(1).toInstant().toEpochMilli(), now))
        assertEquals(DeadlineGroup.NO_DUE_DATE, deadlineGroup(null, now))
        val eightDays = ZonedDateTime.of(2026, 11, 12, 8, 0, 0, 0, SCHOOL_ZONE).toInstant().toEpochMilli()
        assertEquals(DeadlineGroup.LATER, deadlineGroup(eightDays, now))
    }

    @Test fun `seven days from today remains in seven day group`() {
        val now = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, SCHOOL_ZONE)
        val inSeven = ZonedDateTime.of(2026, 11, 10, 23, 0, 0, 0, SCHOOL_ZONE).toInstant().toEpochMilli()
        assertEquals(DeadlineGroup.NEXT_SEVEN_DAYS, deadlineGroup(inSeven, now))
    }
}
