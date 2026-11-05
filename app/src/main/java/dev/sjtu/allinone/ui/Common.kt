package dev.sjtu.allinone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import dev.sjtu.allinone.core.SCHOOL_ZONE
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LightColors = lightColorScheme(
    primary = Color(0xFF355CDE), secondary = Color(0xFF00696D),
    background = Color(0xFFF5F7FC), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EDF6),
)
private val DarkColors = darkColorScheme(primary = Color(0xFFB5C4FF), secondary = Color(0xFF80D4D8))

@Composable
fun rememberSchoolNow() = produceState(ZonedDateTime.now(SCHOOL_ZONE)) {
    while (true) {
        value = ZonedDateTime.now(SCHOOL_ZONE)
        delay(30_000)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

@Composable
fun InlineError(text: String) {
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

fun formatDateTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(epochMillis))
