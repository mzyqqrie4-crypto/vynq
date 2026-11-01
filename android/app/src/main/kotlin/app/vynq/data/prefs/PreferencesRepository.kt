package app.vynq.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.vynq.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "vynq_prefs")

/**
 * Non-sensitive local preferences only. Anything secret (keys, tokens) goes to
 * Keystore-backed storage in Phase 2/3, never here.
 */
class PreferencesRepository(private val context: Context) {

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { themeModeFromKey(it[KEY_THEME]) }
    val callQuality: Flow<CallQuality> = context.dataStore.data.map { CallQuality.fromKey(it[KEY_QUALITY]) }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[KEY_THEME] = mode.name }
    suspend fun setCallQuality(quality: CallQuality) = context.dataStore.edit { it[KEY_QUALITY] = quality.name }

    private companion object {
        val KEY_THEME: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        val KEY_QUALITY: Preferences.Key<String> = stringPreferencesKey("call_quality")
    }
}

fun themeModeFromKey(key: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == key } ?: ThemeMode.System

/**
 * User-facing call quality profiles. Phase 4/5 maps them onto LiveKit capture and
 * publish settings; until then the choice is stored, not yet applied.
 */
enum class CallQuality(val title: String, val description: String) {
    Automatic("Automatic", "Adapts to your connection, battery and device temperature. Recommended."),
    DataSaver("Data saver", "Lower resolution and bitrate. Uses noticeably less mobile data."),
    High("High quality", "Prefers sharp, smooth video when the network allows it."),
    Maximum("Maximum", "Highest resolution your device can send reliably. Uses more battery and data.");

    companion object {
        fun fromKey(key: String?): CallQuality = entries.firstOrNull { it.name == key } ?: Automatic
    }
}
