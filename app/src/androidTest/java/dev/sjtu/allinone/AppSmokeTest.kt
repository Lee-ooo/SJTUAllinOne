package dev.sjtu.allinone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test

/** No school credentials required; supports a previously configured emulator. */
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun semesterPersistsAndAllTabsOpen() {
        compose.onNodeWithText("设置学期资料").assertDoesNotExist()
        compose.onNodeWithContentDescription("课表设置").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("设置学期").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("起始日期").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("起始日期").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("起始日期").performClick()
        } else compose.onNodeWithText("设置学期").performClick()
        compose.onNodeWithText("第一周周一（YYYY-MM-DD）")
            .performTextReplacement("2026-09-14")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("起始：2026-09-14").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("起始：2026-09-14").assertDoesNotExist()
        compose.onNodeWithContentDescription("课表设置").performClick()
        compose.onNodeWithText("起始：2026-09-14").assertIsDisplayed()
        compose.onNodeWithText("设置学期资料").assertDoesNotExist()
        compose.onNodeWithText("起始日期").performClick()
        compose.onNodeWithText("修改学期起始日期").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onAllNodesWithText("作业").onLast().performClick()
        compose.onNodeWithText("按 DDL").assertIsDisplayed()
        compose.onAllNodesWithText("教室").onLast().performClick()
        compose.onNodeWithText("查询").assertIsDisplayed()
        compose.onAllNodesWithText("学业").onLast().performClick()
        compose.onNodeWithText("当前学期没有考试缓存").assertIsDisplayed()
    }
}
