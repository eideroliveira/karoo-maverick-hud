package com.eider.karoomaverickhud.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eider.karoomaverickhud.extension.HudFieldId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PageMode { AUTO, FOLLOW_KAROO, MANUAL }

data class HudConfig(
    val maverickDeviceId: String?,
    val maverickDeviceName: String?,
    val imperial: Boolean,
    val refreshIntervalMs: Long,
    val pageMode: PageMode,
    val autoCycleMs: Long,
    /**
     * Custom glasses pages, each a list of Karoo data-type ids (max [com.eider.karoomaverickhud.extension.MAX_CELLS]).
     * Used by AUTO/MANUAL modes; FOLLOW_KAROO ignores these and mirrors the Karoo page.
     */
    val pages: List<List<String>>,
    /** Training-zone thresholds for value coloring (0 disables that field's coloring). */
    val ftp: Int,
    val maxHr: Int,
    val idealCadence: Int,
) {
    companion object {
        /** Seeded layout matching the original hard-coded two-page HUD. */
        val DEFAULT_PAGES: List<List<String>> = listOf(
            listOf(HudFieldId.POWER, HudFieldId.CADENCE, HudFieldId.LR_BALANCE, HudFieldId.SPEED).map { it.dataTypeId },
            listOf(HudFieldId.DISTANCE, HudFieldId.AVG_SPEED, HudFieldId.HEART_RATE, HudFieldId.ELAPSED_TIME).map { it.dataTypeId },
        )

        val DEFAULT = HudConfig(
            maverickDeviceId = null,
            maverickDeviceName = null,
            imperial = false,
            refreshIntervalMs = 1_000L,
            pageMode = PageMode.AUTO,
            autoCycleMs = 5_000L,
            pages = DEFAULT_PAGES,
            ftp = 200,
            maxHr = 185,
            idealCadence = 90,
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
    private val KEY_PAGES = stringPreferencesKey("pages_json")
    private val KEY_FTP = intPreferencesKey("ftp")
    private val KEY_MAX_HR = intPreferencesKey("max_hr")
    private val KEY_IDEAL_CADENCE = intPreferencesKey("ideal_cadence")

    fun flow(context: Context): Flow<HudConfig> = context.dataStore.data.map { prefs ->
        HudConfig(
            maverickDeviceId = prefs[KEY_DEVICE_ID],
            maverickDeviceName = prefs[KEY_DEVICE_NAME],
            imperial = prefs[KEY_IMPERIAL] ?: HudConfig.DEFAULT.imperial,
            refreshIntervalMs = prefs[KEY_REFRESH_MS] ?: HudConfig.DEFAULT.refreshIntervalMs,
            pageMode = prefs[KEY_PAGE_MODE]?.let { runCatching { PageMode.valueOf(it) }.getOrNull() }
                ?: HudConfig.DEFAULT.pageMode,
            autoCycleMs = prefs[KEY_AUTO_CYCLE_MS] ?: HudConfig.DEFAULT.autoCycleMs,
            pages = prefs[KEY_PAGES]?.let { decodePages(it) } ?: HudConfig.DEFAULT_PAGES,
            ftp = prefs[KEY_FTP] ?: HudConfig.DEFAULT.ftp,
            maxHr = prefs[KEY_MAX_HR] ?: HudConfig.DEFAULT.maxHr,
            idealCadence = prefs[KEY_IDEAL_CADENCE] ?: HudConfig.DEFAULT.idealCadence,
        )
    }

    suspend fun setZones(context: Context, ftp: Int, maxHr: Int, idealCadence: Int) {
        context.dataStore.edit {
            it[KEY_FTP] = ftp
            it[KEY_MAX_HR] = maxHr
            it[KEY_IDEAL_CADENCE] = idealCadence
        }
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

    suspend fun setPages(context: Context, pages: List<List<String>>) {
        context.dataStore.edit { it[KEY_PAGES] = Json.encodeToString(pages) }
    }

    private fun decodePages(json: String): List<List<String>>? =
        runCatching { Json.decodeFromString<List<List<String>>>(json) }.getOrNull()
}
