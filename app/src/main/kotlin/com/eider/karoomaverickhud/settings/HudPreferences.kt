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
import com.eider.karoomaverickhud.extension.DEFAULT_HR_ZONES
import com.eider.karoomaverickhud.extension.DEFAULT_POWER_ZONES
import com.eider.karoomaverickhud.extension.HudFontSize
import com.eider.karoomaverickhud.extension.MAX_ROWS
import com.eider.karoomaverickhud.extension.MIN_ROWS
import com.eider.karoomaverickhud.extension.ZoneBand
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PageMode { AUTO, MANUAL }

/**
 * Drivetrain setup for the GEAR field. [source] is "auto" (read live from SRAM AXS / Shimano Di2
 * during the ride) or "manual" (hand-entered for mechanical groupsets). [front]/[rear] are teeth
 * counts. [display] picks the HUD readout style: "gear" (front·rear position), "ratio", "inches".
 */
@Serializable
data class GearConfig(
    val source: String = "auto",
    val drivetrainId: String? = "sram_red_1033",
    val front: List<Int> = listOf(48, 35),
    val rear: List<Int> = listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33),
    val showField: Boolean = true,
    val display: String = "teeth", // "teeth" (50/14) | "ratio" | "inches" (legacy "gear" == teeth)
)

data class HudConfig(
    val maverickDeviceId: String?,
    val maverickDeviceName: String?,
    val imperial: Boolean,
    val refreshIntervalMs: Long,
    val pageMode: PageMode,
    val autoCycleMs: Long,
    /**
     * Custom glasses pages, each a list of Karoo data-type ids (capped to 2 × [rows] when rendered).
     * The glasses pages to render (race mode shows the race-flagged subset).
     */
    val pages: List<List<String>>,
    /**
     * The workout page's fields — shown automatically as the first page while a structured
     * workout is loaded (gated in the extension on WORKOUT_STEP_COUNT > 0). Editable like any
     * page, but it can't be removed or reordered; it simply doesn't render outside a workout.
     */
    val workoutPage: List<String>,
    /**
     * The Strava-segment page's fields — shown and pinned automatically while a live segment is
     * running. Editable like the workout page; doesn't render outside a segment.
     */
    val segmentPage: List<String>,
    /**
     * The climb page's fields — shown and pinned automatically while on a climb when no Strava
     * segment is running. Editable like the workout page; doesn't render off a climb.
     */
    val climbPage: List<String>,
    /** Rows of fields per glasses column (2 or 3); each page holds 2 columns × this many. */
    val rows: Int,
    /** Training-zone thresholds for value coloring (0 disables that field's coloring). */
    val ftp: Int,
    val maxHr: Int,
    val idealCadence: Int,
    /** Editable zone bands (boundaries as % of FTP / MaxHR); drive the HUD value coloring. */
    val ftpZones: List<ZoneBand>,
    val hrZones: List<ZoneBand>,
    /** Drivetrain setup for the GEAR field. */
    val gear: GearConfig,
    /** Whether the glasses show the time-of-day clock in the corner. */
    val showClock: Boolean,
    /** Whether the glasses draw each data field's icon next to its unit/label. */
    val showIcons: Boolean,
    /** Glasses value font size (Small 33 / Medium 38 / Large 42 px face). Medium is the default. */
    val hudFontSize: HudFontSize,
    /**
     * Manual battery-saver ("ECO") toggle. When on, the bridge dims the display, slows the BLE poll
     * and HUD push, blanks while the ride is paused/stopped, and lengthens page cycling — all to
     * stretch the glasses pack. Saver also auto-engages once the battery falls to [saverThresholdPct].
     */
    val saverEnabled: Boolean,
    /** Glasses battery % at or below which saver auto-engages (shown as "ECO (auto)"). */
    val saverThresholdPct: Int,
    /**
     * Whether the next-climb radar look-ahead is active. When on (and a route with climbs is
     * loaded), the HUD pins a brief preview page — distance/ETA/grade/length — as the rider nears a
     * climb, then hands off to the on-climb page at the ramp. See [RouteRadar].
     */
    val radarEnabled: Boolean,
    /**
     * Whether the route trajectory map is active. When on (and a route is loaded), the glasses draw
     * a heading-up preview of the road ahead — auto-pinned on descents to read curves early, and
     * also reachable as a normal page. See [RouteTrajectory].
     */
    val trajectoryEnabled: Boolean,
    /**
     * Race mode. When on, only the race-flagged numbered pages (see [racePages]) plus the dynamic
     * auto-pages (workout/segment/climb/radar/trajectory) are shown, and paging auto-cycles
     * regardless of [pageMode] — a hands-off layout for racing. See [raceBasePages].
     */
    val raceMode: Boolean,
    /**
     * Per numbered-page "include in race mode" flags, aligned by index with [pages]. A missing entry
     * counts as included, so by default every page is a race page until you uncheck some.
     */
    val racePages: List<Boolean>,
) {
    companion object {
        /** Seeded layout matching the original hard-coded two-page HUD. */
        val DEFAULT_PAGES: List<List<String>> = listOf(
            listOf(DataType.Type.POWER, DataType.Type.CADENCE, DataType.Type.PEDAL_POWER_BALANCE, DataType.Type.SPEED),
            listOf(DataType.Type.DISTANCE, DataType.Type.AVERAGE_SPEED, DataType.Type.HEART_RATE, DataType.Type.ELAPSED_TIME),
        )

        /**
         * Seeded workout page. Live power/cadence render "value/target" by themselves while a
         * step prescribes a target, so no dedicated target cells are needed — the freed slots
         * carry interval NP, HR, time-left and step progress
         * (slot order TL-TR-BL-BR-ML-MR; the lens columns draw 0-4-2 / 1-5-3).
         */
        val DEFAULT_WORKOUT_PAGE: List<String> = listOf(
            DataType.Type.POWER,
            DataType.Type.CADENCE,
            DataType.Type.NORMALIZED_POWER_LAP,
            DataType.Type.WORKOUT_INTERVAL_COUNT,
            DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
            DataType.Type.HEART_RATE,
        )

        /**
         * Seeded Strava-segment page. Built-in fields by default (Δ-vs-PR, power, cadence, segment
         * time & distance/elevation remaining); the rider can swap in extension fields such as MPA
         * or time-to-summit via the picker. Slot order TL-TR-BL-BR-ML-MR (lens columns draw
         * 0-4-2 / 1-5-3): left = power, seg-dist, cadence; right = vs-PR, seg-elev, seg-time.
         */
        val DEFAULT_SEGMENT_PAGE: List<String> = listOf(
            DataType.Type.POWER,
            DataType.Type.SEGMENT_TIME_TO_PR,
            DataType.Type.CADENCE,
            DataType.Type.SEGMENT_TIME,
            DataType.Type.SEGMENT_DISTANCE_REMAINING,
            DataType.Type.SEGMENT_ELEVATION_REMAINING,
        )

        /**
         * Seeded climb page. Grade replaces the segment's "time to summit" cell (per the design);
         * left = power, dist-to-top, cadence; right = grade, elev-to-top, VAM.
         */
        val DEFAULT_CLIMB_PAGE: List<String> = listOf(
            DataType.Type.POWER,
            DataType.Type.ELEVATION_GRADE,
            DataType.Type.CADENCE,
            DataType.Type.VERTICAL_SPEED,
            DataType.Type.DISTANCE_TO_TOP,
            DataType.Type.ELEVATION_TO_TOP,
        )

        val DEFAULT = HudConfig(
            maverickDeviceId = null,
            maverickDeviceName = null,
            imperial = false,
            refreshIntervalMs = 1_000L,
            pageMode = PageMode.AUTO,
            autoCycleMs = 5_000L,
            pages = DEFAULT_PAGES,
            workoutPage = DEFAULT_WORKOUT_PAGE,
            segmentPage = DEFAULT_SEGMENT_PAGE,
            climbPage = DEFAULT_CLIMB_PAGE,
            rows = 3,
            ftp = 200,
            maxHr = 185,
            idealCadence = 90,
            ftpZones = DEFAULT_POWER_ZONES,
            hrZones = DEFAULT_HR_ZONES,
            gear = GearConfig(),
            showClock = true,
            showIcons = false,
            hudFontSize = HudFontSize.MEDIUM,
            saverEnabled = false,
            saverThresholdPct = com.eider.karoomaverickhud.maverick.SaverTuning.DEFAULT_THRESHOLD_PCT,
            radarEnabled = true,
            trajectoryEnabled = true,
            raceMode = false,
            racePages = emptyList(),
        )
    }
}

