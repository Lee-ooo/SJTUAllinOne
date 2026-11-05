package dev.sjtu.allinone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sjtu.allinone.core.minuteText
import dev.sjtu.allinone.core.weekdayName
import dev.sjtu.allinone.data.local.ExamEntity
import dev.sjtu.allinone.data.local.GradeEntity
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun AcademicScreen(viewModel: MainViewModel) {
    val exams by viewModel.exams.collectAsState()
    val grades by viewModel.grades.collectAsState()
    val year by viewModel.selectedYear.collectAsState()
    val semester by viewModel.selectedSemester.collectAsState()
    var showExams by remember { mutableStateOf(true) }
    var selectedGrade by remember { mutableStateOf<GradeEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(showExams, { showExams = true }, label = { Text("考试") })
            Spacer(Modifier.padding(4.dp))
            FilterChip(!showExams, { showExams = false }, label = { Text("成绩") })
            Spacer(Modifier.weight(1f))
            IconButton(viewModel::syncAcademics) { Icon(Icons.Default.Refresh, "同步考试与成绩") }
        }
        if (showExams) {
            val current = exams.filter { it.year == year && it.semester == semester }
                .sortedWith(compareBy({ it.date }, { it.startMinute }))
            Text("$year · 学期 $semester", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelLarge)
            if (current.isEmpty()) EmptyState("当前学期没有考试缓存", "点击右上同步。空结果与登录失效、网络失败会分别提示，旧缓存不会因失败被清空。")
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(current, key = { it.id }) { ExamCard(it) }
            }
        } else {
            if (grades.isEmpty()) EmptyState("还没有成绩", "点击右上同步历史成绩；成绩明细获取失败不会影响列表。")
            val groups = grades.groupBy { it.year to it.semester }.toList()
                .sortedWith(compareByDescending<Pair<Pair<String, Int>, List<GradeEntity>>> { it.first.first }
                    .thenByDescending { it.first.second })
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (term, termGrades) ->
                    item(key = "term-${term.first}-${term.second}") {
                        Text("${term.first} · 学期 ${term.second}", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    items(termGrades.sortedBy { it.name }, key = { it.id }) { grade ->
                        Card(onClick = { selectedGrade = grade }) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(grade.name, fontWeight = FontWeight.SemiBold)
                                    Text("${grade.credit} 学分${grade.teacher.ifBlank { "" }.let { if (it.isEmpty()) "" else " · $it" }}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(grade.score, style = MaterialTheme.typography.titleLarge)
                                    Text("绩点 ${grade.gpa ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedGrade?.let { grade ->
        GradeDetailDialog(grade, onDismiss = { selectedGrade = null })
    }
}

@Composable
private fun ExamCard(exam: ExamEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(exam.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            val date = LocalDate.ofEpochDay(exam.date)
            Text("$date 周${weekdayName(date.dayOfWeek.value)}")
            Text("${exam.startMinute.minuteText()}–${exam.endMinute.minuteText()}")
            if (exam.type.isNotBlank()) Text("类型：${exam.type}")
            if (exam.address.isNotBlank()) Text("地点：${exam.address}")
        }
    }
}

@Composable
private fun GradeDetailDialog(grade: GradeEntity, onDismiss: () -> Unit) {
    val details = remember(grade.detailsJson) {
        runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(grade.detailsJson) as? JsonArray)?.mapNotNull { item ->
                val obj = item as? JsonObject
                val name = (obj?.get("name") as? JsonPrimitive)?.content ?: return@mapNotNull null
                val proportion = (obj["proportion"] as? JsonPrimitive)?.content.orEmpty()
                val score = (obj["score"] as? JsonPrimitive)?.content.orEmpty()
                Triple(name, proportion, score)
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(grade.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("学年学期：${grade.year} / ${grade.semester}")
            Text("成绩：${grade.score}")
            Text("绩点：${grade.gpa ?: "未提供"}")
            Text("学分：${grade.credit}")
            if (grade.teacher.isNotBlank()) Text("教师：${grade.teacher}")
            if (grade.courseNature.isNotBlank()) Text("课程性质：${grade.courseNature}")
            if (grade.gradeNature.isNotBlank()) Text("成绩性质：${grade.gradeNature}")
            if (grade.pass.isNotBlank()) Text("通过状态：${grade.pass}")
            if (details.isNotEmpty()) {
                Text("分项成绩", fontWeight = FontWeight.Bold)
                details.forEach { (name, proportion, score) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, Modifier.weight(1f))
                        if (proportion.isNotBlank()) Text("$proportion  ")
                        Text(score)
                    }
                }
            } else Text("暂无分项成绩", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
