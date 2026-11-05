package dev.sjtu.allinone.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sjtu.allinone.SJTUApplication
import dev.sjtu.allinone.data.WidgetSettings
import dev.sjtu.allinone.ui.AppTheme
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {
    private val widgetId by lazy { intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val settings = (application as SJTUApplication).container.settings
        setContent {
            AppTheme {
                val initial by produceState(WidgetSettings()) { value = settings.widgetNow(widgetId) }
                var config by rememberConfig(initial)
                val scope = rememberCoroutineScope()
                Scaffold { padding ->
                    Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("今日课程组件", style = MaterialTheme.typography.headlineSmall)
                        Text("组件只读取本地课表；刷新按钮不会自动联网更新课表。")
                        ConfigSwitch("只看接下来", config.upcomingOnly) { config = config.copy(upcomingOnly = it) }
                        ConfigSwitch("深色背景", config.dark) { config = config.copy(dark = it) }
                        Text("背景不透明度：${(config.alpha * 100).toInt()}%")
                        Slider(config.alpha, { config = config.copy(alpha = it) }, valueRange = 0.2f..1f)
                        Text("字号：${(config.fontScale * 100).toInt()}%")
                        Slider(config.fontScale, { config = config.copy(fontScale = it) }, valueRange = 0.8f..1.4f)
                        Button(onClick = {
                            scope.launch {
                                settings.saveWidget(widgetId, config)
                                CourseWidgetProvider.updateAll(applicationContext)
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("添加组件") }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberConfig(initial: WidgetSettings) = androidx.compose.runtime.remember(initial) {
    mutableStateOf(initial)
}

@Composable
private fun ConfigSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
