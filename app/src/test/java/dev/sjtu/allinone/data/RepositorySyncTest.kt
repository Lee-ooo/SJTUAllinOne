package dev.sjtu.allinone.data

import androidx.room.Room
import dev.sjtu.allinone.data.local.*
import dev.sjtu.allinone.data.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepositorySyncTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private var respond: (Request) -> Response = { error("Unexpected request") }
    private val account = "sync-regression"
    private fun reply(request: Request, body: String, code: Int = 200, next: String? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .header("Content-Type", "application/json")
        .apply { if (next != null) header("Link", "<$next>; rel=\"next\"") }
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        val school = SchoolApi(JsonTransport(client, "j.sjtu.edu.cn"))
        val canvas = CanvasApi(JsonTransport(client, "oc.sjtu.edu.cn"))
        repository = AppRepository(db, Settings(context), school, canvas,
            IdsApi(client, JsonTransport(client, "ids.sjtu.edu.cn")),
            SessionCookieJar(context, "j.sjtu.edu.cn", "school") { account },
            SessionCookieJar(context, "oc.sjtu.edu.cn", "canvas") { account }, { account }, {})
    }
    @After fun teardown() { db.close() }

    @Test fun `partial Canvas pagination failure preserves failed courses and local settings`(): Unit = runBlocking {
        val stable = stableId(account, "canvas", "1", "10")
        val oldDue = java.time.Instant.parse("2026-11-04T00:00:00Z").toEpochMilli()
        val old = AssignmentEntity(stable, account, "canvas", "1", "课程一", name = "旧任务",
            dueAt = oldDue, reminderAt = oldDue - 3_600_000, notes = "保留备注", updatedAt = 0)
        db.assignments().insert(listOf(old,
            old.copy(id = "failed", courseId = "2", name = "失败课程缓存"),
            old.copy(id = "absent", courseId = "3", name = "暂不可删除")))
        respond = { request ->
            when (request.url.encodedPath) {
                "/api/v1/courses" -> reply(request, """[{"id":1,"name":"课程一"},{"id":2,"name":"课程二"}]""")
                "/api/v1/courses/1/assignments" -> reply(request,
                    """[{"id":10,"name":"更新任务","due_at":"2026-11-05T00:00:00Z"}]""")
                "/api/v1/courses/2/assignments" -> if (request.url.queryParameter("page") == "2")
                    reply(request, "{}", 503) else reply(request, "[]", next = "https://oc.sjtu.edu.cn/api/v1/courses/2/assignments?page=2")
                else -> error("Unexpected path")
            }
        }
        assertTrue(runCatching { repository.syncAssignments() }.isFailure)
        val saved = db.assignments().allNow(account).associateBy { it.id }
        assertEquals(3, saved.size)
        assertEquals("失败课程缓存", saved["failed"]!!.name)
        assertNotNull(saved["absent"])
        assertEquals("保留备注", saved[stable]!!.notes)
        assertEquals(old.reminderAt!! + 86_400_000, saved[stable]!!.reminderAt)
        assertNotNull(db.sync().get(account, "canvas")!!.error)
    }

    @Test fun `expired login keeps assignments and last success time`(): Unit = runBlocking {
        db.assignments().save(AssignmentEntity("cached", account, "canvas", "1", "课程", name = "缓存", updatedAt = 0))
        db.sync().save(SyncStateEntity(account, "canvas", 123L, null, "VALID"))
        respond = { reply(it, "{}", 401) }
        assertTrue(runCatching { repository.syncAssignments() }.exceptionOrNull() is AuthRequiredException)
        assertEquals(1, db.assignments().allNow(account).size)
        assertEquals("REQUIRED", db.sync().get(account, "canvas")!!.authState)
        assertEquals(123L, db.sync().get(account, "canvas")!!.lastSuccessAt)
    }

    @Test fun `session probe marks only authentication failures and preserves last sync`(): Unit = runBlocking {
        db.sync().save(SyncStateEntity(account, "canvas", 123L, null, "VALID"))
        respond = { reply(it, "{}", 503) }
        assertTrue(runCatching { repository.validateSession("canvas") }.exceptionOrNull() is RemoteException)
        assertEquals("VALID", db.sync().get(account, "canvas")!!.authState)

        respond = { reply(it, "{}", 401) }
        assertTrue(runCatching { repository.validateSession("canvas") }.exceptionOrNull() is AuthRequiredException)
        assertEquals("REQUIRED", db.sync().get(account, "canvas")!!.authState)

        respond = { reply(it, "[]") }
        repository.validateSession("canvas")
        assertEquals(SyncStateEntity(account, "canvas", 123L, null, "VALID"), db.sync().get(account, "canvas"))
    }

    @Test fun `authentication failure within one course requires login without deleting cached tasks`(): Unit = runBlocking {
        db.assignments().save(AssignmentEntity("cached", account, "canvas", "1", "课程", name = "缓存", updatedAt = 0))
        respond = { request ->
            if (request.url.encodedPath == "/api/v1/courses") reply(request, """[{"id":1,"name":"课程"}]""")
            else reply(request, "{}", 403)
        }
        assertTrue(runCatching { repository.syncAssignments() }.exceptionOrNull() is AuthRequiredException)
        assertEquals("REQUIRED", db.sync().get(account, "canvas")!!.authState)
        assertEquals(1, db.assignments().allNow(account).size)
    }

    @Test fun `grade details failure does not discard grade list or cached breakdown`(): Unit = runBlocking {
        val id = stableId(account, "class", "2026-2027", 1, "测试成绩", 0)
        db.academics().saveGrade(GradeEntity(id, account, "2026-2027", 1, "测试成绩", "2", "P",
            jxbId = "class", detailsJson = "[{\"name\":\"旧明细\"}]"))
        respond = { request ->
            if (request.url.encodedPath.endsWith("/list")) reply(request,
                """{"errno":0,"data":[{"name":"测试成绩","year":"2026-2027","semester":1,"jxbId":"class","score":"P","credit":"2"}]}""")
            else reply(request, "{}", 404)
        }
        repository.syncGrades()
        val grade = db.academics().gradesNow(account).single()
        assertEquals("P", grade.score)
        assertNull(grade.gpa)
        assertTrue(grade.detailsJson.contains("旧明细"))
        assertNotNull(db.sync().get(account, "grades")!!.lastSuccessAt)
        assertNotNull(db.sync().get(account, "grades")!!.error)
    }

    @Test fun `incomplete timetable preview never replaces existing courses`(): Unit = runBlocking {
        val monday = java.time.LocalDate.parse("2026-09-14").toEpochDay()
        repository.saveSemester("2026-2027", 1, monday)
        val old = CourseEntity("cached-course", account, "school", "2026-2027", 1, 1, monday,
            "旧课程", startSection = 1, endSection = 2)
        repository.saveTimetable("2026-2027", 1, TimetableSnapshot(listOf(old), 1))
        respond = { request ->
            when {
                request.url.encodedPath.endsWith("/calendar") -> reply(request,
                    """{"errno":0,"data":[{"year":"2026-2027","semester":1,"week":1},{"year":"2026-2027","semester":1,"week":2}]}""")
                request.url.encodedPath.endsWith("/listBySemester") -> reply(request, """{"errno":0,"data":[]}""")
                request.url.queryParameter("week") == "2" -> reply(request, "{}", 503)
                else -> reply(request, """{"errno":0,"data":[{"name":"新课程","day":1,"duration":[1,2]}]}""")
            }
        }
        assertTrue(runCatching { repository.previewTimetable("2026-2027", 1) }.isFailure)
        assertEquals(listOf(old), db.courses().forSemester(account, "2026-2027", 1))
    }
}
