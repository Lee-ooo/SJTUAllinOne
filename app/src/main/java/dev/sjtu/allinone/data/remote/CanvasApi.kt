package dev.sjtu.allinone.data.remote

import dev.sjtu.allinone.data.local.AssignmentEntity
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

private val CANVAS_BASE = "https://oc.sjtu.edu.cn/".toHttpUrl()

data class CanvasCourse(val id: String, val name: String)

class CanvasApi(private val transport: JsonTransport) {
    suspend fun validateSession() { fetchCourses() }

    suspend fun fetchCourses(): List<CanvasCourse> = fetchPages(
        url("/api/v1/courses", "enrollment_state" to "active", "per_page" to "100")
    ).map { obj ->
        val id = obj.string("id") ?: throw SourceChangedException()
        CanvasCourse(id, obj.string("name", "course_code").orEmpty().ifBlank { "Canvas 课程 $id" })
    }.distinctBy { it.id }

    suspend fun fetchAssignments(accountId: String, course: CanvasCourse): List<AssignmentEntity> = fetchPages(
        url("/api/v1/courses/${course.id}/assignments", "include[]" to "submission", "per_page" to "100")
    ).map { obj ->
        val remoteId = obj.string("id") ?: throw SourceChangedException()
        val submission = obj["submission"] as? JsonObject
        AssignmentEntity(
            id = stableId(accountId, "canvas", course.id, remoteId), accountId = accountId, source = "canvas",
            courseId = course.id, courseName = course.name, remoteId = remoteId,
            name = obj.string("name") ?: throw SourceChangedException(),
            descriptionHtml = obj.string("description").orEmpty(), url = obj.string("html_url").orEmpty(),
            dueAt = obj.string("due_at").toInstantMillis(), published = obj["published"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            } ?: true,
            submissionTypes = (obj["submission_types"] as? JsonArray)?.joinToString(",") {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            }.orEmpty(),
            isQuiz = obj.boolean("is_quiz_assignment"),
            workflowState = submission?.string("workflow_state").orEmpty(),
            submittedAt = submission?.string("submitted_at").toInstantMillis(),
            late = submission?.boolean("late") ?: false,
            missing = submission?.boolean("missing") ?: false,
            excused = submission?.boolean("excused") ?: false,
            redoRequest = submission?.boolean("redo_request") ?: false,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun fetchPages(initial: HttpUrl): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var next: HttpUrl? = initial
        var pages = 0
        while (next != null) {
            if (++pages > 100) throw RemoteException("Canvas 分页超过安全上限，未删除缓存")
            val current = next
            if (!current.isTrustedCanvasNext() || !visited.add(current.toString())) {
                throw SourceChangedException("Canvas 分页链接无效，未删除缓存")
            }
            val (body, response) = transport.get(current)
            result += body.requireArray().map { it.requireObject() }
            next = nextCanvasUrl(current, response)
        }
        return result
    }

    private fun url(path: String, vararg parameters: Pair<String, String>): HttpUrl =
        CANVAS_BASE.newBuilder().encodedPath(path).apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
}

internal fun nextCanvasUrl(current: HttpUrl, response: Response): HttpUrl? {
    val link = response.header("Link") ?: return null
    val target = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?").find(link)?.groupValues?.get(1) ?: return null
    val next = current.newBuilder(target)?.build() ?: throw SourceChangedException("Canvas 分页链接无效")
    if (!next.isTrustedCanvasNext()) throw SourceChangedException("Canvas 分页指向非受信任地址")
    return next
}

internal fun String?.toInstantMillis(): Long? = this?.let {
    try { Instant.parse(it).toEpochMilli() } catch (_: Exception) {
        throw SourceChangedException("平台日期格式无法识别")
    }
}
