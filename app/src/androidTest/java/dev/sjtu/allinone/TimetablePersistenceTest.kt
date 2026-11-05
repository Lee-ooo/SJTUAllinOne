package dev.sjtu.allinone

import androidx.test.platform.app.InstrumentationRegistry
import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.remote.TimetableSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TimetablePersistenceTest {
    @Test fun importsPreserveOverridesAndLocalCoursesAndSemesterDates(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SJTUApplication
        val container = app.container
        val repository = container.repository
        val dao = container.db.courses()
        val account = container.activeAccount
        val oldYear = container.selectedYear()
        val oldSemester = container.selectedSemester()
        val year = "2098-2099"
        val monday = LocalDate.parse("2098-09-01").with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay()
        val remote = CourseEntity("regression-remote", account, "school", year, 1, 1, monday,
            "导入课程", jxbId = "regression", startSection = 1, endSection = 2)
        try {
            repository.saveSemester(year, 1, monday)
            repository.saveTimetable(year, 1, TimetableSnapshot(listOf(remote), 1))
            repository.saveLocalCourse(year, 1, monday, "本地课程", "", "", 3, 4)
            repository.saveLocalCourse(year, 1, monday, "本地修改名称", "", "修改地点", 1, 2, remote.id)
            repository.setCourseCancelled(remote, true)
            repository.saveTimetable(year, 1, TimetableSnapshot(listOf(remote), 1))
            assertEquals(2, dao.forSemester(account, year, 1).size)
            val override = dao.overrideById(account, remote.id)!!
            assertTrue(override.cancelled)
            assertEquals("本地修改名称", override.name)
            repository.saveSemester(year, 1, monday + 7)
            val shifted = dao.forSemester(account, year, 1)
            val moved = shifted.single { it.source == "school" }
            assertEquals(monday + 7, moved.date)
            assertEquals(monday, shifted.single { it.source == "local" }.date)
            assertTrue(dao.overrideById(account, moved.id)!!.cancelled)
            repository.saveSemester(year, 2, monday + 140)
            assertEquals(monday + 7, dao.semester(account, year, 1)!!.startDate)
            assertEquals(monday + 140, dao.semester(account, year, 2)!!.startDate)
            repository.resetCourseOverride(moved.id)
            assertNull(dao.overrideById(account, moved.id))
        } finally {
            repository.deleteSemester(year, 1)
            repository.deleteSemester(year, 2)
            repository.selectSemester(oldYear, oldSemester)
        }
    }
}
