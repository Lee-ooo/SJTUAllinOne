package dev.sjtu.allinone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.sjtu.allinone.data.local.GradeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Synthetic P grade and absent GPA; does not claim real account synchronization. */
class AcademicUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun passGradeAndBreakdownRenderWithoutInventingGpa() {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SJTUApplication).container
        val dao = container.db.academics()
        val account = container.activeAccount
        val previous = runBlocking { dao.gradesNow(account) }
        try {
            runBlocking {
                dao.insertGrades(listOf(GradeEntity("regression-grade", account, "2026-2027", 1,
                    "成绩展示回归", "2", "P", jxbId = "synthetic",
                    detailsJson = """[{"name":"考核分项","proportion":"100%","score":"P"}]""")))
            }
            compose.onAllNodesWithText("学业").onLast().performClick()
            compose.onNodeWithText("成绩").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("成绩展示回归").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("成绩展示回归").performClick()
            compose.onNodeWithText("成绩：P").assertIsDisplayed()
            compose.onNodeWithText("绩点：未提供").assertIsDisplayed()
            compose.onNodeWithText("考核分项").assertIsDisplayed()
            compose.onNodeWithText("100%  ").assertIsDisplayed()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "academic-test.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("关闭").performClick()
        } finally {
            runBlocking { dao.deleteGrades(account); dao.insertGrades(previous) }
        }
    }
}
