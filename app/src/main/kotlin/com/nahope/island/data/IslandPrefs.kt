package com.nahope.island.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "island_prefs")

/**
 * Geometry + behaviour knobs. Every Android punch-hole sits somewhere different, so all of this
 * is user-tunable from the calibration screen instead of hard-coded.
 */
data class IslandConfig(
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val idleWidth: Float = 110f,
    val idleHeight: Float = 32f,
    val cornerRadius: Float = 16f,
    val enabled: Boolean = false,
    val showWhenIdle: Boolean = true,
    val hideOnLandscape: Boolean = true,
    val expandedWidth: Float = 340f,
    val expandedHeight: Float = 186f,
)

class IslandPrefs(context: Context) {

    private val store = context.applicationContext.dataStore

    val config: Flow<IslandConfig> = store.data.map { p ->
        IslandConfig(
            offsetX = p[KEY_OFFSET_X] ?: 0,
            offsetY = p[KEY_OFFSET_Y] ?: 0,
            idleWidth = p[KEY_IDLE_W] ?: 110f,
            idleHeight = p[KEY_IDLE_H] ?: 32f,
            cornerRadius = p[KEY_RADIUS] ?: 16f,
            enabled = p[KEY_ENABLED] ?: false,
            showWhenIdle = p[KEY_SHOW_IDLE] ?: true,
            hideOnLandscape = p[KEY_HIDE_LANDSCAPE] ?: true,
            expandedWidth = p[KEY_EXPANDED_W] ?: 340f,
            expandedHeight = p[KEY_EXPANDED_H] ?: 186f,
        )
    }

    suspend fun setOffsetX(v: Int) = put(KEY_OFFSET_X, v)
    suspend fun setOffsetY(v: Int) = put(KEY_OFFSET_Y, v)
    suspend fun setIdleWidth(v: Float) = put(KEY_IDLE_W, v)
    suspend fun setIdleHeight(v: Float) = put(KEY_IDLE_H, v)
    suspend fun setCornerRadius(v: Float) = put(KEY_RADIUS, v)
    suspend fun setEnabled(v: Boolean) = put(KEY_ENABLED, v)
    suspend fun setShowWhenIdle(v: Boolean) = put(KEY_SHOW_IDLE, v)
    suspend fun setHideOnLandscape(v: Boolean) = put(KEY_HIDE_LANDSCAPE, v)
    suspend fun setExpandedWidth(v: Float) = put(KEY_EXPANDED_W, v)
    suspend fun setExpandedHeight(v: Float) = put(KEY_EXPANDED_H, v)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private companion object {
        val KEY_OFFSET_X = intPreferencesKey("offset_x")
        val KEY_OFFSET_Y = intPreferencesKey("offset_y")
        val KEY_IDLE_W = floatPreferencesKey("idle_w")
        val KEY_IDLE_H = floatPreferencesKey("idle_h")
        val KEY_RADIUS = floatPreferencesKey("radius")
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_SHOW_IDLE = booleanPreferencesKey("show_idle")
        val KEY_HIDE_LANDSCAPE = booleanPreferencesKey("hide_landscape")
        val KEY_EXPANDED_W = floatPreferencesKey("expanded_w")
        val KEY_EXPANDED_H = floatPreferencesKey("expanded_h")
    }
}
