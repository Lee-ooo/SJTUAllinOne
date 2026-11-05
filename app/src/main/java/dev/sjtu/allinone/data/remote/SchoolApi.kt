package dev.sjtu.allinone.data.remote

import dev.sjtu.allinone.core.parseMinute
import dev.sjtu.allinone.core.minuteText
import dev.sjtu.allinone.domain.explicitCourseTime
import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.local.ExamEntity
import dev.sjtu.allinone.data.local.GradeEntity
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

private val SCHOOL_BASE = "https://j.sjtu.edu.cn/app/".toHttpUrl()

data class TimetableSnapshot(
    val courses: List<CourseEntity>,
    val semesterRecordCount: Int,
)

class SchoolApi(private val transport: JsonTransport) {
    suspend fun validateSession() {
        val root = transport.get(url("stu/exam/score/list")).first.requireObject()
        root.requireBusinessSuccess()
        root.requiredData().requireArray()
    }

    suspend fun fetchTimetable(accountId: String, year: String, semester: Int, startDate: Long): TimetableSnapshot = coroutineScope {
        val weeksDeferred = async { fetchTeachingWeeks(year, semester) }
        val semesterDeferred = async {
            val root = transport.get(url("stu/lesson/listBySemester", "year" to year, "semester" to semester.toString()))
                .first.requireObject()
            root.requireBusinessSuccess()
            root.requiredData().requireArray().size
        }
        val weeks = weeksDeferred.await()
        val semesterRecordCount = semesterDeferred.await()
        val courses = weeks.chunked(4).flatMap { chunk ->
            chunk.map { week -> async { fetchWeek(accountId, year, semester, week, startDate) } }.flatMap { it.await() }
        }
        TimetableSnapshot(mergeCourseBlocks(courses), semesterRecordCount)
    }

    private suspend fun fetchTeachingWeeks(year: String, semester: Int): List<Int> {
        val root = transport.get(url("stu/school/semester/calendar")).first.requireObject()
        return parseTeachingWeeks(root, year, semester)
    }

    private suspend fun fetchWeek(
        accountId: String,
        year: String,
        semester: Int,
        week: Int,
        startDate: Long,
    ): List<CourseEntity> {
        val root = transport.get(url(
            "stu/lesson/listByWeek",
            "year" to year, "semester" to semester.toString(), "week" to week.toString(),
        )).first.requireObject()
        return parseWeekCourses(root, accountId, year, semester, week, startDate)
    }

    suspend fun fetchExams(accountId: String, year: String, semester: Int): List<ExamEntity> {
        val root = transport.get(url("stu/exam/semester/list", "year" to year, "semester" to semester.toString()))
            .first.requireObject()
        root.requireBusinessSuccess()
        return flattenExams(root.requiredData()).map { obj ->
            val name = obj.string("name") ?: throw SourceChangedException()
            val day = obj.string("day") ?: throw SourceChangedException()
            val date = runCatching { LocalDate.parse(day).toEpochDay() }.getOrElse { throw SourceChangedException() }
            val start = obj.string("startTime")?.parseMinute() ?: throw SourceChangedException()
            val end = obj.string("endTime")?.parseMinute() ?: throw SourceChangedException()
            if (end <= start) throw SourceChangedException()
            val type = obj.string("examType").orEmpty()
            val address = obj.string("address").orEmpty()
            ExamEntity(stableId(accountId, year, semester, name, type, date, start, address), accountId,
                year, semester, name, type, address, date, start, end)
        }.sortedWith(compareBy({ it.date }, { it.startMinute }, { it.name }))
    }

    suspend fun fetchGrades(accountId: String): List<GradeEntity> {
        val root = transport.get(url("stu/exam/score/list")).first.requireObject()
        root.requireBusinessSuccess()
        return root.requiredData().requireArray().mapIndexed { index, item ->
            val obj = item.requireObject()
            val name = obj.string("name") ?: throw SourceChangedException()
            val year = obj.string("year") ?: throw SourceChangedException()
            val semester = obj.int("semester") ?: throw SourceChangedException()
            val jxbId = obj.string("jxbId").orEmpty()
            GradeEntity(
                id = stableId(accountId, jxbId, year, semester, name, index), accountId = accountId,
                year = year, semester = semester, name = name, credit = obj.string("credit").orEmpty(),
                score = obj.string("score").orEmpty(), gpa = obj.string("gpa")?.takeIf { it.isNotBlank() },
                courseNature = obj.string("courseNature").orEmpty(), gradeNature = obj.string("gradeNature").orEmpty(),
                teacher = obj.string("teacher").orEmpty(), jxbId = jxbId, pass = obj.string("pass").orEmpty(),
            )
        }
    }

