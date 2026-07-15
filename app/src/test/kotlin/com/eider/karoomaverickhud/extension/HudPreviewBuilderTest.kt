package com.eider.karoomaverickhud.extension

import com.eider.karoomaverickhud.settings.HudConfig
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the settings live-preview mirrors the runtime workout overlay: a page carrying a
 * workout-target field previews its live POWER/CADENCE tiles as the "current/target" composite,
 * while a plain page (no target field) previews them as ordinary single values.
 */
class HudPreviewBuilderTest {

    private val base = HudConfig.DEFAULT // ftp 200, idealCadence 90 → demo target 180 W / 90 rpm

    // The mirror now returns one page per scene (numbered pages come first in the scene list), so the
    // numbered page [page] is selected via sceneIndex and read from the snapshot's single page.
    private fun previewValue(pages: List<List<String>>, page: Int, cell: Int): String =
        HudPreviewBuilder.snapshot(base.copy(pages = pages), seed = 1, sceneIndex = page)
            .pages[0][cell].value

    @Test
    fun powerTileShowsCompositeWhenTargetFieldOnPage() {
        // POWER alongside its target field → the POWER tile previews as "<watts>/180".
        val value = previewValue(listOf(listOf(DataType.Type.POWER, DataType.Type.WORKOUT_POWER_TARGET)), 0, 0)
        assertTrue("expected composite value, got '$value'", value.endsWith("/180"))
    }

    @Test
    fun cadenceTileShowsCompositeWhenTargetFieldOnPage() {
        val value = previewValue(listOf(listOf(DataType.Type.CADENCE, DataType.Type.WORKOUT_CADENCE_TARGET)), 0, 0)
        assertTrue("expected composite value, got '$value'", value.endsWith("/90"))
    }

    @Test
    fun powerTileStaysPlainWithoutTargetField() {
        // No target field on the page → plain single value, no "/target" suffix.
        val value = previewValue(listOf(listOf(DataType.Type.POWER, DataType.Type.SPEED)), 0, 0)
        assertFalse("expected plain value, got '$value'", value.contains("/"))
    }

    @Test
    fun onlyMetricWithTargetFieldGoesComposite() {
        // POWER + CADENCE share a page that carries only the power target field → POWER previews as
        // composite while CADENCE (no target for it) stays the regular cadence field.
        val pages = listOf(listOf(DataType.Type.POWER, DataType.Type.CADENCE, DataType.Type.WORKOUT_POWER_TARGET))
        assertTrue("power should be composite", previewValue(pages, 0, 0).endsWith("/180"))
        assertFalse("cadence should be plain", previewValue(pages, 0, 1).contains("/"))
    }

    @Test
    fun targetOnlyAffectsItsOwnPage() {
        // Page 0 carries the target; page 1 has a bare POWER tile that must stay plain.
        val pages = listOf(
            listOf(DataType.Type.POWER, DataType.Type.WORKOUT_POWER_TARGET),
            listOf(DataType.Type.POWER, DataType.Type.SPEED),
        )
        assertTrue(previewValue(pages, 0, 0).endsWith("/180"))
        assertFalse(previewValue(pages, 1, 0).contains("/"))
    }
}
