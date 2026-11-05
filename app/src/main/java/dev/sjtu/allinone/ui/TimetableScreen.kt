package dev.sjtu.allinone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sjtu.allinone.core.DEFAULT_SECTIONS
import dev.sjtu.allinone.core.minuteText
import dev.sjtu.allinone.core.sectionEnd
import dev.sjtu.allinone.core.sectionStart
import dev.sjtu.allinone.core.weekdayName
import dev.sjtu.allinone.data.local.SemesterEntity
import dev.sjtu.allinone.domain.Course
import dev.sjtu.allinone.domain.layoutCourses
import java.time.DayOfWeek
import java.time.LocalDate

private val SECTION_HEIGHT = 58.dp
private val COURSE_COLORS = listOf(
    Color(0xFFE98598), Color(0xFF6B9DE8), Color(0xFF8E7ADE), Color(0xFFE3B65F),
    Color(0xFF54B7AA), Color(0xFFE68B68), Color(0xFF7095C9), Color(0xFFBA7FB4),
)

@Composable
fun TimetableScreen(viewModel: MainViewModel, initialCourseId: String?, settingsExpanded: Boolean = false) {
    val profiles by viewModel.semesters.collectAsState()
    val year by viewModel.selectedYear.collectAsState()
    val semester by viewModel.selectedSemester.collectAsState()
    val week by viewModel.selectedWeek.collectAsState()
    val allCourses by viewModel.courses.collectAsState()
    val pending by viewModel.pendingImport.collectAsState()
    val profile = profiles.firstOrNull { it.year == year && it.semester == semester }
    val courses = allCourses.filter { it.entity.year == year && it.entity.semester == semester }
    var semesterEditor by remember { mutableStateOf(false) }
    var semesterEditorProfile by remember { mutableStateOf<SemesterEntity?>(null) }
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    var editorCourse by remember { mutableStateOf<Course?>(null) }
    var adding by remember { mutableStateOf(false) }
    val weekStart = profile?.let { LocalDate.ofEpochDay(it.startDate).plusWeeks((week - 1).toLong()) }
        ?: LocalDate.now()

    LaunchedEffect(initialCourseId, allCourses) {
        if (initialCourseId != null && selectedCourse == null) {
            selectedCourse = allCourses.firstOrNull { it.id == initialCourseId }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (settingsExpanded) {
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    SemesterSelector(profiles, year, semester, profile, onSelect = viewModel::selectSemester,
                        onEdit = { semesterEditorProfile = it; semesterEditor = true })
                    profile?.let {
                        Text("起始：${LocalDate.ofEpochDay(it.startDate)}", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { profile?.let { viewModel.selectWeek(viewModel.currentWeek(it)) } }, enabled = profile != null) {
                            Text("回到本周")
                        }
                        TextButton(onClick = { adding = true }, enabled = profile != null) {
                            Icon(Icons.Default.Add, null); Text("添加")
                        }
                        TextButton(onClick = { viewModel.importTimetable(year, semester) }, enabled = profile != null) {
                            Icon(Icons.Default.FileDownload, null); Text("自动导入")
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { viewModel.selectWeek(week - 1) }, enabled = profile != null && week > 1) {
                Icon(Icons.Default.ChevronLeft, "上一周")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 $week 周", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { viewModel.selectWeek(week + 1) }, enabled = profile != null) {
                Icon(Icons.Default.ChevronRight, "下一周")
            }
        }
        if (profile == null) {
            EmptyState("还没有学期资料", "点击课表右侧齿轮设置学年、学期和第一周周一，再自动导入课表。")
        } else {
            Column(Modifier.weight(1f).testTag("timetable_grid").pointerInput(year, semester, week) {
                var dragged = 0f
                val threshold = 48.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragCancel = { dragged = 0f },
                    onDragEnd = {
                        when {
                            dragged > threshold -> viewModel.selectWeek(week - 1)
                            dragged < -threshold -> viewModel.selectWeek(week + 1)
                        }
                    },
                    onHorizontalDrag = { change, delta -> change.consume(); dragged += delta },
                )
            }) {
                WeekHeader(weekStart)
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().height(SECTION_HEIGHT * 14)) {
                        SectionRail()
                        (1..7).forEach { day ->
                            val date = weekStart.plusDays((day - 1).toLong())
                            val dayCourses = courses.filter { LocalDate.ofEpochDay(it.entity.date) == date }
                            DayColumn(dayCourses, Modifier.weight(1f), onClick = { selectedCourse = it })
                        }
                    }
                }
            }
        }
    }

    if (semesterEditor) {
        SemesterEditorDialog(semesterEditorProfile, year, semester, onDismiss = { semesterEditor = false },
            onSave = viewModel::saveSemester)
    }
    pending?.let {
        AlertDialog(onDismissRequest = viewModel::dismissImport, title = { Text("导入预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${it.year} · 第 ${it.semester} 学期")
                    Text("整学期接口原始记录：${it.snapshot.semesterRecordCount} 条")
                    Text("将保存 ${it.snapshot.courses.size} 条具体日期课程，涉及 ${it.snapshot.courses.map { c -> c.name }.distinct().size} 门课程。")
                    Text("日期按你设置的第一周周一计算。只有全部教学周读取成功才会显示此预览；确认后保留本地添加、停课和编辑。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = viewModel::dismissImport) { Text("取消") } })
    }
    selectedCourse?.let { course ->
        CourseDetailDialog(course, onDismiss = { selectedCourse = null },
            onEdit = { editorCourse = course; selectedCourse = null },
            onCancel = { viewModel.setCourseCancelled(course.entity, !course.cancelled) },
            onReset = { viewModel.resetCourse(course.id) })
    }
    if (adding || editorCourse != null) {
        CourseEditorDialog(editorCourse, year, semester,
            editorCourse?.entity?.date ?: weekStart.toEpochDay(), onDismiss = { adding = false; editorCourse = null },
            onSave = { date, name, teacher, location, start, end ->
                viewModel.saveCourse(year, semester, date, name, teacher, location, start, end, editorCourse?.id)
                adding = false; editorCourse = null
            })
    }
}

@Composable
private fun SemesterSelector(
    profiles: List<SemesterEntity>, year: String, semester: Int, profile: SemesterEntity?,
    onSelect: (String, Int) -> Unit, onEdit: (SemesterEntity?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("$year · 学期 $semester") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                profiles.forEach { item ->
                    DropdownMenuItem(text = { Text("${item.year} · 学期 ${item.semester}") }, onClick = {
                        onSelect(item.year, item.semester); expanded = false
                    })
                }
                DropdownMenuItem(text = { Text("＋ 新建学期") }, onClick = { onEdit(null); expanded = false })
            }
        }
        Spacer(Modifier.weight(1f))
        if (profile?.importedAt != null) {
            Text("已导入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = { onEdit(profile) }) { Text(if (profile == null) "设置学期" else "起始日期") }
    }
}

@Composable
private fun WeekHeader(start: LocalDate) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(48.dp))
        (1..7).forEach { day ->
            val date = start.plusDays((day - 1).toLong())
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(weekdayName(day), style = MaterialTheme.typography.labelMedium)
                Text("${date.monthValue}/${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionRail() {
    Column(Modifier.width(48.dp).fillMaxHeight()) {
        DEFAULT_SECTIONS.forEach { (section, times) ->
            Column(Modifier.height(SECTION_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(section.toString(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(times.first.minuteText(), fontSize = 8.sp, lineHeight = 10.sp)
                Text(times.second.minuteText(), fontSize = 8.sp, lineHeight = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DayColumn(courses: List<Course>, modifier: Modifier, onClick: (Course) -> Unit) {
    BoxWithConstraints(modifier.fillMaxHeight().padding(horizontal = 1.dp)) {
        Spacer(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)))
        layoutCourses(courses).forEach { placed ->
            val course = placed.course
            val laneWidth = maxWidth / placed.laneCount
            val color = COURSE_COLORS[(course.colorKey.hashCode() and Int.MAX_VALUE) % COURSE_COLORS.size]
            Surface(
                onClick = { onClick(course) },
                modifier = Modifier.offset(x = laneWidth * placed.lane, y = SECTION_HEIGHT * (course.startSection - 1))
                    .width(laneWidth).height(SECTION_HEIGHT * (course.endSection - course.startSection + 1) - 3.dp)
                    .alpha(if (course.cancelled) 0.35f else 1f),
                color = color, contentColor = Color.White, shape = RoundedCornerShape(7.dp),
            ) {
                Column(Modifier.padding(3.dp)) {
                    Text(course.name, fontSize = if (placed.laneCount > 1) 8.sp else 10.sp,
                        lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 4,
                        overflow = TextOverflow.Ellipsis)
                    if (course.location.isNotBlank()) Text(course.location, fontSize = 8.sp, lineHeight = 10.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (course.cancelled) Text("停课", fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun SemesterEditorDialog(
    profile: SemesterEntity?, selectedYear: String, selectedSemester: Int,
    onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit,
) {
    var year by remember { mutableStateOf(profile?.year ?: selectedYear) }
    var semester by remember { mutableStateOf((profile?.semester ?: selectedSemester).toString()) }
    var date by remember { mutableStateOf(profile?.startDate?.let(LocalDate::ofEpochDay)?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (profile == null) "设置学期资料" else "修改学期起始日期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it }, label = { Text("学年，如 2026-2027") }, singleLine = true,
                    enabled = profile == null)
                OutlinedTextField(semester, { semester = it.filter(Char::isDigit) }, label = { Text("学期（1/2/3）") },
                    singleLine = true, enabled = profile == null)
                OutlinedTextField(date, { date = it }, label = { Text("第一周周一（YYYY-MM-DD）") }, singleLine = true)
                Text("修改起始日期会平移该学期已导入的远端课程；手动添加的具体日期课程不移动。",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val sem = semester.toIntOrNull() ?: -1
                val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
                when {
                    !year.matches(Regex("\\d{4}-\\d{4}")) -> error = "请输入正确学年"
                    sem !in 1..3 -> error = "学期应为 1、2 或 3"
                    parsed == null -> error = "请输入正确日期"
                    parsed.dayOfWeek != DayOfWeek.MONDAY -> error = "起始日期必须是周一"
                    else -> { onSave(year, sem, date); onDismiss() }
                }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun CourseDetailDialog(
    course: Course, onDismiss: () -> Unit, onEdit: () -> Unit,
    onCancel: () -> Unit, onReset: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(course.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("日期：${LocalDate.ofEpochDay(course.entity.date)} 周${weekdayName(LocalDate.ofEpochDay(course.entity.date).dayOfWeek.value)}")
                Text("时间：第 ${course.startSection}–${course.endSection} 节  ${course.startMinute.minuteText()}–${course.endMinute.minuteText()}")
                if (course.entity.source == "school" && course.platformTime == null) {
                    Text("时间按默认节次显示", style = MaterialTheme.typography.bodySmall)
                    course.entity.detailTime?.takeIf { it.isNotBlank() }?.let { Text("平台时间说明：$it") }
                }
                if (course.location.isNotBlank()) Text("地点：${course.location}")
                if (course.teacher.isNotBlank()) Text("教师：${course.teacher}")
                course.entity.credit?.let { Text("学分：$it") }
                if (course.entity.suffix.isNotBlank()) Text("标记：${course.entity.suffix}")
                Text(if (course.entity.source == "local") "来源：本地添加" else "来源：学在交大导入")
                if (course.cancelled) Text("此课程已停课", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            Row {
                if (course.entity.source != "local" && course.override != null) TextButton(onClick = onReset) { Text("恢复远端") }
                TextButton(onClick = onCancel) { Text(if (course.entity.source == "local") "删除" else if (course.cancelled) "恢复" else "停课") }
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun CourseEditorDialog(
    course: Course?, year: String, semester: Int, defaultDate: Long, onDismiss: () -> Unit,
    onSave: (Long, String, String, String, Int, Int) -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.ofEpochDay(course?.entity?.date ?: defaultDate).toString()) }
    var name by remember { mutableStateOf(course?.name.orEmpty()) }
    var teacher by remember { mutableStateOf(course?.teacher.orEmpty()) }
    var location by remember { mutableStateOf(course?.location.orEmpty()) }
    var start by remember { mutableStateOf((course?.startSection ?: 1).toString()) }
    var end by remember { mutableStateOf((course?.endSection ?: 2).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (course == null) "添加课程" else "编辑课程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("课程名") })
                OutlinedTextField(date, { date = it }, label = { Text("日期") }, enabled = course?.entity?.source != "school")
                OutlinedTextField(location, { location = it }, label = { Text("地点") })
                OutlinedTextField(teacher, { teacher = it }, label = { Text("教师（可选）") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("开始节") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it.filter(Char::isDigit) }, label = { Text("结束节") }, modifier = Modifier.weight(1f))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val parsedDate = runCatching { LocalDate.parse(date).toEpochDay() }.getOrNull()
                val first = start.toIntOrNull(); val last = end.toIntOrNull()
                when {
                    name.isBlank() -> error = "课程名不能为空"
                    parsedDate == null -> error = "日期无效"
                    first == null || last == null || first !in 1..14 || last !in first..14 -> error = "节次无效"
                    else -> onSave(parsedDate, name, teacher, location, first, last)
                }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