    /** Missing detail is a valid empty result and never invalidates the grade list. */
    suspend fun fetchGradeDetails(jxbId: String): String {
        if (jxbId.isBlank()) return ""
        val root = transport.get(url("stu/exam/score/detail", "jxbId" to jxbId)).first.requireObject()
        root.requireBusinessSuccess()
        val data = root.requiredData().requireObject()
        return (data["detailList"] as? JsonArray)?.toString().orEmpty()
    }

    private fun flattenExams(element: JsonElement, inheritedType: String = ""): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap { flattenExams(it, inheritedType) }
        is JsonObject -> {
            val obj = if (inheritedType.isNotEmpty() && element["examType"] == null) {
                JsonObject(element + ("examType" to JsonPrimitive(inheritedType)))
            } else element
            if (obj["name"] != null && obj["day"] != null) listOf(obj)
            else obj.entries.flatMap { (key, value) -> flattenExams(value, obj.string("examType") ?: key) }
        }
        else -> emptyList()
    }

    private fun mergeCourseBlocks(input: List<CourseEntity>): List<CourseEntity> = input
        .groupBy { listOf(it.date, it.jxbId, it.lessonClassCode, it.name, it.location, it.teacher) }
        .values.flatMap { group ->
            group.sortedBy { it.startSection }.fold(mutableListOf<CourseEntity>()) { merged, next ->
                val previous = merged.lastOrNull()
                if (previous != null && next.startSection <= previous.endSection + 1) {
                    val previousTime = explicitCourseTime(previous.detailTime)
                    val nextTime = explicitCourseTime(next.detailTime)
                    merged[merged.lastIndex] = previous.copy(
                        endSection = maxOf(previous.endSection, next.endSection),
                        detailTime = if (previousTime != null && nextTime != null)
                            "${minOf(previousTime.first, nextTime.first).minuteText()}–${maxOf(previousTime.second, nextTime.second).minuteText()}"
                        else if (previous.detailTime == next.detailTime) previous.detailTime else null,
                        id = stableId(previous.accountId, previous.year, previous.semester, previous.date,
                            previous.jxbId, previous.lessonClassCode, previous.startSection,
                            maxOf(previous.endSection, next.endSection), previous.name),
                    )
                } else merged += next
                merged
            }
        }

    private fun url(path: String, vararg parameters: Pair<String, String>): HttpUrl =
        SCHOOL_BASE.newBuilder().addPathSegments(path).apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
}

internal fun parseTeachingWeeks(root: JsonObject, year: String, semester: Int): List<Int> {
    root.requireBusinessSuccess()
    val weeks = root.requiredData().requireArray().mapNotNull { item ->
        val obj = item.requireObject()
        if (obj.string("year") == year && obj.int("semester") == semester) obj.int("week") else null
    }.distinct().sorted()
    if (weeks.isEmpty()) throw EmptyDataException()
    return weeks
}

/** The date comes only from the user's semester start date, never from an unverified device timezone. */
internal fun parseWeekCourses(
    root: JsonObject,
    accountId: String,
    year: String,
    semester: Int,
    week: Int,
    startDate: Long,
): List<CourseEntity> {
    root.requireBusinessSuccess()
    return root.requiredData().requireArray().map { item ->
        val obj = item.requireObject()
        val day = obj.int("day") ?: throw SourceChangedException()
        val duration = obj["duration"]?.requireArray() ?: throw SourceChangedException()
        if (duration.size != 2) throw SourceChangedException()
        val start = (duration[0] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw SourceChangedException()
        val end = (duration[1] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw SourceChangedException()
        if (day !in 1..7 || start !in 1..14 || end !in start..14) throw SourceChangedException()
        val date = LocalDate.ofEpochDay(startDate).plusWeeks((week - 1).toLong()).plusDays((day - 1).toLong()).toEpochDay()
        val name = obj.string("name")?.trim().orEmpty()
        if (name.isEmpty()) throw SourceChangedException()
        val jxbId = obj.string("jxbId").orEmpty()
        val lessonClassCode = obj.string("lessonClassCode").orEmpty()
        val id = stableId(accountId, year, semester, date, jxbId, lessonClassCode, start, end, name)
        CourseEntity(
            id = id, accountId = accountId, source = "school", year = year, semester = semester,
            week = week, date = date, name = name,
            code = obj.string("code").orEmpty(), jxbId = jxbId, lessonClassCode = lessonClassCode,
            teacher = obj.string("teacher", "teacherName").orEmpty(),
            location = obj.string("address").orEmpty(), credit = obj.string("credit"),
            startSection = start, endSection = end, detailTime = obj.string("detailTime"),
            suffix = (obj["suffix"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(" ").orEmpty(),
        )
    }
}
