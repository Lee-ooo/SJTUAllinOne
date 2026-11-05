package dev.sjtu.allinone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sjtu.allinone.core.SCHOOL_ZONE
import dev.sjtu.allinone.core.minuteText
import dev.sjtu.allinone.core.parseMinute
import dev.sjtu.allinone.data.local.OccupationEntity
import dev.sjtu.allinone.data.local.RoomEntity
import dev.sjtu.allinone.domain.intervalsOverlap
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun RoomScreen(viewModel: MainViewModel) {
    val buildings by viewModel.buildings.collectAsState()
    val allRooms by viewModel.rooms.collectAsState()
    val occupations by viewModel.occupations.collectAsState()
    val sections by viewModel.sections.collectAsState()
    val selectedDate by viewModel.selectedRoomDate.collectAsState()
    var campus by remember { mutableStateOf("") }
    var buildingId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now(SCHOOL_ZONE).toString()) }
    var start by remember { mutableStateOf("08:00") }
    var end by remember { mutableStateOf("09:40") }
    var freeOnly by remember { mutableStateOf(false) }
    var selectedRoom by remember { mutableStateOf<RoomEntity?>(null) }
    val campuses = buildings.map { it.campus }.distinct()
    val campusBuildings = buildings.filter { it.campus == campus }
    val rooms = allRooms.filter { it.buildingId == buildingId }
    val startMinute = start.parseMinute()
    val endMinute = end.parseMinute()

    LaunchedEffect(buildings) {
        if (campus.isEmpty() || campus !in campuses) campus = campuses.firstOrNull().orEmpty()
    }
    LaunchedEffect(campus, buildings) {
        if (buildingId !in campusBuildings.map { it.id }) buildingId = campusBuildings.firstOrNull()?.id
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceMenu("校区", campus.ifBlank { "校区" }, campuses.map { it to it }, Modifier.weight(1f)) { campus = it }
            ChoiceMenu("楼宇", campusBuildings.firstOrNull { it.id == buildingId }?.name ?: "楼宇",
                campusBuildings.map { it.id to it.name }, Modifier.weight(1.4f)) { buildingId = it }
            IconButton(onClick = viewModel::syncBuildings) { Icon(Icons.Default.Refresh, "更新楼宇目录") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { date = it }, label = { Text("日期") }, singleLine = true, modifier = Modifier.weight(1.4f))
            OutlinedTextField(start, { start = it }, label = { Text("开始") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(end, { end = it }, label = { Text("结束") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = false, onClick = {
                val now = ZonedDateTime.now(SCHOOL_ZONE)
                date = now.toLocalDate().toString(); start = "%02d:%02d".format(now.hour, now.minute)
                end = (now.hour * 60 + now.minute + 1).minuteText()
            }, label = { Text("现在") })
            FilterChip(selected = false, enabled = sections.isNotEmpty(), onClick = {
                val now = ZonedDateTime.now(SCHOOL_ZONE)
                val minute = now.hour * 60 + now.minute
                sections.firstOrNull { it.startMinute > minute }?.let {
                    date = now.toLocalDate().toString(); start = it.startMinute.minuteText(); end = it.endMinute.minuteText()
                }
            }, label = { Text("下一节") })
            FilterChip(selected = false, onClick = { start = "18:00"; end = "22:00" }, label = { Text("今晚") })
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("仅看无已知排课") })
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                val epochDay = runCatching { LocalDate.parse(date).toEpochDay() }.getOrNull()
                val id = buildingId
                if (epochDay != null && id != null && startMinute != null && endMinute != null && endMinute > startMinute) {
                    viewModel.syncRooms(id, epochDay)
                }
            }, enabled = buildingId != null && startMinute != null && endMinute != null && endMinute > startMinute) {
                Text("查询")
            }
        }
        Text("“无已知排课”不代表实时无人或教室已开放；临时活动与开放状态可能未知。",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        if (buildings.isEmpty()) {
            EmptyState("尚未读取楼宇目录", "点击右上刷新按钮，以匿名 IDS 会话读取校区和楼宇。")
        } else {
            val statuses = rooms.map { room ->
                val queryDate = runCatching { LocalDate.parse(date).toEpochDay() }.getOrNull()
                room to roomStatus(room, queryDate?.takeIf { it == selectedDate } ?: Long.MIN_VALUE,
                    startMinute, endMinute, occupations)
            }
            val visible = statuses.filter { !freeOnly || it.second.type == RoomStatusType.FREE }
            if (visible.isEmpty()) {
                EmptyState("没有可显示的教室", if (freeOnly) "没有符合“无已知排课”的结果；未知教室不会被当作空闲。" else "请选择日期和时间后点击查询。")
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.first.id }) { (room, status) ->
                    Card(onClick = { selectedRoom = room; viewModel.loadRoomDetail(room.id) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(room.name, fontWeight = FontWeight.SemiBold)
                                Text("${room.floor}${room.capacity?.let { " · $it 座位" }.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(status.label, color = when (status.type) {
                                RoomStatusType.FREE -> MaterialTheme.colorScheme.primary
                                RoomStatusType.OCCUPIED -> MaterialTheme.colorScheme.error
                                RoomStatusType.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    selectedRoom?.let { selected ->
        val room = allRooms.firstOrNull { it.id == selected.id } ?: selected
        RoomDetailDialog(room, occupations.filter { it.roomId == room.id }, onDismiss = { selectedRoom = null })
    }
}

private enum class RoomStatusType { FREE, OCCUPIED, UNKNOWN }
private data class RoomStatus(val type: RoomStatusType, val label: String)

private fun roomStatus(
    room: RoomEntity, date: Long, start: Int?, end: Int?, occupations: List<OccupationEntity>,
): RoomStatus {
    if (start == null || end == null || end <= start || room.scheduleDate != date || room.scheduleStatus != "KNOWN") {
        return RoomStatus(RoomStatusType.UNKNOWN, "排课未知")
    }
    val roomOccupations = occupations.filter { it.roomId == room.id }
    return if (roomOccupations.any { intervalsOverlap(it.startMinute, it.endMinute, start, end) }) {
        RoomStatus(RoomStatusType.OCCUPIED, "已知占用")
    } else RoomStatus(RoomStatusType.FREE, "无已知排课")
}

@Composable
private fun ChoiceMenu(
    label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(value, maxLines = 1)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

@Composable
private fun RoomDetailDialog(room: RoomEntity, occupations: List<OccupationEntity>, onDismiss: () -> Unit) {
    val facilities = remember(room.facilitiesJson) {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(room.facilitiesJson).asArrayOrEmpty().mapNotNull {
                val obj = it as? JsonObject
                val name = (obj?.get("name") as? JsonPrimitive)?.content
                val value = (obj?.get("value") as? JsonPrimitive)?.content
                if (name != null && value != null) "$name：$value" else null
            }
        }.getOrDefault(emptyList())
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(room.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("位置：${room.buildingId} / ${room.floor}")
            room.capacity?.let { Text("座位数：$it") }
            Text("当日排课时间线", fontWeight = FontWeight.Bold)
            if (occupations.isEmpty()) Text("无已知排课；开放状态未知")
            occupations.sortedBy { it.startMinute }.forEach {
                Text("${it.startMinute.minuteText()}–${it.endMinute.minuteText()}  ${it.title}")
                Text("${it.teacher}${if (it.sourceType.isNotBlank()) " · 来源 ${it.sourceType}" else ""}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (facilities.isNotEmpty()) {
                Text("设施", fontWeight = FontWeight.Bold)
                facilities.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("地图仅在取得可靠位置后提供；当前未验证楼宇坐标。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

private fun kotlinx.serialization.json.JsonElement.asArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
