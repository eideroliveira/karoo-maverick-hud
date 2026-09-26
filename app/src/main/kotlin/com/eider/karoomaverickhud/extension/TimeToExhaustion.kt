package com.eider.karoomaverickhud.extension

import kotlin.math.exp

/**
 * Time to exhaustion (TTE): a synthetic HUD field estimating how long the rider can hold the current
 * power before MPA (Maximal Power Available, from a Xert-style extension) falls to meet it.
 *
 * Model: above threshold, MPA drains roughly in proportion to how far the rider is over it:
 *
 *     dMPA/dt ≈ −k · (P − TP)        TP ≈ the rider's FTP
 *
 * so the time until MPA meets P is `(MPA − P) / (k · (P − TP))`. The drain rate `k` isn't known up
 * front (the extension's MPA↔HIE mapping is private), so it's **learned from the MPA stream itself**:
 * over a trailing window of above-threshold samples, the observed MPA slope divided by the mean
 * excess power gives one estimate of `k`, blended into a running value. Because `k` stays roughly
 * constant across efforts while the projection uses the *current* power, TTE reacts the instant the
 * rider surges or eases, instead of waiting for an MPA slope to catch up.
 *
 * Shows "--" at or below threshold (sustainable) and until `k` has been learned on the first effort.
 * Stateful, so the live pipeline keeps it in its own persistent flow, like [WorkoutBlocks]; the
 * learned `k` survives between efforts, which is the point.
 */
object TimeToExhaustion {

    /** Synthetic data-type id for the pickable field (kept out of stream subscription, injected). */
    const val FIELD_TTE = "maverick.power.tte"

    /** Picker/tile metadata. */
    const val LABEL = "TTE"
    const val UNIT = "TTE"

    /** The pipeline feeds the estimator one power+MPA reading this often (ms). */
    const val SAMPLE_MS = 1_000L

    /** Time constant of the power smoothing (s), so pedal-stroke noise doesn't swing the estimate. */
    const val POWER_TAU_SEC = 3.0

    /** Trailing window of above-threshold samples the MPA slope is fitted over (s). */
    const val WINDOW_SEC = 15.0

    /** Minimum span of that window before a slope is trusted (s). */
    const val MIN_FIT_SEC = 6.0

    /** Power must clear TP by at least this much to count as "above threshold" (W). */
    const val MIN_EXCESS_W = 10.0

    /** Weight of each fresh `k` observation in the running value (per tick, ~1 Hz). */
    const val K_BLEND = 0.2

    /** Plausibility clamp on a single `k` observation (1/s). */
    const val K_MIN = 1e-3
    const val K_MAX = 1.0

    /** Estimates beyond this (s) read as "20+": the effort is effectively sustainable. */
    const val MAX_DISPLAY_SEC = 20 * 60.0

    /** A gap longer than this (s) between readings restarts the power smoothing. */
    private const val STALE_GAP_SEC = 5.0

    /** Whether [id] is this field's injected (non-Karoo-stream) id. */
    fun isSynthetic(id: String): Boolean = id == FIELD_TTE

    /** One fitted point: time (s), MPA (W) and smoothed power (W). */
    data class Sample(val t: Double, val mpa: Double, val power: Double)

    /**
     * Estimator state, advanced one reading at a time. [power] is the smoothed power, [window] the
     * contiguous run of above-threshold samples (cleared on dropping below), [k] the learned drain
     * rate (null until the first usable fit).
     */
    data class State(
        val lastT: Double? = null,
        val power: Double? = null,
        val mpa: Double? = null,
        val tp: Int = 0,
        val window: List<Sample> = emptyList(),
        val k: Double? = null,
    ) {
        /** Seconds until MPA meets the current power; null at/below threshold or before `k` is learned. */
        val tteSec: Double?
            get() {
                val p = power ?: return null
                val m = mpa ?: return null
                val kk = k ?: return null
                if (tp <= 0) return null
                val excess = p - tp
                if (excess < MIN_EXCESS_W) return null
                return ((m - p) / (kk * excess)).coerceAtLeast(0.0)
            }

        /**
         * Fold in one reading at [nowMs]: raw [rawPower] (W), [mpa] (W) and threshold [tp] (FTP).
         * A missing power or MPA reading, or no usable threshold, clears the fit window but keeps
         * the learned `k`.
         */
        fun advance(nowMs: Long, rawPower: Double?, mpa: Double?, tp: Int): State {
            val t = nowMs / 1000.0
            if (rawPower == null) return copy(lastT = t, power = null, mpa = mpa, tp = tp, window = emptyList())

            val prev = power
            val dt = lastT?.let { t - it }
            val smoothed = if (prev == null || dt == null || dt <= 0.0 || dt > STALE_GAP_SEC) {
                rawPower
            } else {
                prev + (1.0 - exp(-dt / POWER_TAU_SEC)) * (rawPower - prev)
            }

            val base = copy(lastT = t, power = smoothed, mpa = mpa, tp = tp)
            if (mpa == null || tp <= 0 || smoothed - tp < MIN_EXCESS_W) return base.copy(window = emptyList())

            val window = (window + Sample(t, mpa, smoothed)).filter { t - it.t <= WINDOW_SEC }
            val kObs = observeK(window, tp) ?: return base.copy(window = window)
            val learned = k?.let { it + K_BLEND * (kObs - it) } ?: kObs
            return base.copy(window = window, k = learned)
        }
    }

    val INITIAL = State()

    /**
     * One `k` estimate from an above-threshold [window]: the least-squares MPA slope over the mean
     * excess power. Null while the window is too short, or when MPA isn't falling (the extension may
     * lag the surge by a beat, or be publishing a flat value).
     */
    fun observeK(window: List<Sample>, tp: Int): Double? {
        if (window.size < 3) return null
        if (window.last().t - window.first().t < MIN_FIT_SEC) return null
        val n = window.size
        val meanT = window.sumOf { it.t } / n
        val meanM = window.sumOf { it.mpa } / n
        var sxx = 0.0
        var sxy = 0.0
        for (s in window) {
            val dx = s.t - meanT
            sxx += dx * dx
            sxy += dx * (s.mpa - meanM)
        }
        if (sxx <= 0.0) return null
        val slope = sxy / sxx
        val excess = window.sumOf { it.power } / n - tp
        if (slope >= 0.0 || excess < MIN_EXCESS_W) return null
        return (-slope / excess).coerceIn(K_MIN, K_MAX)
    }

    /** "m:ss", "20+" past [MAX_DISPLAY_SEC], or "--" when there's no estimate. */
    fun display(sec: Double?): String {
        if (sec == null) return "--"
        if (sec >= MAX_DISPLAY_SEC) return "${(MAX_DISPLAY_SEC / 60).toInt()}+"
        val s = sec.toLong()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Urgency colour: red ≤1 min, orange ≤3 min, yellow ≤5 min, green beyond; white with no estimate. */
    fun color(sec: Double?): HudColor = when {
        sec == null -> HudColor.WHITE
        sec <= 60.0 -> HudColor.RED
        sec <= 180.0 -> HudColor.ORANGE
        sec <= 300.0 -> HudColor.YELLOW
        else -> HudColor.GREEN
    }

    /** The HUD cell for an estimate (null = "--"). Marked with the time glyph + a "TTE" tag. */
    fun cell(sec: Double?): HudCell = HudCell(display(sec), UNIT, color(sec), HudIcon.TIME, iconLabel = LABEL)

    /** A demo cell for the settings preview (the live value is injected by the pipeline). */
    fun previewCell(): HudCell = cell(150.0)
}
