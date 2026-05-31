package com.github.momentum4control

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "momentum4_settings")

data class AppSettings(
    val deviceMac: String = "",
    val deviceName: String = "",
    val modeOffEnabled: Boolean = true,
    val modeAncEnabled: Boolean = true,
    val modeAmbEnabled: Boolean = false,
    val currentMode: NoiseMode = NoiseMode.ANC,
)

class SettingsStore(private val context: Context) {
    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            deviceMac = prefs[Keys.DEVICE_MAC] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: "",
            modeOffEnabled = prefs[Keys.MODE_OFF] ?: true,
            modeAncEnabled = prefs[Keys.MODE_ANC] ?: true,
            modeAmbEnabled = prefs[Keys.MODE_AMB] ?: false,
            currentMode = try {
                NoiseMode.valueOf(prefs[Keys.CURRENT_MODE] ?: NoiseMode.ANC.name)
            } catch (_: IllegalArgumentException) {
                NoiseMode.ANC
            },
        )
    }

    suspend fun updateDevice(mac: String, name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DEVICE_MAC] = mac
            prefs[Keys.DEVICE_NAME] = name
        }
    }

    suspend fun updateModes(off: Boolean, anc: Boolean, amb: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MODE_OFF] = off
            prefs[Keys.MODE_ANC] = anc
            prefs[Keys.MODE_AMB] = amb
        }
    }

    suspend fun updateCurrentMode(mode: NoiseMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CURRENT_MODE] = mode.name
        }
    }

    private object Keys {
        val DEVICE_MAC = stringPreferencesKey("device_mac")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val MODE_OFF = booleanPreferencesKey("mode_off")
        val MODE_ANC = booleanPreferencesKey("mode_anc")
        val MODE_AMB = booleanPreferencesKey("mode_amb")
        val CURRENT_MODE = stringPreferencesKey("current_mode")
    }
}
