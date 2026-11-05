package dev.sjtu.allinone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimetableNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun collapsedSettingsAndDirectionalWeekSwipes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SJTUApplication).container
        val oldYear = runBlocking { container.selectedYear() }
        val oldSemester = runBlocking { container.selectedSemester() }
        val monday = dev.sjtu.allinone.core.schoolToday()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        try {
            runBlocking {
                container.repository.saveSemester("2093-2094", 1, monday.minusWeeks(2).toEpochDay())
                container.repository.saveLocalCourse("2093-2094", 1, monday.toEpochDay(), "滑动测试", "", "测试地点", 1, 2)
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("第 3 周").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("滑动测试").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("回到本周").assertDoesNotExist()
            compose.onNodeWithText("自动导入").assertDoesNotExist()
            val grid = compose.onNodeWithTag("timetable_grid")
            val collapsedTop = grid.fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.onNodeWithText("自动导入").assertIsDisplayed()
            compose.onNodeWithText("2093-2094 · 学期 1").assertIsDisplayed()
            assertTrue(grid.fetchSemanticsNode().boundsInRoot.top > collapsedTop)
            compose.onNodeWithText("添加").performClick()
            compose.onNodeWithText("添加课程").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("起始日期").performClick()
            compose.onNodeWithText("修改学期起始日期").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.onNodeWithText("滑动测试").performTouchInput { click() }
            compose.onNodeWithText("地点：测试地点").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()

            grid.performTouchInput { swipeLeft() }
            compose.onNodeWithText("第 4 周").assertIsDisplayed()
            grid.performTouchInput { swipeRight() }
            compose.onNodeWithText("第 3 周").assertIsDisplayed()
            grid.performTouchInput { swipeRight() }
            compose.onNodeWithText("第 2 周").assertIsDisplayed()
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.onNodeWithText("回到本周").performClick()
            compose.onNodeWithText("第 3 周").assertIsDisplayed()
            compose.onNodeWithContentDescription("课表设置").performClick()
            grid.performTouchInput { swipeUp() }
            compose.onNodeWithText("第 3 周").assertIsDisplayed()
            compose.onNodeWithContentDescription("上一周").performClick().performClick()
            compose.onNodeWithText("第 1 周").assertIsDisplayed()
            grid.performTouchInput { swipeRight() }
            compose.onNodeWithText("第 1 周").assertIsDisplayed()
            compose.onNodeWithContentDescription("上一周").assertIsNotEnabled()
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("自动导入").assertDoesNotExist()
            compose.onNodeWithText("设置学期资料").assertDoesNotExist()
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.onNodeWithText("回到本周").performClick()
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.onNodeWithText("第 3 周").assertIsDisplayed()
            compose.onNodeWithText("滑动测试").performScrollTo().assertIsDisplayed()
            java.io.File(context.getExternalFilesDir(null), "timetable-collapsed.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("课表设置").performClick()
            compose.onNodeWithText("自动导入").assertIsDisplayed()
            java.io.File(context.getExternalFilesDir(null), "timetable-settings.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onAllNodesWithText("作业").onLast().performClick()
            compose.onNodeWithContentDescription("课表设置").assertDoesNotExist()
            compose.onAllNodesWithText("课表").onLast().performClick()
            compose.onNodeWithText("自动导入").assertDoesNotExist()
        } finally {
            runBlocking {
                container.repository.deleteSemester("2093-2094", 1)
                container.repository.selectSemester(oldYear, oldSemester)
            }
        }
    }
}
