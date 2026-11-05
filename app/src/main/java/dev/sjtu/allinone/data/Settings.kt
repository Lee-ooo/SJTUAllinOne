package dev.sjtu.allinone.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class WidgetSettings(
    val upcomingOnly: Boolean = false,
    val dark: Boolean = false,
    val alpha: Float = 0.94f,
    val fontScale: Float = 1f,
)

class Settings(private val context: Context) {
    private val accountKey = stringPreferencesKey("active_account")
    private val yearKey = stringPreferencesKey("year")
    private val semesterKey = intPreferencesKey("semester")

    val accountId: Flow<String> = context.store.data.map { it[accountKey] ?: "workspace-default" }
    val year: Flow<String> = context.store.data.map { it[yearKey] ?: "2026-2027" }
    val semester: Flow<Int> = context.store.data.map { it[semesterKey] ?: 1 }

    suspend fun activeAccount(): String = accountId.first()

    suspend fun newAccount(): String {
        val id = "workspace-${UUID.randomUUID()}"
        context.store.edit { it[accountKey] = id }
        return id
    }

    suspend fun setSemester(year: String, semester: Int) {
        context.store.edit {
            it[yearKey] = year
            it[semesterKey] = semester
        }
    }

    fun widget(id: Int): Flow<WidgetSettings> = context.store.data.map { it.toWidgetSettings(id) }
    suspend fun widgetNow(id: Int): WidgetSettings = context.store.data.first().toWidgetSettings(id)

    suspend fun saveWidget(id: Int, settings: WidgetSettings) {
        context.store.edit {
            it[booleanPreferencesKey("widget_${id}_upcoming")] = settings.upcomingOnly
            it[booleanPreferencesKey("widget_${id}_dark")] = settings.dark
            it[floatPreferencesKey("widget_${id}_alpha")] = settings.alpha
            it[floatPreferencesKey("widget_${id}_font")] = settings.fontScale
        }
    }

    suspend fun removeWidget(id: Int) {
        context.store.edit {
            it.remove(booleanPreferencesKey("widget_${id}_upcoming"))
            it.remove(booleanPreferencesKey("widget_${id}_dark"))
            it.remove(floatPreferencesKey("widget_${id}_alpha"))
            it.remove(floatPreferencesKey("widget_${id}_font"))
        }
    }

    private fun Preferences.toWidgetSettings(id: Int) = WidgetSettings(
        upcomingOnly = this[booleanPreferencesKey("widget_${id}_upcoming")] ?: false,
        dark = this[booleanPreferencesKey("widget_${id}_dark")] ?: false,
        alpha = this[floatPreferencesKey("widget_${id}_alpha")] ?: 0.94f,
        fontScale = this[floatPreferencesKey("widget_${id}_font")] ?: 1f,
    )
}
