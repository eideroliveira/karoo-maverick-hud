package com.eider.karoomaverickhud.settings

import com.eider.karoomaverickhud.extension.HudPreviewBuilder
import com.eider.karoomaverickhud.extension.HudSnapshot
import com.eider.karoomaverickhud.settings.ui.PreviewOverlay
import com.eider.karoomaverickhud.settings.ui.previewScenes
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hub's on-screen lens ([previewScenes]) and the on-glasses mirror ([HudPreviewBuilder.snapshot])
 * cycle through the same scene index, so they must list the same scenes in the same order — or the
 * phone would show one layout while the glasses show another.
 */
class PreviewScenesParityTest {

    private fun overlayOf(s: HudSnapshot): PreviewOverlay = when {
        s.climb != null -> PreviewOverlay.CLIMB
        s.radar != null -> PreviewOverlay.RADAR
        s.trajectory != null -> PreviewOverlay.TRAJECTORY
        else -> PreviewOverlay.NONE
    }

    private fun assertParity(cfg: HudConfig) {
        val scenes = previewScenes(cfg)
        scenes.forEachIndexed { i, scene ->
            val snap = HudPreviewBuilder.snapshot(cfg, seed = 1, sceneIndex = i)
            assertEquals("scene $i (${scene.label})", scene.overlay, overlayOf(snap))
        }
        // One past the end wraps back to the first scene — i.e. the mirror has no extra scenes.
        val wrapped = HudPreviewBuilder.snapshot(cfg, seed = 1, sceneIndex = scenes.size)
        assertEquals(scenes.first().overlay, overlayOf(wrapped))
    }

    private val twoPages = HudConfig.DEFAULT.copy(
        pages = listOf(
            listOf(DataType.Type.POWER, DataType.Type.SPEED),
            listOf(DataType.Type.HEART_RATE, DataType.Type.CADENCE),
        ),
    )

    @Test
    fun climbIsAnOverlayOverTheFirstPage() {
        val scenes = previewScenes(twoPages)
        val climb = scenes.single { it.overlay == PreviewOverlay.CLIMB }
        assertEquals(twoPages.pages.first(), climb.fields)
    }

    @Test
    fun matchesMirrorWithAllOverlays() =
        assertParity(twoPages.copy(radarEnabled = true, trajectoryEnabled = true))

    @Test
    fun matchesMirrorWithRouteOverlaysOff() =
        assertParity(twoPages.copy(radarEnabled = false, trajectoryEnabled = false))
}
