package com.eider.karoomaverickhud.extension

/**
 * Debounced, hysteretic decision of whether the descent trajectory overlay should be shown, so it
 * neither flickers as the grade hovers around the threshold nor whips away the instant a descent
 * eases for a moment.
 *
 *  - SHOW: grade must sit at/below [SHOW_GRADE] (a descent) continuously for [HOLD_MS] before showing.
 *  - HIDE: grade must rise above [HIDE_GRADE] continuously for [HOLD_MS] before hiding.
 *  - The band between the two ([SHOW_GRADE]..[HIDE_GRADE]) holds whatever state we're in (hysteresis).
 *  - A flat-or-uphill grade (≥ [IMMEDIATE_HIDE_GRADE]) hides at once, bypassing the hold — so the map
 *    clears promptly once the rider is clearly climbing again.
 *
 * Pure and clock-injected ([advance] takes `nowMs`) so it's unit-testable; the live flow feeds it
 * `System.currentTimeMillis()` once per grade tick.
 */
data class DescentGate(
    val shown: Boolean = false,
    /** When the grade first started disagreeing with [shown]; null when steady (no flip pending). */
    val pendingSinceMs: Long? = null,
) {
    fun advance(grade: Double?, nowMs: Long): DescentGate {
        // No grade reading — hold the current state and cancel any pending flip (we can't time it).
        if (grade == null) return copy(pendingSinceMs = null)
        // Clearly not descending any more — drop the map immediately, no hold.
        if (shown && grade >= IMMEDIATE_HIDE_GRADE) return DescentGate(shown = false)
        val target = when {
            grade <= SHOW_GRADE -> true   // descending
            grade > HIDE_GRADE -> false   // clearly eased off
            else -> shown                 // hysteresis band: keep whatever we were doing
        }
        if (target == shown) return copy(pendingSinceMs = null)
        val since = pendingSinceMs ?: nowMs
        return if (nowMs - since >= HOLD_MS) DescentGate(shown = target) else copy(pendingSinceMs = since)
    }

    companion object {
        /** Show once the grade is at/below this (%, negative = downhill). */
        const val SHOW_GRADE = -2.0
        /** Start the hide timer once the grade rises above this (%); the SHOW..HIDE band holds state. */
        const val HIDE_GRADE = -1.0
        /** Flat-or-uphill grade (%) that hides the map immediately, skipping the hold. */
        const val IMMEDIATE_HIDE_GRADE = 0.0
        /** How long a grade must hold past a threshold before the show/hide flip commits. */
        const val HOLD_MS = 3_000L

        val INITIAL = DescentGate()
    }
}
