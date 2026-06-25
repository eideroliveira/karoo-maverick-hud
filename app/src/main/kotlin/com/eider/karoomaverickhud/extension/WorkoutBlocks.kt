package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState

/**
 * "Block · Rep" workout tracker — a synthetic HUD field that answers "which work interval am I on
 * within this block?". The Karoo SDK only ever streams the *current* step (no look-ahead, no
 * workout structure), so the block/rep position is inferred from the steps as they play, never read
 * ahead. Because it's stateful, the live pipeline keeps it in its own persistent flow (not the
 * per-layout [FormatContext], which is rebuilt whenever an auto-page appears and would reset the
 * count mid-ride).
 *
 * Heuristic (chosen with the rider):
 *  - A step is **rest** when its power target is below [REST_FRACTION_OF_FTP] of FTP (or it has no
 *    target); otherwise it's **work**.
 *  - A **rep** is the run of consecutive work steps up to its trailing rest — so a 2-step rep and a
 *    4-step rep both count once. The rep number bumps when a work step begins after a (short) rest.
 *  - A **block** ends on a *large* rest: one at least [BLOCK_REST_FACTOR]× the block's short rests.
 *    The next work step then starts a new block at rep 1.
 *  - The opening warm-up reads as a large rest, so the first real effort lands at block 1, rep 1.
 *
 * Known limits: starting mid-workout can't reconstruct earlier blocks (it counts from the first step
 * it sees); a workout whose blocks are single reps separated by equal big rests can't be split until
 * a *shorter* rest has been seen somewhere to calibrate against; manual step skips on the Karoo may
 * nudge the count.
 */
object WorkoutBlocks {

    /** Synthetic data-type id for the pickable field (kept out of stream subscription, injected). */
    const val FIELD_REP = "maverick.workout.blockrep"
    private const val PREFIX = "maverick.workout."

    /** Picker/tile metadata. */
    const val LABEL = "WORK REP"
    const val UNIT = "blk·rep"

    /** A step at or above this fraction of FTP counts as work; below it (or no target) is rest. */
    const val REST_FRACTION_OF_FTP = 0.60

    /** A rest at least this many times the block's short rests closes the block. */
    const val BLOCK_REST_FACTOR = 2.0

    /** Whether [id] is one of our injected (non-Karoo-stream) ids. */
    fun isSynthetic(id: String): Boolean = id.startsWith(PREFIX)

    /** One tick's view of the workout: current step number (null = no workout), target watts, and the
     *  step's remaining time in **seconds** (the stream ships ms; see [observe]). */
    data class Observation(val step: Int?, val targetWatts: Double?, val remainingSec: Double, val ftp: Int)

