@file:OptIn(ExperimentalUnsignedTypes::class) // EvsKit's UIKit (Screen/UIElement/EvsColor.rgba) is built on UInt

package com.eider.karoomaverickhud.maverick

import UIKit.app.Screen
import UIKit.app.data.Align
import UIKit.app.data.EvsColor
import UIKit.app.data.TouchDirection
import UIKit.app.resources.Font
import UIKit.app.resources.ImgSrc
import UIKit.widgets.Image
import UIKit.widgets.Text
import UIKit.widgets.UIElement
import com.eider.karoomaverickhud.extension.HudCell
import com.eider.karoomaverickhud.extension.HudColor
import com.eider.karoomaverickhud.extension.HudIcon
import com.eider.karoomaverickhud.extension.HudSnapshot

/**
 * HUD on a 420×150 Maverick screen. Data lives in the two edge columns (far left / far
 * right) so the centre stays a clear field of vision, with up to three rows each — six
 * cells. Each cell is a small green icon + a zone-coloured value + its unit; no labels.
 * Temple-pad touches go to [onTouch] for MANUAL page switching.
 */
class HudScreen : Screen(420f, 150f) {

    private data class Pos(val iconX: Float, val valueX: Float, val unitX: Float, val y: Float)

    // Left column (rows 0-2) then right column (rows 3-5). Centre x≈108..312 stays clear.
    private val positions = arrayOf(
        Pos(iconX = 4f, valueX = 30f, unitX = 70f, y = 30f),
        Pos(iconX = 4f, valueX = 30f, unitX = 70f, y = 74f),
        Pos(iconX = 4f, valueX = 30f, unitX = 70f, y = 118f),
        Pos(iconX = 314f, valueX = 340f, unitX = 380f, y = 30f),
        Pos(iconX = 314f, valueX = 340f, unitX = 380f, y = 74f),
        Pos(iconX = 314f, valueX = 340f, unitX = 380f, y = 118f),
    )
    private val cellCount = positions.size

    private val icons = Array(cellCount) { Image() }
    private val values = Array(cellCount) { Text() }
    private val units = Array(cellCount) { Text() }
    private val statusText = Text() // centred "waiting for ride" when idle
    private val pauseDot = Text()

    // One ImgSrc per glyph (each gets its own glasses image slot).
    private val imgPower = ImgSrc("ic_power.png", ImgSrc.Slot.s1)
    private val imgSpeed = ImgSrc("ic_speed.png", ImgSrc.Slot.s2)
    private val imgHeart = ImgSrc("ic_hr.png", ImgSrc.Slot.s3)
    private val imgCadence = ImgSrc("ic_cadence.png", ImgSrc.Slot.s4)

    private val currentIcon = arrayOfNulls<HudIcon>(cellCount) // avoid redundant setResource

    @Volatile private var snapshot: HudSnapshot = HudSnapshot.empty

    /** Set by [MaverickBridge] to handle temple-pad touches (MANUAL page switching). */
    @Volatile var onTouchPad: ((TouchDirection) -> Unit)? = null

    fun apply(next: HudSnapshot) {
        snapshot = next
    }

    private fun UIElement.addTo(screen: Screen): UIElement = also { screen.add(it) }

    override fun onCreate() {
        for (i in 0 until cellCount) {
            val p = positions[i]
            icons[i]
                .setXY(p.iconX, p.y - 6f)
                .setVisibility(false)
                .addTo(this)
            values[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.left)
                .setXY(p.valueX, p.y)
                .setForegroundColor(EvsColor.Green.rgba)
                .addTo(this)
            units[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.left)
                .setXY(p.unitX, p.y)
                .setForegroundColor(EvsColor.Green.rgba)
                .addTo(this)
        }

        pauseDot
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(getWidth() / 2f, 4f)
            .setForegroundColor(EvsColor.Green.rgba)
            .addTo(this)

        statusText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(getWidth() / 2f, getHeight() / 2f)
            .setForegroundColor(EvsColor.Green.rgba)
            .addTo(this)
    }

    override fun onUpdateUI(timestampMs: Long) {
        val snap = snapshot

        // Connected to the Karoo but no ride yet — show a holding message, blank the grid.
        if (!snap.recording && !snap.paused) {
            for (i in 0 until cellCount) {
                values[i].setText("")
                units[i].setText("")
                setIcon(i, null)
            }
            statusText.setText("WAITING FOR RIDE")
            pauseDot.setText("KAROO CONNECTED")
            return
        }

        statusText.setText("")
        val page: List<HudCell> = snap.pages.getOrNull(snap.pageIndex).orEmpty()
        for (i in 0 until cellCount) {
            val cell = page.getOrNull(i)
            values[i].setText(cell?.value ?: "")
            values[i].setForegroundColor(colorRgba(cell?.color ?: HudColor.GREEN))
            units[i].setText(cell?.units.orEmpty())
            setIcon(i, cell?.icon)
        }
        pauseDot.setText(if (snap.paused) "‖ PAUSED" else "")
    }

    private fun setIcon(i: Int, icon: HudIcon?) {
        if (currentIcon[i] == icon) return
        currentIcon[i] = icon
        if (icon == null) {
            icons[i].setVisibility(false)
        } else {
            icons[i].setResource(imgSrcFor(icon))
            icons[i].setVisibility(true)
        }
    }

    private fun imgSrcFor(icon: HudIcon): ImgSrc = when (icon) {
        HudIcon.POWER -> imgPower
        HudIcon.SPEED -> imgSpeed
        HudIcon.HEART -> imgHeart
        HudIcon.CADENCE -> imgCadence
    }

    private fun colorRgba(color: HudColor): Int = when (color) {
        HudColor.GREEN -> EvsColor.Green.rgba
        HudColor.ORANGE -> EvsColor.Orange.rgba
        HudColor.RED -> EvsColor.Red.rgba
    }

    override fun onTouch(touch: TouchDirection) {
        onTouchPad?.invoke(touch)
    }
}
