package com.eider.karoomaverickhud.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PageMode { AUTO, FOLLOW_KAROO, MANUAL }

data class HudConfig(
    val maverickDeviceId: String?,
    val maverickDeviceName: String?,
    val imperial: Boolean,
    val refreshIntervalMs: Long,
    val pageMode: PageMode,
    val autoCycleMs: Long,
) {
    companion object {
        val DEFAULT = HudConfig(
            maverickDeviceId = null,
            maverickDeviceName = null,
            imperial = false,
            refreshIntervalMs = 1_000L,
            pageMode = PageMode.AUTO,
            autoCycleMs = 5_000L,
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("hud_prefs")

object HudPreferences {
    private val KEY_DEVICE_ID = stringPreferencesKey("maverick_device_id")
    private val KEY_DEVICE_NAME = stringPreferencesKey("maverick_device_name")
    private val KEY_IMPERIAL = booleanPreferencesKey("imperial")
    private val KEY_REFRESH_MS = longPreferencesKey("refresh_ms")
    private val KEY_PAGE_MODE = stringPreferencesKey("page_mode")
    private val KEY_AUTO_CYCLE_MS = longPreferencesKey("auto_cycle_ms")

    fun flow(context: Context): Flow<HudConfig> = context.dataStore.data.map { prefs ->
        HudConfig(
            maverickDeviceId = prefs[KEY_DEVICE_ID],
            maverickDeviceName = prefs[KEY_DEVICE_NAME],
            imperial = prefs[KEY_IMPERIAL] ?: HudConfig.DEFAULT.imperial,
            refreshIntervalMs = prefs[KEY_REFRESH_MS] ?: HudConfig.DEFAULT.refreshIntervalMs,
            pageMode = prefs[KEY_PAGE_MODE]?.let { runCatching { PageMode.valueOf(it) }.getOrNull() }
                ?: HudConfig.DEFAULT.pageMode,
            autoCycleMs = prefs[KEY_AUTO_CYCLE_MS] ?: HudConfig.DEFAULT.autoCycleMs,
        )
    }

    suspend fun setPairedDevice(context: Context, id: String?, name: String?) {
        context.dataStore.edit { p ->
            if (id == null) p.remove(KEY_DEVICE_ID) else p[KEY_DEVICE_ID] = id
            if (name == null) p.remove(KEY_DEVICE_NAME) else p[KEY_DEVICE_NAME] = name
        }
    }

    suspend fun setImperial(context: Context, imperial: Boolean) {
        context.dataStore.edit { it[KEY_IMPERIAL] = imperial }
    }

    suspend fun setPageMode(context: Context, mode: PageMode) {
        context.dataStore.edit { it[KEY_PAGE_MODE] = mode.name }
    }
}
