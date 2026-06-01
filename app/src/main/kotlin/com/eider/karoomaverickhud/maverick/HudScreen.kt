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
import com.eider.karoomaverickhud.extension.MAX_CELLS
import com.eider.karoomaverickhud.extension.MAX_ROWS
import com.eider.karoomaverickhud.extension.MIN_ROWS

/**
 * HUD on a 420×150 Maverick screen. Data lives in the two edge columns (the first and last of
 * a notional four) so the centre stays a clear field of vision, with two or three rows each —
 * up to six cells. Each cell is a small icon + a zone-coloured value + its unit; no labels.
 * Temple-pad touches go to [onTouch] for MANUAL page switching.
 */
class HudScreen : Screen(420f, 150f) {

    private data class Pos(val iconX: Float, val valueX: Float, val unitX: Float, val y: Float)

    /** Cell positions for a row count: all left-column rows (top→bottom) then all right-column. */
    private fun positionsFor(rows: Int): Array<Pos> {
        // Centre x≈108..312 stays clear; two rows sit balanced, three rows fill the height.
        val ys = if (rows <= MIN_ROWS) floatArrayOf(48f, 102f) else floatArrayOf(30f, 74f, 118f)
        val left = ys.map { Pos(iconX = 4f, valueX = 30f, unitX = 70f, y = it) }
        val right = ys.map { Pos(iconX = 314f, valueX = 340f, unitX = 380f, y = it) }
        return (left + right).toTypedArray()
    }

    // Element pool is sized for the max (six); only the active 2×rows are positioned and shown.
    private val cellCount = MAX_CELLS
    private var positions = positionsFor(MAX_ROWS)
    private var layoutRows = MAX_ROWS

    private val icons = Array(cellCount) { Image() }
    private val values = Array(cellCount) { Text() }
    private val units = Array(cellCount) { Text() }
    private val statusText = Text() // centred "waiting for ride" when idle
    private val pauseDot = Text()
    private val clockText = Text() // time of day, top-left
    private val batteryText = Text() // Karoo battery %, top-right

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
                .setForegroundColor(EvsColor.White.rgba)
                .addTo(this)
            units[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.left)
                .setXY(p.unitX, p.y)
                .setForegroundColor(EvsColor.White.rgba)
                .addTo(this)
        }

        pauseDot
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(getWidth() / 2f, 4f)
            .setForegroundColor(EvsColor.Green.rgba)
            .addTo(this)

        clockText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.left)
            .setXY(4f, 4f)
            .setForegroundColor(EvsColor.White.rgba)
            .addTo(this)

        batteryText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.right)
            .setXY(getWidth() - 4f, 4f)
            .setForegroundColor(EvsColor.White.rgba)
            .addTo(this)

        statusText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(getWidth() / 2f, getHeight() / 2f)
            .setForegroundColor(EvsColor.Green.rgba)
            .addTo(this)
    }

    /** Reposition the element pool when the user's row count changes (2 ↔ 3 rows). */
    private fun applyRows(rows: Int) {
        layoutRows = rows
        positions = positionsFor(rows)
        for (i in positions.indices) {
            val p = positions[i]
            icons[i].setXY(p.iconX, p.y - 6f)
            values[i].setXY(p.valueX, p.y)
            units[i].setXY(p.unitX, p.y)
        }
    }

    override fun onUpdateUI(timestampMs: Long) {
        val snap = snapshot
        val rows = snap.rows.coerceIn(MIN_ROWS, MAX_ROWS)
        if (rows != layoutRows) applyRows(rows)
        val activeCells = positions.size // 2 × rows

        // Corners are always-on: time top-left, Karoo battery top-right.
        clockText.setText(snap.clock)
        batteryText.setText(snap.battery?.let { "$it%" } ?: "")

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
            if (i >= activeCells) {
                // Rows shrank — blank the now-unused slots so stale values don't linger.
                values[i].setText("")
                units[i].setText("")
                setIcon(i, null)
                continue
            }
            val cell = page.getOrNull(i)
            values[i].setText(cell?.value ?: "")
            values[i].setForegroundColor(colorRgba(cell?.color ?: HudColor.WHITE))
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
        HudColor.WHITE -> EvsColor.White.rgba
        HudColor.GREEN -> EvsColor.Green.rgba
        HudColor.ORANGE -> EvsColor.Orange.rgba
        HudColor.RED -> EvsColor.Red.rgba
        HudColor.PURPLE -> EvsColor.Purple.rgba
    }

    override fun onTouch(touch: TouchDirection) {
        onTouchPad?.invoke(touch)
    }
}
