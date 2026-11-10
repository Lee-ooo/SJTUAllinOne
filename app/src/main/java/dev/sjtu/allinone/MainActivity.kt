package dev.sjtu.allinone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sjtu.allinone.auth.LoginActivity
import dev.sjtu.allinone.data.local.SyncStateEntity
import dev.sjtu.allinone.ui.AcademicScreen
import dev.sjtu.allinone.ui.AppTheme
import dev.sjtu.allinone.ui.AssignmentScreen
import dev.sjtu.allinone.ui.MainViewModel
import dev.sjtu.allinone.ui.RoomScreen
import dev.sjtu.allinone.ui.TimetableScreen
import dev.sjtu.allinone.ui.formatDateTime
import dev.sjtu.allinone.update.AppUpdateDialog

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application) }
    private val deepLinkCourse = mutableStateOf<String?>(null)
    private val deepLinkAssignment = mutableStateOf<String?>(null)
    private var timetableRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        setContent { AppTheme { MainApp(viewModel, deepLinkCourse.value, deepLinkAssignment.value, timetableRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshSavedSessions()
    }

    private fun readIntent(intent: Intent) {
        deepLinkCourse.value = intent.getStringExtra("courseId")
        deepLinkAssignment.value = intent.getStringExtra("assignmentId")
        if (deepLinkAssignment.value != null) deepLinkCourse.value = null
        if (intent.action == ACTION_OPEN_TIMETABLE) timetableRequests++
    }

    companion object {
        private const val ACTION_OPEN_TIMETABLE = "dev.sjtu.allinone.action.OPEN_TIMETABLE"

        fun timetableIntent(context: Context) = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_TIMETABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun courseIntent(context: Context, id: String) = Intent(context, MainActivity::class.java)
            .putExtra("courseId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        fun assignmentIntent(context: Context, id: String) = Intent(context, MainActivity::class.java)
            .putExtra("assignmentId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel, initialCourseId: String?, initialAssignmentId: String?, timetableRequests: Int) {
    var tab by rememberSaveable { mutableIntStateOf(if (initialAssignmentId == null) 0 else 1) }
    LaunchedEffect(initialAssignmentId) { if (initialAssignmentId != null) tab = 1 }
    LaunchedEffect(initialCourseId) { if (initialCourseId != null) tab = 0 }
    LaunchedEffect(timetableRequests) { if (timetableRequests > 0) tab = 0 }
    val operation by viewModel.operation.collectAsState()
    val syncStates by viewModel.syncStates.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSyncCenter by remember { mutableStateOf(false) }
    var updateRequests by rememberSaveable { mutableIntStateOf(0) }
    var timetableSettingsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab != 0) timetableSettingsExpanded = false }
    val context = LocalContext.current
    val loginLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    fun login(source: String) {
        viewModel.openLogin {
            loginLauncher.launch(Intent(context, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SOURCE, source))
        }
    }
    LaunchedEffect(operation.message) {
        operation.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    val titles = listOf("课表", "作业", "教室", "学业")
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(titles[tab])
                        if (tab == 0) IconButton(onClick = { timetableSettingsExpanded = !timetableSettingsExpanded }) {
                            Icon(Icons.Default.Settings, "课表设置", Modifier.size(20.dp))
                        }
                    }
                }, actions = {
                    IconButton(onClick = { showSyncCenter = true }) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "账号与同步")
                    }
                })
                if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Default.CalendarMonth, Icons.Default.Assignment,
                    Icons.Default.MeetingRoom, Icons.Default.School)
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icons[index], contentDescription = title) }, label = { Text(title) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> TimetableScreen(viewModel, initialCourseId, timetableSettingsExpanded)
                1 -> AssignmentScreen(viewModel, initialAssignmentId)
                2 -> RoomScreen(viewModel)
                else -> AcademicScreen(viewModel)
            }
        }
    }
    if (showSyncCenter) {
        SyncCenterDialog(syncStates, onDismiss = { showSyncCenter = false },
            onSchoolLogin = { login(LoginActivity.SOURCE_SCHOOL) },
            onCanvasLogin = { login(LoginActivity.SOURCE_CANVAS) },
            onLogout = { viewModel.logout(); showSyncCenter = false },
            onCheckUpdate = { showSyncCenter = false; updateRequests++ })
    }
    AppUpdateDialog(updateRequests)
}

@Composable
private fun SyncCenterDialog(
    states: List<SyncStateEntity>, onDismiss: () -> Unit,
    onSchoolLogin: () -> Unit, onCanvasLogin: () -> Unit, onLogout: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("账号与同步") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("课表由你点击自动导入并确认保存，不会后台更新；每个学期独立保存。组件离线读取本地数据。",
                    style = MaterialTheme.typography.bodySmall)
                listOf("timetable" to "课表", "canvas" to "Canvas", "rooms" to "IDS 教室",
                    "exams" to "考试", "grades" to "成绩").forEach { (source, label) ->
                    val state = states.firstOrNull { it.source == source }
                    Column(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        if (source != "rooms") Text(when (state?.authState) {
                            "VALID" -> "登录已验证"
                            "REQUIRED" -> "需要重新登录"
                            else -> "登录尚未验证"
                        }, style = MaterialTheme.typography.bodySmall)
                        Text(state?.lastSuccessAt?.let { "上次成功：${formatDateTime(it)}" } ?: "尚未成功同步",
                            style = MaterialTheme.typography.bodySmall)
                        state?.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSchoolLogin) { Text("教务登录") }
                    TextButton(onClick = onCanvasLogin) { Text("Canvas 登录") }
                }
                TextButton(onClick = { confirmLogout = true }) { Text("退出并切换账号") }
                TextButton(onClick = onCheckUpdate) { Text("检查更新") }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("清除本账号数据？") },
            text = { Text("将退出教务和 Canvas，并清除本账号的本地课程、作业、成绩及教室缓存。此操作不可撤销。") },
            confirmButton = { TextButton(onClick = onLogout) { Text("退出并清除") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
    }
}
