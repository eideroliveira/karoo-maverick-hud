package com.eider.karoomaverickhud.extension

import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the icon differentiator tags: the original field that owns an icon shows it alone (empty
 * tag), while every variant that reuses the icon gets a short tag so they can be told apart on the
 * glasses. Mirrors what HudScreen draws beside the icon and the settings preview shows.
 */
class IconTagTest {

    @Test
    fun originalFieldsOwningAnIconHaveNoTag() {
        // The live primaries each own their icon outright → icon-only.
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.POWER))
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.CADENCE))
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.HEART_RATE))
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.SPEED))
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.DISTANCE))
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.PEDAL_POWER_BALANCE))
        // Ride time is the original time field.
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.ELAPSED_TIME))
    }

    @Test
    fun variantsSharingAnIconGetAShortTag() {
        // avg/max variants reuse the suffix.
        assertEquals("avg", FieldFormat.iconTagFor(DataType.Type.AVERAGE_POWER))
        assertEquals("max", FieldFormat.iconTagFor(DataType.Type.MAX_HR))
        // NP reuses the power icon → "NP".
        assertEquals("NP", FieldFormat.iconTagFor(DataType.Type.NORMALIZED_POWER))
        // The other time fields are told apart by what they time.
        assertEquals("lap", FieldFormat.iconTagFor(DataType.Type.ELAPSED_TIME_LAP))
        assertEquals("last lap", FieldFormat.iconTagFor(DataType.Type.ELAPSED_TIME_LAST_LAP))
        // The climb's two distances share the distance icon (the user's "to top" example).
        assertEquals("to top", FieldFormat.iconTagFor(DataType.Type.DISTANCE_TO_TOP))
    }

    @Test
    fun fieldsWithoutAnIconHaveNoTag() {
        // IF/VI/gear have no glasses icon, so no tag.
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.INTENSITY_FACTOR))
        assertEquals("", FieldFormat.iconTagFor(DataType.Type.SHIFTING_GEARS))
        // Unknown/extension fields fall through to empty.
        assertEquals("", FieldFormat.iconTagFor("TYPE_SOME_EXTENSION_FIELD_ID"))
    }

    @Test
    fun formattedCellCarriesTheTag() {
        // The tag rides along on the produced HudCell (what the renderer reads), not just the lookup.
        val zones = ZoneConfig(ftp = 200, maxHr = 185, idealCadence = 90)
        val avg = FieldFormat.format(DataType.Type.AVERAGE_POWER, io.hammerhead.karooext.models.StreamState.Idle, imperial = false, zones = zones)
        assertEquals("avg", avg.iconLabel)
        val power = FieldFormat.format(DataType.Type.POWER, io.hammerhead.karooext.models.StreamState.Idle, imperial = false, zones = zones)
        assertEquals("", power.iconLabel)
    }
}