/**
 * The base (numbered) pages to cycle in race mode: those flagged in [raceFlags] (a missing flag
 * counts as included), each capped to [cap] cells and non-empty. Falls back to all pages when
 * nothing is flagged, so race mode is never blank.
 */
fun raceBasePages(pages: List<List<String>>, raceFlags: List<Boolean>, cap: Int): List<List<String>> {
    val flagged = pages.filterIndexed { i, _ -> raceFlags.getOrElse(i) { true } }
        .map { it.take(cap) }.filter { it.isNotEmpty() }
    return flagged.ifEmpty { pages.map { it.take(cap) }.filter { it.isNotEmpty() } }
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
    private val KEY_WORKOUT_PAGE = stringPreferencesKey("workout_page_json")
    private val KEY_SEGMENT_PAGE = stringPreferencesKey("segment_page_json")
    private val KEY_CLIMB_PAGE = stringPreferencesKey("climb_page_json")
    private val KEY_ROWS = intPreferencesKey("rows")
    private val KEY_FTP = intPreferencesKey("ftp")
    private val KEY_MAX_HR = intPreferencesKey("max_hr")
    private val KEY_IDEAL_CADENCE = intPreferencesKey("ideal_cadence")
    private val KEY_FTP_ZONES = stringPreferencesKey("ftp_zones_json")
    private val KEY_HR_ZONES = stringPreferencesKey("hr_zones_json")
    private val KEY_GEAR = stringPreferencesKey("gear_json")
    private val KEY_SHOW_CLOCK = booleanPreferencesKey("show_clock")
    private val KEY_SHOW_ICONS = booleanPreferencesKey("show_icons")
    private val KEY_HUD_FONT_SIZE = stringPreferencesKey("hud_font_size")
    private val KEY_SAVER_ENABLED = booleanPreferencesKey("saver_enabled")
    private val KEY_SAVER_THRESHOLD = intPreferencesKey("saver_threshold_pct")
    private val KEY_RADAR_ENABLED = booleanPreferencesKey("radar_enabled")
    private val KEY_TRAJECTORY_ENABLED = booleanPreferencesKey("trajectory_enabled")
    private val KEY_RACE_MODE = booleanPreferencesKey("race_mode")
    private val KEY_RACE_PAGES = stringPreferencesKey("race_pages_json")

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
            workoutPage = prefs[KEY_WORKOUT_PAGE]?.let { decodeFields(it) } ?: HudConfig.DEFAULT_WORKOUT_PAGE,
            segmentPage = prefs[KEY_SEGMENT_PAGE]?.let { decodeFields(it) } ?: HudConfig.DEFAULT_SEGMENT_PAGE,
            climbPage = prefs[KEY_CLIMB_PAGE]?.let { decodeFields(it) } ?: HudConfig.DEFAULT_CLIMB_PAGE,
            rows = (prefs[KEY_ROWS] ?: HudConfig.DEFAULT.rows).coerceIn(MIN_ROWS, MAX_ROWS),
            ftp = prefs[KEY_FTP] ?: HudConfig.DEFAULT.ftp,
            maxHr = prefs[KEY_MAX_HR] ?: HudConfig.DEFAULT.maxHr,
            idealCadence = prefs[KEY_IDEAL_CADENCE] ?: HudConfig.DEFAULT.idealCadence,
            // Fall back to the current default whenever the saved band count differs (e.g. after
            // the power model changed from 6 to 7 zones) so the colour mapping stays in lockstep.
            ftpZones = prefs[KEY_FTP_ZONES]?.let { decodeZones(it) }
                ?.takeIf { it.size == HudConfig.DEFAULT.ftpZones.size } ?: HudConfig.DEFAULT.ftpZones,
            hrZones = prefs[KEY_HR_ZONES]?.let { decodeZones(it) }
                ?.takeIf { it.size == HudConfig.DEFAULT.hrZones.size } ?: HudConfig.DEFAULT.hrZones,
            gear = prefs[KEY_GEAR]?.let { decodeGear(it) } ?: HudConfig.DEFAULT.gear,
            showClock = prefs[KEY_SHOW_CLOCK] ?: HudConfig.DEFAULT.showClock,
            showIcons = prefs[KEY_SHOW_ICONS] ?: HudConfig.DEFAULT.showIcons,
            hudFontSize = prefs[KEY_HUD_FONT_SIZE]?.let { runCatching { HudFontSize.valueOf(it) }.getOrNull() }
                ?: HudConfig.DEFAULT.hudFontSize,
            saverEnabled = prefs[KEY_SAVER_ENABLED] ?: HudConfig.DEFAULT.saverEnabled,
            saverThresholdPct = (prefs[KEY_SAVER_THRESHOLD] ?: HudConfig.DEFAULT.saverThresholdPct).coerceIn(0, 100),
            radarEnabled = prefs[KEY_RADAR_ENABLED] ?: HudConfig.DEFAULT.radarEnabled,
            trajectoryEnabled = prefs[KEY_TRAJECTORY_ENABLED] ?: HudConfig.DEFAULT.trajectoryEnabled,
            raceMode = prefs[KEY_RACE_MODE] ?: HudConfig.DEFAULT.raceMode,
            racePages = prefs[KEY_RACE_PAGES]?.let { decodeBools(it) } ?: HudConfig.DEFAULT.racePages,
        )
    }

    suspend fun setRows(context: Context, rows: Int) {
        context.dataStore.edit { it[KEY_ROWS] = rows.coerceIn(MIN_ROWS, MAX_ROWS) }
    }

    suspend fun setZones(context: Context, ftp: Int, maxHr: Int, idealCadence: Int) {
        context.dataStore.edit {
            it[KEY_FTP] = ftp
            it[KEY_MAX_HR] = maxHr
            it[KEY_IDEAL_CADENCE] = idealCadence
        }
    }

    suspend fun setFtp(context: Context, ftp: Int) {
        context.dataStore.edit { it[KEY_FTP] = ftp }
    }

    suspend fun setMaxHr(context: Context, maxHr: Int) {
        context.dataStore.edit { it[KEY_MAX_HR] = maxHr }
    }

    suspend fun setIdealCadence(context: Context, idealCadence: Int) {
        context.dataStore.edit { it[KEY_IDEAL_CADENCE] = idealCadence }
    }

    suspend fun setFtpZones(context: Context, zones: List<ZoneBand>) {
        context.dataStore.edit { it[KEY_FTP_ZONES] = Json.encodeToString(zones) }
    }

    suspend fun setHrZones(context: Context, zones: List<ZoneBand>) {
        context.dataStore.edit { it[KEY_HR_ZONES] = Json.encodeToString(zones) }
    }

    suspend fun setGear(context: Context, gear: GearConfig) {
        context.dataStore.edit { it[KEY_GEAR] = Json.encodeToString(gear) }
    }

    suspend fun setShowClock(context: Context, show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_CLOCK] = show }
    }

    suspend fun setShowIcons(context: Context, show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_ICONS] = show }
    }

    suspend fun setHudFontSize(context: Context, size: HudFontSize) {
        context.dataStore.edit { it[KEY_HUD_FONT_SIZE] = size.name }
    }

    suspend fun setSaverEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_SAVER_ENABLED] = enabled }
    }

    suspend fun setSaverThreshold(context: Context, pct: Int) {
        context.dataStore.edit { it[KEY_SAVER_THRESHOLD] = pct.coerceIn(0, 100) }
    }

    suspend fun setRadarEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_RADAR_ENABLED] = enabled }
    }

    suspend fun setTrajectoryEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_TRAJECTORY_ENABLED] = enabled }
    }

    suspend fun setRaceMode(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[KEY_RACE_MODE] = enabled }
    }

    suspend fun setRacePages(context: Context, flags: List<Boolean>) {
        context.dataStore.edit { it[KEY_RACE_PAGES] = Json.encodeToString(flags) }
    }

    suspend fun setAutoCycleMs(context: Context, ms: Long) {
        context.dataStore.edit { it[KEY_AUTO_CYCLE_MS] = ms }
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

    suspend fun setWorkoutPage(context: Context, fields: List<String>) {
        context.dataStore.edit { it[KEY_WORKOUT_PAGE] = Json.encodeToString(fields) }
    }

    suspend fun setSegmentPage(context: Context, fields: List<String>) {
        context.dataStore.edit { it[KEY_SEGMENT_PAGE] = Json.encodeToString(fields) }
    }

    suspend fun setClimbPage(context: Context, fields: List<String>) {
        context.dataStore.edit { it[KEY_CLIMB_PAGE] = Json.encodeToString(fields) }
    }

    private fun decodePages(json: String): List<List<String>>? =
        runCatching { Json.decodeFromString<List<List<String>>>(json) }.getOrNull()

    private fun decodeFields(json: String): List<String>? =
        runCatching { Json.decodeFromString<List<String>>(json) }.getOrNull()

    private fun decodeBools(json: String): List<Boolean>? =
        runCatching { Json.decodeFromString<List<Boolean>>(json) }.getOrNull()

    private fun decodeZones(json: String): List<ZoneBand>? =
        runCatching { Json.decodeFromString<List<ZoneBand>>(json) }.getOrNull()

    private fun decodeGear(json: String): GearConfig? =
        runCatching { Json.decodeFromString<GearConfig>(json) }.getOrNull()
}
