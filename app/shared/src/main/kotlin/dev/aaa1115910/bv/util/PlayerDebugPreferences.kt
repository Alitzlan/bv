package dev.aaa1115910.bv.util

import android.content.Context
import dev.aaa1115910.bv.BVApp

/**
 * Settings used only for optional diagnostic UI. Kept separate from build type so an
 * installable debug APK behaves like the normal app unless the user enables diagnostics.
 */
object PlayerDebugPreferences {
    private const val PREFERENCES_NAME = "player_debug_preferences"
    private const val KEY_SHOW_PLAYER_DEBUG_INFO = "show_player_debug_info"

    private val preferences
        get() = BVApp.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var showPlayerDebugInfo: Boolean
        get() = preferences.getBoolean(KEY_SHOW_PLAYER_DEBUG_INFO, false)
        set(value) {
            preferences.edit().putBoolean(KEY_SHOW_PLAYER_DEBUG_INFO, value).apply()
        }
}
