package dev.sjtu.allinone.domain

import dev.sjtu.allinone.data.local.AssignmentEntity

fun AssignmentEntity.isPlatformSubmitted(): Boolean =
    !redoRequest && (workflowState in setOf("submitted", "graded") || submittedAt != null)

fun AssignmentEntity.isDone(): Boolean = localCompleted || isPlatformSubmitted() || excused

fun AssignmentEntity.withLocalSettings(local: AssignmentEntity?): AssignmentEntity {
    if (local == null) return this
    val reminder = when {
        local.reminderAt == null || dueAt == null -> null
        local.dueAt != null -> dueAt - (local.dueAt - local.reminderAt)
        else -> null
    }
    return copy(localCompleted = local.localCompleted, notes = local.notes, reminderAt = reminder)
}
