package com.eider.karoomaverickhud.maverick

import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide mirror of the battery-saver ("ECO") state, written solely by [MaverickBridge] and
 * read by the ride pipeline ([com.eider.karoomaverickhud.extension.RideHudExtension]) and the
 * glasses data field ([com.eider.karoomaverickhud.extension.GlassesDataType]) without a reference
 * to the bridge instance — the same decoupling pattern as [GlassesLinkState].
 *
 * Saver exists to stretch the glasses pack (the ride-limiting resource); when it's engaged the
 * bridge dims the display, slows the BLE poll, blanks the screen while the ride is paused/stopped,
 * and lengthens page cycling, while the pipeline lengthens the HUD push and — at [critical] — drops
 * to a single minimal page.
 */
object Eco {
    /** True while battery-saver is engaged — the manual toggle OR the auto low-battery threshold. */
    val active = MutableStateFlow(value = false)

    /** True when saver engaged automatically via the battery threshold (drives the "ECO (auto)" badge). */
    val auto = MutableStateFlow(value = false)

    /** True at critical battery: collapse to one minimal page at the lowest brightness for the final minutes. */
    val critical = MutableStateFlow(value = false)
}

/** Tuning constants for battery-saver, kept in one place so the power levers are easy to retune. */
object SaverTuning {
    /** Default glasses battery % at or below which saver auto-engages; rider-tunable in settings. */
    const val DEFAULT_THRESHOLD_PCT = 25

    /** Forced fixed brightness (0..100) while saver is active but the battery isn't yet critical. */
    const val SAVER_BRIGHTNESS = 25

    /** Lowest usable brightness once the battery is critical. */
    const val CRITICAL_BRIGHTNESS = 10

    /** HUD push floor while saver is active (ms); the pipeline takes the slower of this and any battery-warn tier. */
    const val SAVER_REFRESH_MS = 3_000L

    /** HUD push floor once critical (ms). */
    const val CRITICAL_REFRESH_MS = 5_000L

    /** Auto page-cycle floor while saver is active (ms) — lengthened, not paused, so paging still works. */
    const val SAVER_AUTOCYCLE_MS = 20_000L

    /** BLE telemetry poll cadence while saver is active and connected (ms); stretched from the active 2 s. */
    const val SAVER_POLL_MS = 5_000L

    /** Battery % at or below which the critical single-page fallback kicks in. */
    const val CRITICAL_PCT = 10

    /** The one minimal page shown at critical battery: speed + elapsed time, to squeeze out the final minutes. */
    val MINIMAL_PAGE: List<String> = listOf(DataType.Type.SPEED, DataType.Type.ELAPSED_TIME)
}
