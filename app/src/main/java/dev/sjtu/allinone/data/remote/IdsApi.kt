package dev.sjtu.allinone.data.remote

import dev.sjtu.allinone.core.parseMinute
import dev.sjtu.allinone.data.local.BuildingEntity
import dev.sjtu.allinone.data.local.OccupationEntity
import dev.sjtu.allinone.data.local.RoomEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class IdsApi(
    private val client: OkHttpClient,
    private val transport: JsonTransport,
) {
    private val bootstrapLock = Mutex()
    private var bootstrapped = false

    suspend fun fetchBuildings(accountId: String): List<BuildingEntity> {
        val root = post("/build/findAreaBuild", "schoolArea" to "0").requireObject()
        return parseBuildings(accountId, root)
    }

    suspend fun fetchSections(): Map<Int, Pair<Int, Int>> = parseSections(post("/course/findSection").requireObject())

    suspend fun fetchRooms(
        accountId: String,
        buildingId: String,
        date: Long,
        sections: Map<Int, Pair<Int, Int>>,
    ): RoomSnapshot {
        val dateText = java.time.LocalDate.ofEpochDay(date).toString()
        val root = post("/build/findBuildRoomType", "buildId" to buildingId, "courseDate" to dateText).requireObject()
        return parseRooms(accountId, buildingId, date, root, sections)
    }

    suspend fun fetchRoomDetail(roomId: String): RoomDetail {
        val root = post("/roomAttr/findRoomDetail", "roomId" to roomId).requireObject()
        val data = root.requireIdsSuccess().requireObject()
        val attrs = (data["roomAttrList"] as? JsonArray) ?: JsonArray(emptyList())
        val jsms = (data["JSMS"] as? JsonArray)?.firstOrNull() as? JsonObject
        val capacity = jsms?.string("value")?.toIntOrNull()
            ?: attrs.mapNotNull { it as? JsonObject }.firstOrNull {
                it.string("name")?.contains("座位") == true
            }?.string("value")?.toIntOrNull()
        return RoomDetail(capacity, attrs.toString())
    }

    private suspend fun post(path: String, vararg parameters: Pair<String, String>): JsonObject {
        val form = FormBody.Builder().apply { parameters.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url("https://ids.sjtu.edu.cn$path")
            .header("X-Requested-With", "XMLHttpRequest").post(form).build()
        repeat(2) { attempt ->
            ensureBootstrap()
            try {
                val root = transport.execute(request).first.requireObject()
                root.requireIdsSuccess()
                return root
            } catch (error: AuthRequiredException) {
                if (attempt == 1) throw error
                bootstrapLock.withLock { bootstrapped = false }
            }
        }
        error("IDS retry exhausted")
    }

    private suspend fun ensureBootstrap() {
        if (bootstrapped) return
        bootstrapLock.withLock {
            if (bootstrapped) return
            try {
                withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("https://ids.sjtu.edu.cn/").get().build()).await().use {
                        it.requireHost("ids.sjtu.edu.cn")
                        if (!it.isSuccessful) throw RemoteException("教室服务暂时不可用")
                    }
                }
                bootstrapped = true
            } catch (error: Exception) {
                bootstrapped = false
                throw when (error) {
                    is RemoteException -> error
                    is java.net.SocketTimeoutException -> RemoteException("连接教室服务超时，请检查网络")
                    is java.io.IOException -> RemoteException("网络连接失败，已保留本地缓存")
                    else -> error
                }
            }
        }
    }
}

data class RoomSnapshot(val rooms: List<RoomEntity>, val occupations: List<OccupationEntity>)
data class RoomDetail(val capacity: Int?, val facilitiesJson: String)

internal fun parseBuildings(accountId: String, root: JsonObject): List<BuildingEntity> {
    val data = root.requireIdsSuccess().requireObject()
    return data["buildList"]?.requireArray()?.flatMap { campusElement ->
        val campus = campusElement.requireObject()
        val campusName = campus.string("name") ?: throw SourceChangedException()
        campus["children"]?.requireArray()?.map { child ->
            val building = child.requireObject()
            BuildingEntity(
                id = building.int("id")?.toString() ?: throw SourceChangedException(),
                accountId = accountId, campus = campusName,
                name = building.string("name") ?: throw SourceChangedException(),
            )
        } ?: throw SourceChangedException()
    } ?: throw SourceChangedException()
}

internal fun parseSections(root: JsonObject): Map<Int, Pair<Int, Int>> {
    val data = root.requireIdsSuccess().requireObject()
    return data["section"]?.requireArray()?.associate { item ->
        val section = item.requireObject()
        val index = section.int("sectionIndex") ?: throw SourceChangedException()
        val start = section.string("startTime")?.parseMinute() ?: throw SourceChangedException()
        val end = section.string("endTime")?.parseMinute() ?: throw SourceChangedException()
        if (end <= start) throw SourceChangedException()
        index to (start to end)
    } ?: throw SourceChangedException()
}

internal fun parseRooms(
    accountId: String,
    buildingId: String,
    date: Long,
    root: JsonObject,
    sections: Map<Int, Pair<Int, Int>>,
): RoomSnapshot {
    val data = root.requireIdsSuccess().requireObject()
    val floors = data["floorList"]?.requireArray() ?: throw SourceChangedException()
    val rooms = mutableListOf<RoomEntity>()
    val occupations = mutableListOf<OccupationEntity>()
    floors.forEach { floorElement ->
        val floor = floorElement.requireObject()
        val floorName = floor.string("name").orEmpty()
        val children = floor["children"]?.requireArray() ?: throw SourceChangedException()
        children.forEach { roomElement ->
            val room = roomElement.requireObject()
            val roomId = room.int("id")?.toString() ?: throw SourceChangedException()
            val name = room.string("name") ?: throw SourceChangedException()
            var known = true
            val courseList = room["roomCourseList"] as? JsonArray
            if (courseList == null) known = false
            courseList?.forEach { courseElement ->
                val course = courseElement as? JsonObject
                val startSection = course?.int("startSection")
                val endSection = course?.int("endSection")
                val start = sections[startSection]?.first
                val end = sections[endSection]?.second
                if (course == null || startSection == null || endSection == null ||
                    startSection > endSection || start == null || end == null || end <= start) {
                    known = false
                } else {
                    val title = course.string("courseName").orEmpty()
                    val teacher = course.string("teacherName").orEmpty()
                    val sourceType = course.string("sjly").orEmpty()
                    occupations += OccupationEntity(
                        id = stableId(accountId, roomId, date, startSection, endSection, title, sourceType),
                        accountId = accountId, roomId = roomId, date = date,
                        startMinute = start, endMinute = end, startSection = startSection, endSection = endSection,
                        title = title, teacher = teacher, sourceType = sourceType,
                    )
                }
            }
            rooms += RoomEntity(
                id = roomId, accountId = accountId, buildingId = buildingId, name = name, floor = floorName,
                scheduleDate = date, scheduleStatus = if (known) "KNOWN" else "UNKNOWN",
            )
        }
    }
    return RoomSnapshot(rooms, occupations)
}
