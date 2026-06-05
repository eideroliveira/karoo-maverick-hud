package com.eider.karoomaverickhud.extension

import com.eider.karoomaverickhud.settings.HudConfig
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.random.Random

/**
 * Builds a [HudSnapshot] from a [HudConfig] using fabricated demo sensor values, so the settings
 * app can mirror "what the rider would see" onto the glasses live while they edit. Runs each demo
 * value through the real [FieldFormat] so units, zone colours and icons match the actual HUD.
 */
object HudPreviewBuilder {

    /**
     * A preview snapshot for [cfg]. [seed] jitters the live-ish values so the glasses feel alive;
     * [pageIndex] lets the caller cycle pages on the configured timer.
     */
    fun snapshot(cfg: HudConfig, seed: Int, pageIndex: Int): HudSnapshot {
        val zones = ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
        val gear = GearLayout(cfg.gear.front, cfg.gear.rear, cfg.gear.display)
        val cap = cellsForRows(cfg.rows)
        val pages = cfg.pages.map { page ->
            page.take(cap).map { id -> FieldFormat.format(id, demoState(id, cfg, seed), cfg.imperial, zones, null, gear) }
        }.filter { it.isNotEmpty() }
        val idx = if (pages.isEmpty()) 0 else pageIndex % pages.size
        return HudSnapshot(
            pages = pages,
            paused = false,
            recording = true, // so the screen renders the cells rather than "waiting for ride"
            pageIndex = idx,
            rows = cfg.rows,
            clock = "", // stamped by the bridge if the clock is enabled
            showIcons = cfg.showIcons,
        )
    }

    /** A fabricated [StreamState] carrying a plausible demo value for [dataTypeId]. */
    private fun demoState(dataTypeId: String, cfg: HudConfig, seed: Int): StreamState {
        val rng = Random(seed * 31 + dataTypeId.hashCode())
        fun jit() = rng.nextDouble() // 0..1
        val values: Map<String, Double> = when (dataTypeId) {
            DataType.Type.POWER -> mapOf(DataType.Field.POWER to (cfg.ftp * (0.70 + jit() * 0.40)))
            DataType.Type.CADENCE -> mapOf(DataType.Field.CADENCE to (cfg.idealCadence + (jit() - 0.5) * 22))
            DataType.Type.HEART_RATE -> mapOf(DataType.Field.HEART_RATE to (cfg.maxHr * (0.64 + jit() * 0.30)))
            DataType.Type.SPEED -> mapOf(DataType.Field.SPEED to ((30.0 + jit() * 8.0) / 3.6))
            DataType.Type.AVERAGE_SPEED -> mapOf(DataType.Field.AVERAGE_SPEED to (29.6 / 3.6))
            DataType.Type.DISTANCE -> mapOf(DataType.Field.DISTANCE to 42_100.0)
            DataType.Type.ELAPSED_TIME -> mapOf(DataType.Field.ELAPSED_TIME to 5_070_000.0) // 1:24:30 in ms
            DataType.Type.PEDAL_POWER_BALANCE -> mapOf(DataType.Field.PEDAL_POWER_BALANCE_LEFT to 51.0)
            DataType.Type.SHIFTING_GEARS -> mapOf(
                DataType.Field.SHIFTING_FRONT_GEAR_TEETH to cfg.gear.front.last().toDouble(),
                DataType.Field.SHIFTING_REAR_GEAR_TEETH to cfg.gear.rear[cfg.gear.rear.size / 2].toDouble(),
            )
            else -> mapOf(dataTypeId to jit() * 100.0)
        }
        return StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = values))
    }
}
