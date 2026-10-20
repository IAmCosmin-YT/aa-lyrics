package dev.lyricdrive.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences, persisted in SharedPreferences and exposed as flows. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _wordSync = MutableStateFlow(prefs.getBoolean(KEY_WORD_SYNC, true))

    /** Word-by-word fill when the lyrics have word timing; off = whole lines light up at once. */
    val wordSync: StateFlow<Boolean> = _wordSync.asStateFlow()

    fun setWordSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WORD_SYNC, enabled).apply()
        _wordSync.value = enabled
    }

    private companion object {
        const val KEY_WORD_SYNC = "word_sync"
    }
}
