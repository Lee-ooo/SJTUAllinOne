package dev.sjtu.allinone.domain

import dev.sjtu.allinone.data.local.AssignmentEntity

fun AssignmentEntity.isPlatformSubmitted(): Boolean =
    !redoRequest && (workflowState in setOf("submitted", "graded") || submittedAt != null)

fun AssignmentEntity.isDone(): Boolean = localCompleted || isPlatformSubmitted() || excused

enum class DeadlineEdit { KEEP, CUSTOM, CANVAS }

fun AssignmentEntity.reminderForDeadline(deadline: Long?): Long? {
    val reminder = reminderAt ?: return null
    if (deadline == dueAt) return reminder
    return if (deadline != null && dueAt != null) reminder + (deadline - dueAt) else null
}

fun AssignmentEntity.withLocalSettings(local: AssignmentEntity?): AssignmentEntity {
    if (local == null) return this
    val deadline = if (local.hasDeadlineOverride) local.dueAt else dueAt
    return copy(
        dueAt = deadline,
        hasDeadlineOverride = local.hasDeadlineOverride,
        canvasDueAt = if (local.hasDeadlineOverride) dueAt else null,
        localCompleted = local.localCompleted,
        notes = local.notes,
        reminderAt = deadline?.let { local.reminderForDeadline(it) },
    )
}
