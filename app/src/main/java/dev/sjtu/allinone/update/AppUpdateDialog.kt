package dev.sjtu.allinone.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateDialog(requests: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    }
    var handledRequests by rememberSaveable { mutableStateOf(0) }
    var update by rememberSaveable { mutableStateOf<AppUpdate?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var apkPath by rememberSaveable { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    fun install() {
        val apk = apkPath?.let(::File) ?: return
        try {
            if (!apk.isFile) {
                apkPath = null
                message = "安装包已被清理，请重新下载。"
                return
            }
            context.startActivity(AppUpdates.installIntent(context, apk))
            apkPath = null
            update = null
            message = null
        } catch (_: Exception) {
            message = "无法打开系统安装界面，请稍后重试。"
        }
    }

    val grantInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }

    LaunchedEffect(requests) {
        val manual = requests != handledRequests
        val now = System.currentTimeMillis()
        val elapsed = now - preferences.getLong("last_check", 0L)
        if (!manual && elapsed in 0 until TimeUnit.HOURS.toMillis(24)) return@LaunchedEffect
        if (manual) {
            update = null
            apkPath = null
            message = "正在检查更新…"
        }
        try {
            update = AppUpdates.check(context)
            preferences.edit().putLong("last_check", now).apply()
            message = if (manual && update == null) "当前已是最新版本。" else null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (manual) message = "暂时无法检查更新，请确认手机能访问 GitHub 后重试。"
        }
        handledRequests = requests
    }

    val available = update
    if (available == null && message == null && apkPath == null) return

    fun dismiss() {
        if (downloading) return
        update = null
        apkPath = null
        message = null
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(available?.let { "发现新版本 ${it.versionName}" } ?: "应用更新") },
        text = {
            Text(
                message ?: when {
                    apkPath != null && !context.packageManager.canRequestPackageInstalls() ->
                        "APK 已校验。请在系统设置中允许本应用安装未知应用，然后返回继续安装。"
                    apkPath != null -> "APK 已校验，可以覆盖安装。"
                    downloading -> "正在下载并校验 APK…"
                    else -> available?.notes?.ifBlank { "可在应用内下载新版 APK。" }.orEmpty()
                },
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            when {
                apkPath != null -> TextButton(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        install()
                    } else {
                        try {
                            grantInstall.launch(Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.fromParts("package", context.packageName, null),
                            ))
                        } catch (_: Exception) {
                            message = "无法打开安装授权页面，请在系统设置中手动授权。"
                        }
                    }
                }) { Text("继续安装") }
                available != null -> TextButton(enabled = !downloading, onClick = {
                    message = null
                    downloading = true
                    scope.launch {
                        try {
                            apkPath = AppUpdates.download(context, available).path
                            if (context.packageManager.canRequestPackageInstalls()) install()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            message = "下载或校验失败，请重试。"
                        } finally {
                            downloading = false
                        }
                    }
                }) { Text("下载并安装") }
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = ::dismiss) {
                Text(if (available != null || apkPath != null) "稍后" else "关闭")
            }
        },
    )
}