    /**
     * Tracker state, advanced one [Observation] at a time. [block]/[rep] hold the current position
     * (kept steady through a rest, so the tile shows the rep just finished). The `cur*` fields track
     * the in-progress step until it's committed on the next transition; [curCounted] guards the
     * one-time rep/block bump so a step counted on entry isn't re-counted on every later tick (and so
     * a late-arriving target that flips the classification work-ward still counts exactly once).
     */
    data class State(
        val block: Int,
        val rep: Int,
        val inBlock: Boolean,
        val curStep: Int?,
        val curIsWork: Boolean,
        val curCounted: Boolean,
        val curMaxRemainSec: Double,
        val prevCommittedWasWork: Boolean,
        val restRunSec: Double,
        val blockMinRestSec: Double?,
        val globalMinRestSec: Double?,
    ) {
        /** "block·rep" once a block has started, else dashes (warm-up, pre-workout, off-workout). */
        val display: String get() = if (inBlock && rep > 0) "$block·$rep" else "--"

        fun advance(obs: Observation): State {
            // No workout loaded → reset so the next workout starts clean.
            val step = obs.step ?: return INITIAL
            val isWork = classifyWork(obs.targetWatts, obs.ftp)
            val remain = obs.remainingSec.coerceAtLeast(0.0)

            if (step == curStep) {
                // Same step: keep the largest remaining seen as the step's duration, and let a target
                // that only just arrived settle the classification work-ward (then count once).
                var s = copy(curMaxRemainSec = maxOf(curMaxRemainSec, remain))
                if (isWork && !s.curIsWork) s = s.copy(curIsWork = true)
                return if (s.curIsWork && !s.curCounted) s.applyWorkEntry() else s
            }

            // Transition: fold the finishing step into history, then open the new one.
            var s = commitPrevious()
            s = s.copy(curStep = step, curIsWork = isWork, curCounted = false, curMaxRemainSec = remain)
            return if (s.curIsWork) s.applyWorkEntry() else s
        }

        /** Fold the just-finished [curStep] into the rest-run / work history. */
        private fun commitPrevious(): State {
            if (curStep == null) return this
            return if (curIsWork) {
                // A work step ended — the gap after it starts fresh.
                copy(prevCommittedWasWork = true, restRunSec = 0.0)
            } else {
                // A rest step ended — extend the current rest run by its duration.
                copy(prevCommittedWasWork = false, restRunSec = restRunSec + curMaxRemainSec)
            }
        }

        /** Apply the rep/block bump for the in-progress work step exactly once ([curCounted]). */
        private fun applyWorkEntry(): State {
            val counted = when {
                // Consecutive work steps with no rest between = same rep (multi-step interval).
                prevCommittedWasWork -> this
                // First effort of the ride → block 1, rep 1 (the warm-up rest is ignored).
                !inBlock -> copy(inBlock = true, block = 1, rep = 1)
                else -> {
                    val baseline = blockMinRestSec ?: globalMinRestSec
                    val blockBreak = baseline != null && restRunSec >= BLOCK_REST_FACTOR * baseline
                    if (blockBreak) {
                        // Large rest → new block; its short-rest baseline resets for the new block.
                        copy(block = block + 1, rep = 1, blockMinRestSec = null)
                    } else {
                        // Short rest → next rep; record it as a block (and ride) short-rest baseline.
                        copy(
                            rep = rep + 1,
                            blockMinRestSec = minOfNullable(blockMinRestSec, restRunSec),
                            globalMinRestSec = minOfNullable(globalMinRestSec, restRunSec),
                        )
                    }
                }
            }
            return counted.copy(curCounted = true)
        }
    }

    val INITIAL = State(
        block = 0, rep = 0, inBlock = false,
        curStep = null, curIsWork = false, curCounted = false, curMaxRemainSec = 0.0,
        prevCommittedWasWork = false, restRunSec = 0.0,
        blockMinRestSec = null, globalMinRestSec = null,
    )

    /** A step is work when it prescribes a target at/above the rest threshold; below (or none) is rest. */
    fun classifyWork(targetWatts: Double?, ftp: Int): Boolean {
        if (targetWatts == null || targetWatts <= 0.0) return false
        // No usable FTP → any positive target counts as work (best we can do without a reference).
        if (ftp <= 0) return true
        return targetWatts >= REST_FRACTION_OF_FTP * ftp
    }

    /** Build an [Observation] from the three workout streams + the rider's FTP. */
    fun observe(stepState: StreamState, targetState: StreamState, durState: StreamState, ftp: Int): Observation {
        val stepDp = (stepState as? StreamState.Streaming)?.dataPoint
        val stepCount = stepDp?.values?.get(DataType.Field.WORKOUT_STEP_COUNT)?.toInt() ?: 0
        // WORKOUT_STEP_COUNT only streams non-zero while a structured workout is loaded.
        val step = if (stepCount > 0) stepDp?.values?.get(DataType.Field.WORKOUT_CURRENT_STEP)?.toInt() else null

        val targetWatts = (targetState as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.WORKOUT_TARGET_VALUE)
        // Time-to-step-finish ships milliseconds (confirmed on-device); the tracker works in seconds.
        val remainMs = (durState as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.WORKOUT_TIME_TO_STEP_FINISH) ?: 0.0
        return Observation(step, targetWatts, remainMs / 1000.0, ftp)
    }

    /** The HUD cell for a computed [display] string ("1·2" or "--"). */
    fun cell(display: String): HudCell = HudCell(display, UNIT, HudColor.WHITE, HudIcon.TIME)

    /** A demo cell for the settings preview (the live value is injected by the pipeline). */
    fun previewCell(): HudCell = cell("1·2")

    private fun minOfNullable(a: Double?, b: Double): Double = if (a == null) b else minOf(a, b)
}
