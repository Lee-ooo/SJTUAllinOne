package dev.sjtu.allinone.data.remote

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsParserTest {
    private val json = Json
    private fun obj(body: String) = json.parseToJsonElement(body).jsonObject
    private val sections = obj("""{"code":200,"data":{"section":[
        {"sectionIndex":1,"startTime":"08:00","endTime":"08:45"},
        {"sectionIndex":2,"startTime":"08:55","endTime":"09:40"}
    ]}}""").let(::parseSections)

    @Test fun `actual campus structure parses all buildings`() {
        val buildings = parseBuildings("acc", obj("""{"code":200,"data":{"buildList":[
            {"name":"闵行校区","id":3,"children":[{"name":"东下院","id":124},{"name":"上院","id":126}]},
            {"name":"徐汇校区","id":662,"children":[{"name":"工程馆","id":697}]}
        ]}}"""))
        assertEquals(listOf("东下院", "上院", "工程馆"), buildings.map { it.name })
        assertEquals(listOf("闵行校区", "闵行校区", "徐汇校区"), buildings.map { it.campus })
        assertEquals(listOf("124", "126", "697"), buildings.map { it.id })
    }

    @Test fun `actual section times are preserved including anomalies`() {
        val actual = obj("""{"code":200,"data":{"section":[
            {"sectionIndex":13,"startTime":"20:00","endTime":"20:20"},
            {"sectionIndex":14,"startTime":"21:15","endTime":"22:00"}
        ]}}""").let(::parseSections)
        assertEquals(20 * 60 to 20 * 60 + 20, actual[13])
        assertEquals(21 * 60 + 15 to 22 * 60, actual[14])
    }

    @Test fun `empty room list is known no scheduled course`() {
        val root = obj("""{"code":200,"data":{"floorList":[{"name":"一层","children":[
            {"id":286,"name":"东下院102","roomCourseList":[]}
        ]}]}}""")
        val snapshot = parseRooms("acc", "124", 100, root, sections)
        assertEquals("KNOWN", snapshot.rooms.single().scheduleStatus)
        assertTrue(snapshot.occupations.isEmpty())
    }

    @Test fun `borrowed exam is an occupancy`() {
        val root = obj("""{"code":200,"data":{"floorList":[{"name":"一层","children":[
            {"id":287,"name":"东下院105","roomCourseList":[
              {"courseName":"期中随堂考","startSection":1,"endSection":2,"teacherName":"教师","sjly":"借"}
            ]}
        ]}]}}""")
        val snapshot = parseRooms("acc", "124", 100, root, sections)
        assertEquals("KNOWN", snapshot.rooms.single().scheduleStatus)
        assertEquals("借", snapshot.occupations.single().sourceType)
        assertEquals(8 * 60, snapshot.occupations.single().startMinute)
        assertEquals(9 * 60 + 40, snapshot.occupations.single().endMinute)
    }

    @Test fun `malformed course span marks room unknown not free`() {
        val root = obj("""{"code":200,"data":{"floorList":[{"name":"一层","children":[
            {"id":1,"name":"A101","roomCourseList":[{"courseName":"异常","startSection":4,"endSection":2}]},
            {"id":2,"name":"A102","roomCourseList":[{"courseName":"缺节次","startSection":null,"endSection":2}]}
        ]}]}}""")
        val snapshot = parseRooms("acc", "124", 100, root, sections)
        assertEquals(listOf("UNKNOWN", "UNKNOWN"), snapshot.rooms.map { it.scheduleStatus })
        assertTrue(snapshot.occupations.isEmpty())
    }

    @Test fun `null data fails rather than becoming empty`() {
        val root = obj("""{"code":200,"data":null}""")
        var failed = false
        try { parseRooms("acc", "124", 0, root, sections) } catch (_: EmptyDataException) { failed = true }
        assertTrue(failed)
    }

    @Test fun `user start date determines weekday date`() {
        val monday = LocalDate.parse("2026-09-14").toEpochDay()
        val root = obj("""{"errno":"0","data":[
            {"name":"课程A","day":1,"duration":[1,2],"jxbId":"x","address":"东下院101"},
            {"name":"课程B","day":3,"duration":[3,4],"jxbId":"y","address":"东下院102"}
        ]}""")
        val courses = parseWeekCourses(root, "acc", "2026-2027", 1, 2, monday)
        assertEquals(LocalDate.parse("2026-09-21"), LocalDate.ofEpochDay(courses[0].date))
        assertEquals(LocalDate.parse("2026-09-23"), LocalDate.ofEpochDay(courses[1].date))
        assertEquals(2, courses[0].week)
    }

    @Test fun `invalid duration does not silently import partial schedule`() {
        val root = obj("""{"errno":"0","data":[{"name":"坏数据","day":1,"duration":[5]}]}""")
        var failed = false
        try { parseWeekCourses(root, "acc", "2026-2027", 1, 1, 0) } catch (_: SourceChangedException) { failed = true }
        assertTrue(failed)
    }

    @Test fun `calendar weeks are filtered by semester and sorted`() {
        val root = obj("""{"errno":0,"data":[
            {"year":"2026-2027","semester":1,"week":2,"weekDay":1,"day":"2026-09-21"},
            {"year":"2026-2027","semester":1,"week":1,"weekDay":1,"day":"2026-09-14"},
            {"year":"2025-2026","semester":2,"week":1,"weekDay":1,"day":"2026-02-23"}
        ]}""")
        assertEquals(listOf(1, 2), parseTeachingWeeks(root, "2026-2027", 1))
    }
}
