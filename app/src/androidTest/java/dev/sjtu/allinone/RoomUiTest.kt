package dev.sjtu.allinone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.sjtu.allinone.core.schoolToday
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class RoomUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun directoryQueryDetailsAndChangedDate() {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SJTUApplication).container
        compose.onAllNodesWithText("教室").onLast().performClick()
        compose.onNodeWithContentDescription("更新楼宇目录").performClick()
        compose.waitUntil(45_000) {
            runBlocking { container.db.rooms().observeBuildings(container.activeAccount).first().isNotEmpty() }
        }
        compose.waitForIdle()
        compose.onNodeWithText("查询").performClick()
        compose.waitUntil(45_000) {
            runBlocking { container.db.rooms().observeRooms(container.activeAccount).first().any { it.scheduleDate == schoolToday().toEpochDay() } }
        }
        val rooms = runBlocking { container.db.rooms().observeRooms(container.activeAccount).first() }
        val displayed = rooms.first { compose.onAllNodesWithText(it.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(displayed.name).performClick()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithText("日期").performTextReplacement(schoolToday().plusDays(1).toString())
        compose.onAllNodesWithText("无已知排课").assertCountEquals(0)
        compose.onAllNodesWithText("已知占用").assertCountEquals(0)
        compose.onAllNodesWithText("排课未知").onFirst().assertIsDisplayed()
    }
}
