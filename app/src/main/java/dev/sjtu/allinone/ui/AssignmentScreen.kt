package dev.sjtu.allinone.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Html
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sjtu.allinone.core.SCHOOL_ZONE
import dev.sjtu.allinone.data.local.AssignmentEntity
import dev.sjtu.allinone.domain.DeadlineGroup
import dev.sjtu.allinone.domain.deadlineGroup
import dev.sjtu.allinone.domain.isDone
import dev.sjtu.allinone.domain.isPlatformSubmitted
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class ViewMode { DDL, COURSE }
private enum class DoneFilter { ALL, PENDING, DONE }

@Composable
fun AssignmentScreen(viewModel: MainViewModel, initialAssignmentId: String?) {
    val assignments by viewModel.assignments.collectAsState()
    var mode by remember { mutableStateOf(ViewMode.DDL) }
    var filter by remember { mutableStateOf(DoneFilter.ALL) }
    var selected by remember { mutableStateOf<AssignmentEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val now by rememberSchoolNow()
    val visible = assignments.filter { item ->
        val done = item.isDone()
        when (filter) { DoneFilter.ALL -> true; DoneFilter.PENDING -> !done; DoneFilter.DONE -> done }
    }
    LaunchedEffect(initialAssignmentId, assignments) {
        if (initialAssignmentId != null && selected == null) selected = assignments.firstOrNull { it.id == initialAssignmentId }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mode == ViewMode.DDL, onClick = { mode = ViewMode.DDL }, label = { Text("按 DDL") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == ViewMode.COURSE, onClick = { mode = ViewMode.COURSE }, label = { Text("按课程") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "手动添加任务") }
            IconButton(onClick = viewModel::syncAssignments) { Icon(Icons.Default.Refresh, "同步 Canvas") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DoneFilter.entries.forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item }, label = {
                    Text(when (item) { DoneFilter.ALL -> "全部"; DoneFilter.PENDING -> "待完成"; DoneFilter.DONE -> "已完成" })
                })
            }
        }
        if (assignments.isEmpty()) {
            EmptyState("还没有作业", "登录并同步 Canvas，或点击 ＋ 添加平台之外的任务。教师只在公告或外部网站发布的任务不会被自动发现。")
        } else if (visible.isEmpty()) {
            EmptyState("当前筛选下没有任务", "切换上方完成状态筛选查看其他任务。")
        } else if (mode == ViewMode.DDL) {
            DeadlineList(visible, now, onClick = { selected = it })
        } else {
            CourseAssignmentList(visible, onClick = { selected = it })
        }
    }
    selected?.let {
        AssignmentDetailDialog(it, onDismiss = { selected = null }, onSave = { updated ->
            viewModel.saveAssignment(updated); selected = null
        }, onDelete = if (it.source == "local") {{ viewModel.deleteAssignment(it.id); selected = null }} else null)
    }
    if (adding) {
        NewAssignmentDialog(onDismiss = { adding = false }, onSave = { name, course, due, notes ->
            viewModel.newAssignment(name, course, due, notes); adding = false
        })
    }
}

@Composable
private fun DeadlineList(items: List<AssignmentEntity>, now: java.time.ZonedDateTime, onClick: (AssignmentEntity) -> Unit) {
    val groups = items.groupBy { deadlineGroup(it.dueAt, now) }
    val labels = linkedMapOf(
        DeadlineGroup.OVERDUE to "逾期", DeadlineGroup.TODAY to "今天",
        DeadlineGroup.NEXT_SEVEN_DAYS to "未来七天", DeadlineGroup.LATER to "更晚",
        DeadlineGroup.NO_DUE_DATE to "无截止时间",
    )
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (group, label) ->
            val groupItems = groups[group].orEmpty().sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.name }))
            if (groupItems.isNotEmpty()) {
                item { Text("$label · ${groupItems.size}", style = MaterialTheme.typography.titleSmall,
                    color = if (group == DeadlineGroup.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                items(groupItems, key = { it.id }) { AssignmentCard(it, onClick) }
            }
        }
    }
}

@Composable
private fun CourseAssignmentList(items: List<AssignmentEntity>, onClick: (AssignmentEntity) -> Unit) {
    val expanded = remember { mutableStateOf(setOf<Pair<String, String>>()) }
    val groups = items.groupBy { it.courseId to it.courseName }.toList().sortedBy { it.first.second }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (course, tasks) ->
            item(key = "course-${course.first.length}:${course.first}${course.second}") {
                Card(Modifier.fillMaxWidth().clickable {
                    expanded.value = if (course in expanded.value) expanded.value - course else expanded.value + course
                }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(course.second, fontWeight = FontWeight.Bold)
                            Text("${tasks.count { !it.isDone() }} 项待完成 · 共 ${tasks.size} 项",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (course in expanded.value) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                    }
                }
            }
            if (course in expanded.value) {
                items(tasks.sortedBy { it.dueAt ?: Long.MAX_VALUE }, key = { it.id }) { AssignmentCard(it, onClick) }
            }
        }
    }
}

