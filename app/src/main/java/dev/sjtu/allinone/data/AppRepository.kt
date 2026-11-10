package dev.sjtu.allinone.data

import androidx.room.withTransaction
import dev.sjtu.allinone.core.nowMillis
import dev.sjtu.allinone.data.local.AppDatabase
import dev.sjtu.allinone.data.local.AssignmentEntity
import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.local.CourseOverrideEntity
import dev.sjtu.allinone.data.local.SemesterEntity
import dev.sjtu.allinone.data.local.SyncStateEntity
import dev.sjtu.allinone.data.remote.AuthRequiredException
import dev.sjtu.allinone.data.remote.CanvasApi
import dev.sjtu.allinone.data.remote.IdsApi
import dev.sjtu.allinone.data.remote.RemoteException
import dev.sjtu.allinone.data.remote.SchoolApi
import dev.sjtu.allinone.data.remote.SessionCookieJar
import dev.sjtu.allinone.data.remote.TimetableSnapshot
import dev.sjtu.allinone.data.remote.stableId
import dev.sjtu.allinone.domain.withLocalSettings
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope

class AppRepository(
    private val db: AppDatabase,
    private val settings: Settings,
    private val schoolApi: SchoolApi,
    private val canvasApi: CanvasApi,
    private val idsApi: IdsApi,
    private val schoolCookies: SessionCookieJar,
    private val canvasCookies: SessionCookieJar,
    private val accountId: () -> String,
    private val onAccountChanged: (String) -> Unit,
) {
    suspend fun validateSession(source: String) {
        val account = accountId()
        val sources = when (source) {
            "school" -> listOf("timetable", "exams", "grades")
            "canvas" -> listOf("canvas")
            else -> throw IllegalArgumentException("未知登录平台")
        }
        try {
            if (source == "school") schoolApi.validateSession() else canvasApi.validateSession()
        } catch (error: AuthRequiredException) {
            db.withTransaction { sources.forEach { markError(account, it, error) } }
            throw error
        }
        db.withTransaction {
            sources.forEach { name ->
                val old = db.sync().get(account, name)
                db.sync().save(SyncStateEntity(account, name, old?.lastSuccessAt, null, "VALID"))
            }
        }
    }

    suspend fun saveSemester(year: String, semester: Int, startDate: Long) {
        require(year.matches(Regex("\\d{4}-\\d{4}"))) { "学年格式应为 2026-2027" }
        require(semester in 1..3) { "学期无效" }
        val start = LocalDate.ofEpochDay(startDate)
        require(start.dayOfWeek == java.time.DayOfWeek.MONDAY) { "起始日期应为第一周周一" }
        val account = accountId()
        val old = db.courses().semester(account, year, semester)
        db.withTransaction {
            if (old != null && old.startDate != startDate) {
                val delta = startDate - old.startDate
                val remote = db.courses().forSemester(account, year, semester).filter { it.source == "school" }
                db.courses().deleteRemote(account, year, semester)
                remote.forEach { course ->
                    val newDate = course.date + delta
                    val newId = stableId(account, year, semester, newDate, course.jxbId,
                        course.lessonClassCode, course.startSection, course.endSection, course.name)
                    val override = db.courses().overrideById(account, course.id)
                    db.courses().insertCourses(listOf(course.copy(id = newId, date = newDate)))
                    if (override != null) {
                        db.courses().removeOverride(account, course.id)
                        db.courses().saveOverride(override.copy(id = newId, updatedAt = nowMillis()))
                    }
                }
            }
            db.courses().saveSemester(SemesterEntity(account, year, semester, startDate, old?.importedAt))
        }
        settings.setSemester(year, semester)
    }

    suspend fun selectSemester(year: String, semester: Int) = settings.setSemester(year, semester)

    suspend fun deleteSemester(year: String, semester: Int) {
        val account = accountId()
        db.withTransaction {
            val courses = db.courses().forSemester(account, year, semester)
            courses.forEach { db.courses().removeOverride(account, it.id) }
            db.courses().deleteSemesterCourses(account, year, semester)
            db.courses().deleteSemester(account, year, semester)
        }
    }

    suspend fun previewTimetable(year: String, semester: Int): TimetableSnapshot {
        val account = accountId()
        val profile = db.courses().semester(account, year, semester)
            ?: throw RemoteException("请先设置该学期的起始日期")
        return try {
            schoolApi.fetchTimetable(account, year, semester, profile.startDate)
        } catch (error: Exception) {
            markError(account, "timetable", error)
            throw error
        }
    }

    suspend fun saveTimetable(year: String, semester: Int, snapshot: TimetableSnapshot) {
        val account = accountId()
        db.withTransaction {
            db.courses().deleteRemote(account, year, semester)
            db.courses().insertCourses(snapshot.courses)
            val current = db.courses().semester(account, year, semester)
                ?: throw RemoteException("学期资料已被删除，请重新设置")
            db.courses().saveSemester(current.copy(importedAt = nowMillis()))
        }
        markSuccess(account, "timetable")
    }

    suspend fun saveLocalCourse(
        year: String, semester: Int, date: Long, name: String, teacher: String,
        location: String, startSection: Int, endSection: Int, existingId: String? = null,
    ) {
        require(name.isNotBlank()) { "课程名不能为空" }
        require(startSection in 1..14 && endSection in startSection..14) { "节次无效" }
        val account = accountId()
        val existing = existingId?.let { db.courses().byId(account, it) }
        if (existing != null && existing.source != "local") {
            val old = db.courses().overrideById(account, existing.id)
            db.courses().saveOverride(CourseOverrideEntity(
                id = existing.id, accountId = account, name = name, teacher = teacher,
                location = location, startSection = startSection, endSection = endSection,
                cancelled = old?.cancelled ?: false, updatedAt = nowMillis(),
            ))
        } else {
            val course = CourseEntity(
                id = existing?.id ?: "local-${UUID.randomUUID()}", accountId = account, source = "local",
                year = year, semester = semester,
                week = ((date - (db.courses().semester(account, year, semester)?.startDate ?: date)) / 7 + 1).toInt().coerceAtLeast(1),
                date = date, name = name.trim(), teacher = teacher.trim(), location = location.trim(),
                startSection = startSection, endSection = endSection,
                jxbId = existing?.jxbId.orEmpty(), code = existing?.code.orEmpty(),
            )
            db.courses().insertCourses(listOf(course))
        }
    }

    suspend fun setCourseCancelled(course: CourseEntity, cancelled: Boolean) {
        val account = accountId()
        if (course.source == "local") {
            if (cancelled) db.courses().deleteLocal(account, course.id)
            return
        }
        val old = db.courses().overrideById(account, course.id)
        db.courses().saveOverride(CourseOverrideEntity(
            id = course.id, accountId = account, cancelled = cancelled,
            name = old?.name, teacher = old?.teacher, location = old?.location,
            startSection = old?.startSection, endSection = old?.endSection, updatedAt = nowMillis(),
        ))
    }

    suspend fun resetCourseOverride(id: String) = db.courses().removeOverride(accountId(), id)

    suspend fun syncAssignments(): Int {
        val account = accountId()
        return try {
            val courses = canvasApi.fetchCourses()
            val existing = db.assignments().allNow(account).associateBy { it.id }
            val failures = mutableListOf<Exception>()
            courses.chunked(3).forEach { chunk ->
                supervisorScope {
                    chunk.map { course -> async {
                        try {
                            val remote = canvasApi.fetchAssignments(account, course).map { item ->
                                item.withLocalSettings(existing[item.id])
                            }
                            db.withTransaction {
                                db.assignments().deleteRemoteCourse(account, course.id)
                                db.assignments().insert(remote)
                            }
                            null
                        } catch (error: Exception) {
                            error
                        }
                    } }.forEach { task -> task.await()?.let { failures += it } }
                }
            }
            if (failures.isEmpty()) {
                if (courses.isEmpty()) db.assignments().deleteAllRemote(account)
                else db.assignments().deleteRemoteNotIn(account, courses.map { it.id })
                markSuccess(account, "canvas")
            } else {
                if (failures.any { it is AuthRequiredException }) throw AuthRequiredException()
                markError(account, "canvas", RemoteException("部分课程同步失败，已保留这些课程的旧数据"))
                throw RemoteException("部分课程同步失败，其他课程已更新")
            }
            courses.size
        } catch (error: Exception) {
            if (error.message != "部分课程同步失败，其他课程已更新") markError(account, "canvas", error)
            throw error
        }
    }

    suspend fun saveAssignmentLocal(item: AssignmentEntity) = db.assignments().save(item)

    suspend fun newLocalAssignment(name: String, courseName: String, dueAt: Long?, notes: String): AssignmentEntity {
        require(name.isNotBlank()) { "任务名称不能为空" }
        val account = accountId()
        return AssignmentEntity(
            id = "local-${UUID.randomUUID()}", accountId = account, source = "local",
            courseId = "manual", courseName = courseName.ifBlank { "手动任务" },
            name = name.trim(), dueAt = dueAt, notes = notes, updatedAt = nowMillis(),
        ).also { db.assignments().save(it) }
    }

    suspend fun deleteLocalAssignment(id: String) = db.assignments().deleteLocal(accountId(), id)

    suspend fun syncExams(year: String, semester: Int) {
        val account = accountId()
        try {
            val exams = schoolApi.fetchExams(account, year, semester)
            db.withTransaction {
                db.academics().deleteExams(account, year, semester)
                db.academics().insertExams(exams)
            }
            markSuccess(account, "exams")
        } catch (error: Exception) {
            markError(account, "exams", error)
            throw error
        }
    }

    suspend fun syncGrades() {
        val account = accountId()
        try {
            val grades = schoolApi.fetchGrades(account)
            val old = db.academics().gradesNow(account).associateBy { it.id }
            db.withTransaction {
                db.academics().deleteGrades(account)
                db.academics().insertGrades(grades.map { it.copy(detailsJson = old[it.id]?.detailsJson.orEmpty()) })
            }
            var failedDetails = 0
            grades.filter { it.jxbId.isNotBlank() }.chunked(4).forEach { chunk ->
                supervisorScope {
                    chunk.map { grade -> async {
                        try {
                            val details = schoolApi.fetchGradeDetails(grade.jxbId)
                            db.academics().saveGrade(grade.copy(detailsJson = details))
                            true
                        } catch (_: Exception) { false }
                    } }.forEach { if (!it.await()) failedDetails++ }
                }
            }
            markSuccess(account, "grades")
            if (failedDetails > 0) {
                markError(account, "grades", RemoteException("$failedDetails 门成绩明细暂未更新，列表和旧明细仍可用"))
            }
        } catch (error: Exception) {
            markError(account, "grades", error)
            throw error
        }
    }

    suspend fun syncBuildings() {
        val account = accountId()
        try {
            val buildings = idsApi.fetchBuildings(account)
            db.withTransaction {
                db.rooms().deleteBuildings(account)
                db.rooms().insertBuildings(buildings)
            }
            markSuccess(account, "rooms")
        } catch (error: Exception) {
            markError(account, "rooms", error)
            throw error
        }
    }

    suspend fun syncRooms(buildingId: String, date: Long) {
        val account = accountId()
        try {
            val sections = idsApi.fetchSections()
            val snapshot = idsApi.fetchRooms(account, buildingId, date, sections)
            val old = db.rooms().roomsNow(account, buildingId).associateBy { it.id }
            db.withTransaction {
                db.rooms().insertSections(sections.map { (index, times) ->
                    dev.sjtu.allinone.data.local.SectionEntity(account, index, times.first, times.second)
                })
                db.rooms().deleteOccupations(account, buildingId, date)
                db.rooms().insertRooms(snapshot.rooms.map { room ->
                    room.copy(capacity = old[room.id]?.capacity, facilitiesJson = old[room.id]?.facilitiesJson.orEmpty())
                })
                db.rooms().insertOccupations(snapshot.occupations)
            }
            markSuccess(account, "rooms")
        } catch (error: Exception) {
            markError(account, "rooms", error)
            throw error
        }
    }

    suspend fun loadRoomDetail(roomId: String) {
        val account = accountId()
        val detail = idsApi.fetchRoomDetail(roomId)
        val old = db.rooms().room(account, roomId) ?: return
        db.rooms().saveRoom(old.copy(capacity = detail.capacity, facilitiesJson = detail.facilitiesJson))
    }

    suspend fun switchAccount() {
        val old = accountId()
        schoolCookies.clear(old)
        canvasCookies.clear(old)
        db.clearAccount(old)
        val new = settings.newAccount()
        onAccountChanged(new)
    }

    private suspend fun markSuccess(account: String, source: String) {
        val old = db.sync().get(account, source)
        db.sync().save(SyncStateEntity(account, source, nowMillis(), null, "VALID"))
    }

    private suspend fun markError(account: String, source: String, error: Exception) {
        val old = db.sync().get(account, source)
        db.sync().save(SyncStateEntity(account, source, old?.lastSuccessAt, error.message ?: "同步失败",
            if (error is AuthRequiredException) "REQUIRED" else old?.authState ?: "UNKNOWN"))
    }
}
