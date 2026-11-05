package dev.sjtu.allinone.domain

import dev.sjtu.allinone.data.local.AssignmentEntity
import dev.sjtu.allinone.data.local.SemesterEntity
import org.junit.Assert.*
import org.junit.Test

class AssignmentReminderTest {
    private val assignment = AssignmentEntity("task", "account", "canvas", "course", "课程",
        name = "任务", dueAt = 10_000_000, updatedAt = 0)

    @Test fun `postponement and earlier deadline preserve relative reminder and local settings`() {
        val local = assignment.copy(reminderAt = 6_400_000, notes = "笔记", localCompleted = true)
        val postponed = assignment.copy(dueAt = 20_000_000).withLocalSettings(local)
        assertEquals(16_400_000L, postponed.reminderAt)
        assertEquals("笔记", postponed.notes)
        assertTrue(postponed.isDone())
        assertEquals(1_400_000L, assignment.copy(dueAt = 5_000_000).withLocalSettings(local).reminderAt)
    }

    @Test fun `removed deadline and disabled reminders do not produce a reminder`() {
        assertNull(assignment.copy(dueAt = null).withLocalSettings(assignment.copy(reminderAt = 100)).reminderAt)
        assertNull(assignment.withLocalSettings(assignment).reminderAt)
        assertEquals(assignment, assignment.withLocalSettings(null))
    }

    @Test fun `every completed state suppresses reminders while missing and redo remain pending`() {
        listOf(assignment.copy(localCompleted = true), assignment.copy(workflowState = "submitted"),
            assignment.copy(workflowState = "graded"), assignment.copy(submittedAt = 123),
            assignment.copy(excused = true)).forEach { assertTrue(it.isDone()) }
        assertFalse(assignment.copy(missing = true).isDone())
        assertFalse(assignment.copy(redoRequest = true).isDone())
        assertFalse(assignment.copy(workflowState = "graded", submittedAt = 123, redoRequest = true).isDone())
    }

    @Test fun `widget cache warns at thirty days and preserves manual-only state`() {
        val now = 60L * 24 * 60 * 60 * 1000
        val profile = SemesterEntity("account", "2026-2027", 1, 0, now)
        assertEquals("", widgetCacheStatus(profile, now))
        assertTrue(widgetCacheStatus(profile.copy(importedAt = now / 2), now).contains("超过30天"))
        assertEquals("", widgetCacheStatus(profile.copy(importedAt = now / 2 + 1), now))
        assertTrue(widgetCacheStatus(profile.copy(importedAt = null), now).contains("仅本地"))
        assertTrue(widgetCacheStatus(null, now).contains("设置学期"))
    }
}