@Composable
private fun AssignmentCard(item: AssignmentEntity, onClick: (AssignmentEntity) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick(item) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.name, fontWeight = FontWeight.SemiBold)
            Text(item.courseName, style = MaterialTheme.typography.bodySmall)
            Text(item.dueAt?.let { "截止：${formatDateTime(it)}" } ?: "无截止时间",
                color = if (item.missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.isPlatformSubmitted()) AssistChip(onClick = { onClick(item) }, label = { Text("平台已提交") })
                if (item.localCompleted) AssistChip(onClick = { onClick(item) }, label = { Text("本地完成") })
                if (item.isQuiz) AssistChip(onClick = { onClick(item) }, label = { Text("测验") })
                if (item.source == "local") AssistChip(onClick = { onClick(item) }, label = { Text("手动") })
            }
        }
    }
}

@Composable
private fun AssignmentDetailDialog(
    item: AssignmentEntity, onDismiss: () -> Unit, onSave: (AssignmentEntity) -> Unit, onDelete: (() -> Unit)?,
) {
    var completed by remember { mutableStateOf(item.localCompleted) }
    var notes by remember { mutableStateOf(item.notes) }
    var reminder by remember { mutableStateOf(item.reminderAt) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val plainDescription = remember(item.descriptionHtml) {
        if (item.descriptionHtml.isBlank()) "" else Html.fromHtml(item.descriptionHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(item.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.courseName, fontWeight = FontWeight.SemiBold)
            Text(item.dueAt?.let { "准确截止时间：${formatDateTime(it)}" } ?: "无截止时间")
            if (item.isPlatformSubmitted()) {
                Text("平台状态：${item.workflowState}${item.submittedAt?.let { " · ${formatDateTime(it)}" }.orEmpty()}")
            } else Text("平台状态：未提交（本地勾选不会提交到 Canvas）")
            if (item.late) Text("平台标记：迟交")
            if (item.missing) Text("平台标记：缺失", color = MaterialTheme.colorScheme.error)
            if (item.excused) Text("平台标记：已免除")
            if (item.redoRequest) Text("平台标记：要求重交")
            if (plainDescription.isNotBlank()) Text(plainDescription, style = MaterialTheme.typography.bodyMedium)
            if (item.url.isNotBlank()) {
                TextButton(onClick = {
                    val uri = Uri.parse(item.url)
                    if (uri.scheme == "https" && uri.host == "oc.sjtu.edu.cn") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }) { Icon(Icons.Default.OpenInBrowser, null); Text("打开 Canvas 原页面") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(completed, { completed = it })
                Text("本地完成（不等于平台提交）")
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            Text("提醒（系统可能延迟，不承诺精确闹钟）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = reminder == null, onClick = { reminder = null }, label = { Text("无") })
                FilterChip(selected = reminder != null && item.dueAt != null && reminder == item.dueAt!! - 3_600_000,
                    onClick = { reminder = item.dueAt?.minus(3_600_000) }, enabled = item.dueAt != null,
                    label = { Text("前1小时") })
                FilterChip(selected = reminder != null && item.dueAt != null && reminder == item.dueAt!! - 86_400_000,
                    onClick = { reminder = item.dueAt?.minus(86_400_000) }, enabled = item.dueAt != null,
                    label = { Text("前1天") })
            }
        }
    }, confirmButton = {
        Row {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("删除") }
            TextButton(onClick = {
                if (reminder != null && Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                onSave(item.copy(localCompleted = completed, notes = notes, reminderAt = reminder))
            }) { Text("保存") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun NewAssignmentDialog(onDismiss: () -> Unit, onSave: (String, String, Long?, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加手动任务") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("任务名") })
            OutlinedTextField(course, { course = it }, label = { Text("课程/分类") })
            OutlinedTextField(due, { due = it }, label = { Text("截止：YYYY-MM-DD HH:mm（可空）") })
            OutlinedTextField(notes, { notes = it }, label = { Text("备注") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val dueAt = if (due.isBlank()) null else runCatching {
            LocalDateTime.parse(due, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(SCHOOL_ZONE).toInstant().toEpochMilli()
        }.getOrNull()
        if (name.isBlank()) error = "任务名不能为空"
        else if (due.isNotBlank() && dueAt == null) error = "截止时间格式无效"
        else onSave(name, course, dueAt, notes)
    }) { Text("添加") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

