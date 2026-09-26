package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Torque fields: rendered as whole N·m on their own hammer glyph. Icons off, the unit reads in full
 * ("Nm avg"); icons on, live torque shows the hammer alone and each variant tags it ("avg").
 */
class TorqueFieldFormatTest {

    private val zones = ZoneConfig(ftp = 250, maxHr = 185, idealCadence = 90)

    private fun torque(id: String, nm: Double): HudCell =
        FieldFormat.format(
            id,
            StreamState.Streaming(DataPoint(dataTypeId = id, values = mapOf(DataType.Field.TORQUE to nm))),
            imperial = false,
            zones = zones,
        )

    @Test
    fun liveTorqueRendersWholeNewtonMetres() {
        val c = torque(DataType.Type.TORQUE, 26.6)
        assertEquals("27", c.value)
        assertEquals("Nm", c.units)
        assertEquals(HudColor.WHITE, c.color)
        assertEquals(HudIcon.TORQUE, c.icon)
        assertEquals("", c.iconLabel)
    }

    @Test
    fun variantsCarryTheirOwnUnitAndTag() {
        listOf(
            Triple(DataType.Type.TORQUE, "Nm", ""),
            Triple(DataType.Type.SMOOTHED_3S_AVERAGE_TORQUE, "Nm 3s", "3s"),
            Triple(DataType.Type.AVERAGE_TORQUE, "Nm avg", "avg"),
            Triple(DataType.Type.MAX_TORQUE, "Nm max", "max"),
            Triple(DataType.Type.TORQUE_LAP, "Nm lap", "lap"),
        ).forEach { (id, unit, tag) ->
            val c = torque(id, 30.0)
            assertEquals(unit, c.units)
            assertEquals(HudIcon.TORQUE, c.icon)
            assertEquals(tag, FieldFormat.iconTagFor(id))
        }
    }

    @Test
    fun idleStreamRendersDash() {
        val c = FieldFormat.format(DataType.Type.TORQUE, StreamState.Idle, imperial = false, zones = zones)
        assertEquals("--", c.value)
        assertNotNull(FieldFormat.specFor(DataType.Type.TORQUE))
    }
}
