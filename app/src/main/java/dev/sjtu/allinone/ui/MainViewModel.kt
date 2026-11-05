package dev.sjtu.allinone.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.sjtu.allinone.SJTUApplication
import dev.sjtu.allinone.background.ReminderScheduler
import dev.sjtu.allinone.core.nowMillis
import dev.sjtu.allinone.core.schoolToday
import dev.sjtu.allinone.data.local.AssignmentEntity
import dev.sjtu.allinone.data.local.CourseEntity
import dev.sjtu.allinone.data.local.SemesterEntity
import dev.sjtu.allinone.data.remote.AuthRequiredException
import dev.sjtu.allinone.data.remote.TimetableSnapshot
import dev.sjtu.allinone.domain.Course
import dev.sjtu.allinone.widget.CourseWidgetProvider
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: Application) : ViewModel() {
    private val container = (app as SJTUApplication).container
    private val repository = container.repository

    val accountId = container.settings.accountId.stateIn(viewModelScope, SharingStarted.Eagerly, container.activeAccount)
    val selectedYear = container.settings.year.stateIn(viewModelScope, SharingStarted.Eagerly, "2026-2027")
    val selectedSemester = container.settings.semester.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    val semesters = accountId.flatMapLatest { container.db.courses().observeSemesters(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val courses = accountId.flatMapLatest { account ->
        combine(container.db.courses().observe(account), container.db.courses().observeOverrides(account)) { courses, overrides ->
            val byId = overrides.associateBy { it.id }
            courses.map { Course(it, byId[it.id]) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val assignments = accountId.flatMapLatest { container.db.assignments().observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val exams = accountId.flatMapLatest { container.db.academics().observeExams(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val grades = accountId.flatMapLatest { container.db.academics().observeGrades(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val buildings = accountId.flatMapLatest { container.db.rooms().observeBuildings(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rooms = accountId.flatMapLatest { container.db.rooms().observeRooms(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sections = accountId.flatMapLatest { container.db.rooms().observeSections(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val syncStates = accountId.flatMapLatest { container.db.sync().observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val roomDate = MutableStateFlow(schoolToday().toEpochDay())
    val selectedRoomDate = roomDate.asStateFlow()
    val occupations = accountId.flatMapLatest { account ->
        roomDate.flatMapLatest { container.db.rooms().observeOccupations(account, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedWeek = MutableStateFlow(1)
    val selectedWeek = _selectedWeek.asStateFlow()
    private var weekProfileKey: String? = null

    private val _operation = MutableStateFlow(Operation())
    val operation = _operation.asStateFlow()
    private val _pendingImport = MutableStateFlow<ImportPreview?>(null)
    val pendingImport = _pendingImport.asStateFlow()

    init {
        viewModelScope.launch {
            combine(semesters, selectedYear, selectedSemester) { profiles, year, semester ->
                Triple(profiles, year, semester)
            }.collect { (profiles, year, semester) ->
                val profile = profiles.firstOrNull { it.year == year && it.semester == semester }
                val key = profile?.let { "${it.accountId}/${it.year}/${it.semester}/${it.startDate}" }
                if (key != null && key != weekProfileKey) {
                    weekProfileKey = key
                    _selectedWeek.value = currentWeek(profile)
                }
            }
        }
    }

    fun currentWeek(profile: SemesterEntity): Int {
        val days = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(profile.startDate), schoolToday())
        return (days.floorDiv(7) + 1).toInt().coerceAtLeast(1)
    }

    fun selectWeek(week: Int) { _selectedWeek.value = week.coerceAtLeast(1) }
    fun selectSemester(year: String, semester: Int) = runTask("已切换学期") {
        repository.selectSemester(year, semester)
        CourseWidgetProvider.updateAll(app)
    }
    fun saveSemester(year: String, semester: Int, date: String) = runTask("学期设置已保存") {
        val start = try { LocalDate.parse(date).toEpochDay() } catch (_: Exception) {
            throw IllegalArgumentException("日期格式应为 YYYY-MM-DD")
        }
        repository.saveSemester(year.trim(), semester, start)
        CourseWidgetProvider.updateAll(app)
    }
    fun deleteSemester(year: String, semester: Int) = runTask("学期资料已删除") {
        repository.deleteSemester(year, semester)
        CourseWidgetProvider.updateAll(app)
    }

    fun importTimetable(year: String, semester: Int) = runTask {
        val snapshot = repository.previewTimetable(year, semester)
        _pendingImport.value = ImportPreview(year, semester, snapshot)
        null
    }
    fun dismissImport() { _pendingImport.value = null }
    fun confirmImport() {
        val pending = _pendingImport.value ?: return
        runTask("课表已导入") {
            repository.saveTimetable(pending.year, pending.semester, pending.snapshot)
            _pendingImport.value = null
            CourseWidgetProvider.updateAll(app)
        }
    }

    fun saveCourse(
        year: String, semester: Int, date: Long, name: String, teacher: String, location: String,
        start: Int, end: Int, existingId: String?,
    ) = runTask("课程已保存") {
        repository.saveLocalCourse(year, semester, date, name, teacher, location, start, end, existingId)
        CourseWidgetProvider.updateAll(app)
    }
    fun setCourseCancelled(course: CourseEntity, cancelled: Boolean) = runTask(if (cancelled) "课程已停课" else "课程已恢复") {
        repository.setCourseCancelled(course, cancelled)
        CourseWidgetProvider.updateAll(app)
    }
    fun resetCourse(id: String) = runTask("已恢复远端课程信息") {
        repository.resetCourseOverride(id)
        CourseWidgetProvider.updateAll(app)
    }

    fun syncAssignments() = runTask("Canvas 作业已同步") {
        try { repository.syncAssignments() }
        finally { ReminderScheduler.refresh(app, container.db.assignments().allNow(container.activeAccount)) }
    }
    fun saveAssignment(item: AssignmentEntity) = runTask("作业设置已保存") {
        val updated = item.copy(updatedAt = nowMillis())
        repository.saveAssignmentLocal(updated)
        ReminderScheduler.schedule(app, updated)
    }
    fun newAssignment(name: String, course: String, dueAt: Long?, notes: String) = runTask("手动任务已添加") {
        val item = repository.newLocalAssignment(name, course, dueAt, notes)
        ReminderScheduler.schedule(app, item)
    }
    fun deleteAssignment(id: String) = runTask("手动任务已删除") {
        repository.deleteLocalAssignment(id)
        WorkManagerCancellation.cancel(app, id)
    }

    fun syncAcademics() = runTask("考试与成绩已同步") {
        var failure: Exception? = null
        try { repository.syncExams(container.selectedYear(), container.selectedSemester()) }
        catch (error: Exception) { failure = error }
        try { repository.syncGrades() } catch (error: Exception) { if (failure == null) failure = error }
        failure?.let { throw it }
    }

    fun syncBuildings() = runTask("楼宇目录已更新") { repository.syncBuildings() }
    fun setRoomDate(date: Long) { roomDate.value = date }
    fun syncRooms(buildingId: String, date: Long) = runTask("教室排课已更新") {
        roomDate.value = date
        repository.syncRooms(buildingId, date)
    }
    fun loadRoomDetail(roomId: String) = runTask { repository.loadRoomDetail(roomId); null }

    fun logout() = runTask("已退出并清除本账号缓存") {
        repository.switchAccount()
        ReminderScheduler.refresh(app, emptyList())
        CookieManager.getInstance().removeAllCookies(null)
        CourseWidgetProvider.updateAll(app)
    }

    fun consumeMessage() { _operation.value = _operation.value.copy(message = null) }

    private fun runTask(successMessage: String? = null, block: suspend () -> Any?) {
        if (_operation.value.busy) return
        viewModelScope.launch {
            _operation.value = Operation(busy = true)
            try {
                val message = block() as? String ?: successMessage
                _operation.value = Operation(message = message)
            } catch (error: Exception) {
                val auth = if (error is AuthRequiredException) "school" else null
                _operation.value = Operation(message = error.message ?: "操作失败", error = true, authSource = auth)
            }
        }
    }

    data class Operation(
        val busy: Boolean = false,
        val message: String? = null,
        val error: Boolean = false,
        val authSource: String? = null,
    )
    data class ImportPreview(val year: String, val semester: Int, val snapshot: TimetableSnapshot)

    companion object {
        fun factory(app: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
        }
    }
}

/** Keeps cancellation out of the data repository's API. */
private object WorkManagerCancellation {
    fun cancel(app: Application, assignmentId: String) {
        androidx.work.WorkManager.getInstance(app).cancelUniqueWork("assignment-$assignmentId")
    }
}
