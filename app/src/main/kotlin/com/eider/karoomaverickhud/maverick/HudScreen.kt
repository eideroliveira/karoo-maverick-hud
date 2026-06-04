@file:OptIn(ExperimentalUnsignedTypes::class) // EvsKit's UIKit (Screen/UIElement/EvsColor.rgba) is built on UInt

package com.eider.karoomaverickhud.maverick

import UIKit.app.Screen
import UIKit.app.data.Align
import UIKit.app.data.EvsColor
import UIKit.app.data.TouchDirection
import UIKit.app.resources.Font
import UIKit.widgets.Rect
import UIKit.widgets.Text
import UIKit.widgets.UIElement
import com.eider.karoomaverickhud.extension.HudCell
import com.eider.karoomaverickhud.extension.HudColor
import com.eider.karoomaverickhud.extension.HudSnapshot
import com.eider.karoomaverickhud.extension.MAX_CELLS

/**
 * HUD on a 420×150 Maverick screen. Data lives in the two edge columns (the first and last of
 * a notional four) so the centre stays a clear field of vision, with two or three rows each.
 *
 * Each cell is two stacked lines: the value on top, then its unit/label below — both tinted by
 * the training-zone colour. Everything is positioned by measured text width, since the SDK's
 * [Align] only affects intra-element justification, not the anchor point. Forward/back
 * temple-pad swipes flip pages.
 */
class HudScreen : Screen(420f, 150f) {

    private val screenW = 420f
    private val leftX = 8f
    private val rightX = screenW - 8f

    // Centre control-window geometry (a bordered box with time/signal/battery + a brightness slider).
    private val boxX = 50f
    private val boxY = 31f
    private val boxW = 320f
    private val boxH = 88f
    private val ctrlLeft = 72f
    private val ctrlRight = 348f
    private val ctrlTopY = 44f
    private val sliderY = 94f // baseline for the brightness status line

    /** Per-cell vertical placement and which edge it hugs; X is computed per-frame from text width. */
    private data class Slot(val isRight: Boolean, val valueY: Float, val labelY: Float)

    /**
     * Slots ordered by field count. The first four fields always take the four corners; a 5th
     * and 6th drop into the centre row (left/right).
     */
    private fun slotsFor(count: Int): Array<Slot> {
        fun row(isRight: Boolean, valueY: Float, gap: Float) =
            Slot(isRight, valueY = valueY, labelY = valueY + gap)
        return if (count <= 4) {
            val gap = 22f
            arrayOf(
                row(false, 18f, gap), row(true, 18f, gap),    // TL, TR
                row(false, 102f, gap), row(true, 102f, gap),  // BL, BR
            )
        } else {
            val gap = 22f
            arrayOf(
                row(false, 4f, gap), row(true, 4f, gap),      // TL, TR
                row(false, 112f, gap), row(true, 112f, gap),  // BL, BR
                row(false, 58f, gap), row(true, 58f, gap),    // ML, MR (centre)
            )
        }
    }

    // Element pool is sized for the max (six); only the active fields are positioned and shown.
    private val cellCount = MAX_CELLS
    private var layoutCount = 4
    private var slots = slotsFor(layoutCount)

    private val values = Array(cellCount) { Text() }
    private val units = Array(cellCount) { Text() }
    private val statusText = Text() // centred "waiting for ride" when idle
    private val pauseDot = Text()
    private val clockText = Text() // time of day (shown inside the control window)
    private val batteryText = Text() // glasses battery % (shown inside the control window)

    // Centre control-window widgets.
    private val ctrlBox = Rect()
    private val sigBars = Array(3) { Rect() }
    private val brightText = Text() // "AUTO BRIGHTNESS" or "BRIGHTNESS  60%"

    @Volatile private var snapshot: HudSnapshot = HudSnapshot.empty

    /** Set by [MaverickBridge] to handle temple-pad touches (page switching). */
    @Volatile var onTouchPad: ((TouchDirection) -> Unit)? = null

    // Centre control-window state, driven by [MaverickBridge] via [setControl].
    @Volatile private var controlOpen = false
    @Volatile private var ctrlBrightness = 50
    @Volatile private var ctrlAuto = false
    @Volatile private var ctrlSignal = 0

    fun apply(next: HudSnapshot) {
        snapshot = next
    }

    /** Push control-window state (open + brightness 0..100 + auto + signal bars 0..3). */
    fun setControl(open: Boolean, brightness: Int, auto: Boolean, signal: Int) {
        controlOpen = open
        ctrlBrightness = brightness
        ctrlAuto = auto
        ctrlSignal = signal
    }

    private fun UIElement.addTo(screen: Screen): UIElement = also { screen.add(it) }

    override fun onCreate() {
        // EvsKit's stock font set is Small/Medium/Large only — we sit at Small for both lines
        // (per the brief: "value at unit's size, unit smaller"). Smaller than Small isn't
        // available without a custom font upload, which we don't pay for here.
        for (i in 0 until cellCount) {
            values[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.left)
                .setForegroundColor(EvsColor.White.rgba)
                .addTo(this)
            units[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.left)
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

        // Time and battery live inside the centre control window, hidden until it opens.
        clockText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.left)
            .setXY(ctrlLeft, ctrlTopY)
            .setForegroundColor(EvsColor.White.rgba)
            .setVisibility(false)
            .addTo(this)

        batteryText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.right)
            .setXY(ctrlRight, ctrlTopY)
            .setForegroundColor(EvsColor.White.rgba)
            .setVisibility(false)
            .addTo(this)

