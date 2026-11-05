package dev.sjtu.allinone.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "semesters", primaryKeys = ["accountId", "year", "semester"])
data class SemesterEntity(
    val accountId: String,
    val year: String,
    val semester: Int,
    val startDate: Long,
    val importedAt: Long? = null,
)

@Entity(tableName = "courses", indices = [Index("accountId", "date"), Index("accountId", "year", "semester")])
data class CourseEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val source: String,
    val year: String,
    val semester: Int,
    val week: Int,
    val date: Long,
    val name: String,
    val code: String = "",
    val jxbId: String = "",
    val lessonClassCode: String = "",
    val teacher: String = "",
    val location: String = "",
    val credit: String? = null,
    val startSection: Int,
    val endSection: Int,
    val detailTime: String? = null,
    val suffix: String = "",
)

@Entity(tableName = "course_overrides")
data class CourseOverrideEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val cancelled: Boolean = false,
    val name: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val startSection: Int? = null,
    val endSection: Int? = null,
    val updatedAt: Long,
)

@Entity(tableName = "assignments", indices = [Index("accountId", "courseId"), Index("accountId", "dueAt")])
data class AssignmentEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val source: String,
    val courseId: String,
    val courseName: String,
    val remoteId: String = "",
    val name: String,
    val descriptionHtml: String = "",
    val url: String = "",
    val dueAt: Long? = null,
    val published: Boolean = true,
    val submissionTypes: String = "",
    val isQuiz: Boolean = false,
    val workflowState: String = "",
    val submittedAt: Long? = null,
    val late: Boolean = false,
    val missing: Boolean = false,
    val excused: Boolean = false,
    val redoRequest: Boolean = false,
    val localCompleted: Boolean = false,
    val notes: String = "",
    val reminderAt: Long? = null,
    val updatedAt: Long,
)

@Entity(tableName = "exams", indices = [Index("accountId", "year", "semester")])
data class ExamEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val year: String,
    val semester: Int,
    val name: String,
    val type: String,
    val address: String,
    val date: Long,
    val startMinute: Int,
    val endMinute: Int,
)

@Entity(tableName = "grades", indices = [Index("accountId", "year", "semester")])
data class GradeEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val year: String,
    val semester: Int,
    val name: String,
    val credit: String,
    val score: String,
    val gpa: String? = null,
    val courseNature: String = "",
    val gradeNature: String = "",
    val teacher: String = "",
    val jxbId: String,
    val pass: String = "",
    val detailsJson: String = "",
)

@Entity(tableName = "buildings", primaryKeys = ["accountId", "id"])
data class BuildingEntity(
    val id: String,
    val accountId: String,
    val campus: String,
    val name: String,
)

@Entity(tableName = "rooms", primaryKeys = ["accountId", "id"], indices = [Index("accountId", "buildingId"), Index("accountId", "scheduleDate")])
data class RoomEntity(
    val id: String,
    val accountId: String,
    val buildingId: String,
    val name: String,
    val floor: String = "",
    val scheduleDate: Long? = null,
    /** KNOWN, UNKNOWN, or STALE when no date has been loaded. */
    val scheduleStatus: String = "STALE",
    val capacity: Int? = null,
    val facilitiesJson: String = "",
)

@Entity(tableName = "sections", primaryKeys = ["accountId", "sectionIndex"])
data class SectionEntity(
    val accountId: String,
    val sectionIndex: Int,
    val startMinute: Int,
    val endMinute: Int,
)

@Entity(tableName = "occupations", indices = [Index("accountId", "roomId", "date")])
data class OccupationEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val roomId: String,
    val date: Long,
    val startMinute: Int,
    val endMinute: Int,
    val startSection: Int,
    val endSection: Int,
    val title: String,
    val teacher: String,
    val sourceType: String,
)

@Entity(tableName = "sync_states", primaryKeys = ["accountId", "source"])
data class SyncStateEntity(
    val accountId: String,
    val source: String,
    val lastSuccessAt: Long? = null,
    val error: String? = null,
    val authState: String = "UNKNOWN",
)
