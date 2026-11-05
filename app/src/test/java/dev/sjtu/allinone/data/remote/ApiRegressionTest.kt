package dev.sjtu.allinone.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ApiRegressionTest {
    private fun response(request: Request, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("fixture").header("Content-Type", "application/json")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun `expired anonymous IDS session is bootstrapped once more`(): Unit = runBlocking {
        var bootstrap = 0
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.method == "GET") { bootstrap++; "{}" }
                else if (++posts == 1) """{"code":401,"data":null}"""
                else """{"code":200,"data":{"buildList":[{"name":"校区","children":[{"id":1,"name":"教学楼"}]}]}}"""
            response(request, body)
        }.build()
        val result = IdsApi(client, JsonTransport(client, "ids.sjtu.edu.cn")).fetchBuildings("account")
        assertEquals("教学楼", result.single().name)
        assertEquals(2, bootstrap)
        assertEquals(2, posts)
    }

    @Test fun `IDS authentication retries are bounded`(): Unit = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            response(it.request(), """{"code":403,"data":null}""")
        }.build()
        val error = runCatching { IdsApi(client, JsonTransport(client, "ids.sjtu.edu.cn")).fetchBuildings("account") }.exceptionOrNull()
        assertTrue(error is AuthRequiredException)
        assertEquals(4, requests)
    }

    @Test fun `exam groups inherit type and sort by actual date and time`(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), """{"errno":0,"data":{
          "期末":[{"name":"晚场","day":"2026-12-02","startTime":"14:00","endTime":"16:00"},
                    {"name":"早场","day":"2026-12-02","startTime":"08:00","endTime":"10:00","address":"教室"}],
          "期中":[{"name":"更早日期","day":"2026-11-03","startTime":"18:00","endTime":"20:00"}]
        }}""") }.build()
        val exams = SchoolApi(JsonTransport(client, "j.sjtu.edu.cn")).fetchExams("account", "2026-2027", 1)
        assertEquals(listOf("更早日期", "早场", "晚场"), exams.map { it.name })
        assertEquals(listOf("期中", "期末", "期末"), exams.map { it.type })
        assertEquals("", exams.first().address)
    }

    @Test fun `merged course blocks preserve full platform time span`(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            val request = it.request()
            response(request, when {
                request.url.encodedPath.endsWith("/calendar") -> """{"errno":0,"data":[{"year":"2026-2027","semester":1,"week":1}]}"""
                request.url.encodedPath.endsWith("/listBySemester") -> """{"errno":0,"data":[]}"""
                else -> """{"errno":0,"data":[
                  {"name":"晚课","day":1,"duration":[13,13],"detailTime":"20:00-20:45"},
                  {"name":"晚课","day":1,"duration":[14,14],"detailTime":"21:15-22:00"}
                ]}"""
            })
        }.build()
        val snapshot = SchoolApi(JsonTransport(client, "j.sjtu.edu.cn"))
            .fetchTimetable("account", "2026-2027", 1, java.time.LocalDate.parse("2026-09-14").toEpochDay())
        assertEquals(14, snapshot.courses.single().endSection)
        assertEquals("20:00–22:00", snapshot.courses.single().detailTime)
    }
}
