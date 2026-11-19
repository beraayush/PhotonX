package com.photonx.camera.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.photonx.camera.domain.PhotonXSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "photonx_settings_v3")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NATURAL_COLOR = booleanPreferencesKey("natural_color")
        val USE_VIVO_EXTENSIONS = booleanPreferencesKey("use_vivo_extensions")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val SHOW_BENCHMARKS = booleanPreferencesKey("show_benchmarks")
        val HDR_STRENGTH = floatPreferencesKey("hdr_strength")
        val PORTRAIT_BLUR_STRENGTH = floatPreferencesKey("portrait_blur_strength")
        val MACRO_CLARITY_STRENGTH = floatPreferencesKey("macro_clarity_strength")
        val COLOR_WARMTH = floatPreferencesKey("color_warmth")
    }

    val settingsFlow: Flow<PhotonXSettings> = context.dataStore.data.map { prefs ->
        PhotonXSettings(
            naturalColorScience = prefs[Keys.NATURAL_COLOR] ?: true,
            useVivoExtensions = prefs[Keys.USE_VIVO_EXTENSIONS] ?: false,
            showGrid = prefs[Keys.SHOW_GRID] ?: true,
            showBenchmarks = prefs[Keys.SHOW_BENCHMARKS] ?: true,
            hdrStrength = (prefs[Keys.HDR_STRENGTH] ?: 0.85f).coerceIn(0.0f, 1.0f),
            portraitBlurStrength = (prefs[Keys.PORTRAIT_BLUR_STRENGTH] ?: 0.70f).coerceIn(0.1f, 1.0f),
            macroClarityStrength = (prefs[Keys.MACRO_CLARITY_STRENGTH] ?: 0.35f).coerceIn(0.0f, 1.0f),
            colorWarmth = (prefs[Keys.COLOR_WARMTH] ?: 0.15f).coerceIn(-0.5f, 0.5f)
        )
    }

    suspend fun toggleNaturalColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NATURAL_COLOR] = enabled }
    }

    suspend fun toggleVivoExtensions(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_VIVO_EXTENSIONS] = enabled }
    }

    suspend fun toggleGrid(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_GRID] = enabled }
    }

    suspend fun toggleBenchmarks(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_BENCHMARKS] = enabled }
    }

    suspend fun setHdrStrength(strength: Float) {
        context.dataStore.edit { it[Keys.HDR_STRENGTH] = strength.coerceIn(0.0f, 1.0f) }
    }

    suspend fun setPortraitBlurStrength(strength: Float) {
        context.dataStore.edit { it[Keys.PORTRAIT_BLUR_STRENGTH] = strength.coerceIn(0.1f, 1.0f) }
    }

    suspend fun setMacroClarityStrength(strength: Float) {
        context.dataStore.edit { it[Keys.MACRO_CLARITY_STRENGTH] = strength.coerceIn(0.0f, 1.0f) }
    }

    suspend fun setColorWarmth(warmth: Float) {
        context.dataStore.edit { it[Keys.COLOR_WARMTH] = warmth.coerceIn(-0.5f, 0.5f) }
    }
}
