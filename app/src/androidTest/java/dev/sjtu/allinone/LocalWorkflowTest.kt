package dev.sjtu.allinone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Synthetic local tasks; no account or remote writes. */
class LocalWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun manualCategoriesExpandIndependentlyAndCompletionPersists() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SJTUApplication
        val tasks = runBlocking {
            listOf(
                app.container.repository.newLocalAssignment("测试任务甲", "测试分类甲", null, ""),
                app.container.repository.newLocalAssignment("测试任务乙", "测试分类乙", null, ""),
            )
        }
        try {
            compose.onAllNodesWithText("作业").onLast().performClick()
            compose.onNodeWithText("按课程").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("测试分类甲").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("测试分类甲").performClick()
            compose.onNodeWithText("测试任务甲").assertIsDisplayed()
            compose.onNodeWithText("测试任务乙").assertDoesNotExist()
            compose.onNodeWithText("测试任务甲").performClick()
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("备注").performTextReplacement("完成状态回归")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("已完成").performClick()
            compose.onNodeWithText("按 DDL").performClick()
            compose.onNodeWithText("测试任务甲").assertIsDisplayed()
            compose.onNodeWithText("测试任务乙").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("测试任务甲").performClick()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText("完成状态回归").assertExists()
            compose.onNodeWithText("删除").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("测试任务甲").fetchSemanticsNodes().isEmpty()
            }
        } finally {
            runBlocking { tasks.forEach { app.container.repository.deleteLocalAssignment(it.id) } }
        }
    }
}
