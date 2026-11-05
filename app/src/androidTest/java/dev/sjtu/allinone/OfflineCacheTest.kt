package dev.sjtu.allinone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Explicit emulator test: disable Wi-Fi and mobile data before invoking. */
class OfflineCacheTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun failedIdsRefreshRetainsDirectoryAndLocalFeaturesWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Network must be disabled", connectivity.activeNetwork)
        val account = container.activeAccount
        val previousYear = runBlocking { container.selectedYear() }
        val previousSemester = runBlocking { container.selectedSemester() }
        val before = runBlocking { container.db.rooms().observeBuildings(account).first() }
        assertTrue("Populate IDS cache online before offline verification", before.isNotEmpty())
        val lastSuccess = runBlocking { container.db.sync().get(account, "rooms")?.lastSuccessAt }
        runBlocking {
            assertTrue(runCatching { container.repository.syncBuildings() }.isFailure)
            assertEquals(before, container.db.rooms().observeBuildings(account).first())
            assertEquals(lastSuccess, container.db.sync().get(account, "rooms")?.lastSuccessAt)
            assertNotNull(container.db.sync().get(account, "rooms")?.error)
        }
        var taskId: String? = null
        try {
            runBlocking {
                val today = dev.sjtu.allinone.core.schoolToday()
                val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                container.repository.saveSemester("2094-2095", 1, monday.toEpochDay())
                container.repository.saveLocalCourse("2094-2095", 1, today.toEpochDay(), "离线课程", "", "", 1, 2)
                taskId = container.repository.newLocalAssignment("离线任务", "测试", null, "").id
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("离线课程").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("离线课程").assertIsDisplayed()
            compose.onAllNodesWithText("作业").onLast().performClick()
            compose.onNodeWithText("离线任务").assertIsDisplayed()
            compose.onAllNodesWithText("教室").onLast().performClick()
            compose.onNodeWithText("尚未读取楼宇目录").assertDoesNotExist()
            compose.onNodeWithText("查询").assertIsDisplayed()
        } finally {
            runBlocking {
                taskId?.let { container.repository.deleteLocalAssignment(it) }
                container.repository.deleteSemester("2094-2095", 1)
                container.repository.selectSemester(previousYear, previousSemester)
            }
        }
    }
}
