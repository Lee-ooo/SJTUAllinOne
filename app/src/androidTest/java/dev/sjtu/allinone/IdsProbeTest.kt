package dev.sjtu.allinone

import dev.sjtu.allinone.data.remote.IdsApi
import dev.sjtu.allinone.data.remote.JsonTransport
import dev.sjtu.allinone.data.remote.MemoryCookieJar
import dev.sjtu.allinone.data.remote.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Explicit anonymous, read-only IDS regression through the UI dispatcher. */
class IdsProbeTest {
    @Test fun buildingsAndRoomsFromUiThread(): Unit = runBlocking {
        val client = okHttpClient(MemoryCookieJar())
        val api = IdsApi(client, JsonTransport(client, "ids.sjtu.edu.cn"))
        withContext(Dispatchers.Main) {
            val buildings = api.fetchBuildings("probe")
            assertTrue("IDS building directory should not be empty", buildings.isNotEmpty())
            val sections = api.fetchSections()
            assertTrue("IDS sections should not be empty", sections.isNotEmpty())
            val rooms = api.fetchRooms("probe", buildings.first().id,
                LocalDate.now().toEpochDay(), sections)
            assertTrue("IDS rooms should not be empty", rooms.rooms.isNotEmpty())
            api.fetchRoomDetail(rooms.rooms.first().id)
        }
    }
}