        statusText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(getWidth() / 2f, getHeight() / 2f)
            .setForegroundColor(EvsColor.Green.rgba)
            .addTo(this)

        setupControlWindow()
    }

    /** Build the centre control window's box, signal bars and brightness slider (all hidden). */
    private fun setupControlWindow() {
        ctrlBox.setRoundedCorners(12f)
        ctrlBox.clearFillColor()
        ctrlBox.setXY(boxX, boxY)
        ctrlBox.setWidthHeight(boxW, boxH)
        ctrlBox.setForegroundColor(EvsColor.White.rgba)
        ctrlBox.setPenThickness(2)
        ctrlBox.setVisibility(false)
        add(ctrlBox)

        for (i in sigBars.indices) {
            val h = 6f + i * 5f
            sigBars[i].setFillColor(EvsColor.White.rgba)
            sigBars[i].setXY(184f + i * 9f, (ctrlTopY + 14f) - h)
            sigBars[i].setWidthHeight(6f, h)
            sigBars[i].setVisibility(false)
            add(sigBars[i])
        }

        brightText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(boxX + boxW / 2f, sliderY - 6f)
            .setForegroundColor(EvsColor.White.rgba)
            .setVisibility(false)
            .addTo(this)
    }

    /** Re-flow slot positions when the active field count changes. */
    private fun applyCount(count: Int) {
        layoutCount = count
        slots = slotsFor(count)
    }

    override fun onUpdateUI(timestampMs: Long) {
        val snap = snapshot
        renderControlWindow(snap)

        // The glasses are see-through, so the control window can't occlude — clear the HUD
        // behind it instead, leaving only the window legible.
        if (controlOpen) {
            for (i in 0 until cellCount) blankCell(i)
            statusText.setText("")
            pauseDot.setText("")
            return
        }

        // Connected to the Karoo but no ride yet — show a holding message, blank the grid.
        if (!snap.recording && !snap.paused) {
            for (i in 0 until cellCount) blankCell(i)
            statusText.setText("WAITING FOR RIDE")
            pauseDot.setText("KAROO CONNECTED")
            return
        }

        statusText.setText("")
        val page: List<HudCell> = snap.pages.getOrNull(snap.pageIndex).orEmpty()
        val count = page.size.coerceIn(1, cellCount)
        if (count != layoutCount) applyCount(count)
        for (i in 0 until cellCount) {
            if (i < count) layoutCell(i, slots[i], page.getOrNull(i)) else blankCell(i)
        }
        pauseDot.setText(if (snap.paused) "‖ PAUSED" else "")
    }

    /** Render one cell: value on top, unit/label below, both tinted by the zone colour. */
    private fun layoutCell(i: Int, slot: Slot, cell: HudCell?) {
        val color = colorRgba(cell?.color ?: HudColor.WHITE)
        val align = if (slot.isRight) Align.right else Align.left
        val anchorX = if (slot.isRight) rightX else leftX

        values[i].setText(cell?.value ?: "").setForegroundColor(color)
        values[i].setTextAlign(align).setXY(anchorX, slot.valueY)

        units[i].setText(cell?.units.orEmpty()).setForegroundColor(color)
        units[i].setTextAlign(align).setXY(anchorX, slot.labelY)
    }

    /** Draw or hide the centre control window from the latest [setControl] state + snapshot. */
    private fun renderControlWindow(snap: HudSnapshot) {
        if (!controlOpen) {
            setControlVisible(false)
            return
        }
        setControlVisible(true)
        clockText.setText(snap.clock)
        batteryText.setText(snap.battery?.let { "$it%" } ?: "")

        val bars = ctrlSignal.coerceIn(0, 3)
        for (i in sigBars.indices) sigBars[i].setVisibility(i < bars)

        if (ctrlAuto) {
            brightText.setText("AUTO BRIGHTNESS").setForegroundColor(EvsColor.Yellow.rgba)
        } else {
            brightText.setText("BRIGHTNESS  ${ctrlBrightness.coerceIn(0, 100)}%").setForegroundColor(EvsColor.White.rgba)
        }
    }

    private fun setControlVisible(visible: Boolean) {
        ctrlBox.setVisibility(visible)
        clockText.setVisibility(visible)
        batteryText.setVisibility(visible)
        brightText.setVisibility(visible)
        if (!visible) sigBars.forEach { it.setVisibility(false) }
    }

    private fun blankCell(i: Int) {
        values[i].setText("")
        units[i].setText("")
    }

    private fun colorRgba(color: HudColor): Int = when (color) {
        HudColor.WHITE -> EvsColor.White.rgba
        HudColor.GREEN -> EvsColor.Green.rgba
        HudColor.YELLOW -> EvsColor.Yellow.rgba
        HudColor.ORANGE -> EvsColor.Orange.rgba
        HudColor.RED -> EvsColor.Red.rgba
        HudColor.PURPLE -> EvsColor.Purple.rgba
        HudColor.CYAN -> EvsColor.Cyan.rgba
    }

    override fun onTouch(touch: TouchDirection) {
        onTouchPad?.invoke(touch)
    }
}
