@file:OptIn(ExperimentalUnsignedTypes::class) // EvsKit's UIKit (Screen/UIElement/EvsColor.rgba) is built on UInt

package com.eider.karoomaverickhud.maverick

import UIKit.app.Screen
import UIKit.app.data.Align
import UIKit.app.data.EvsColor
import UIKit.app.data.EvsColors
import UIKit.app.data.TouchDirection
import UIKit.app.resources.Font
import UIKit.app.resources.ImgSrc
import UIKit.widgets.Image
import UIKit.widgets.Rect
import UIKit.widgets.Text
import UIKit.widgets.UIElement
import com.eider.karoomaverickhud.extension.HudCell
import com.eider.karoomaverickhud.extension.HudColor
import com.eider.karoomaverickhud.extension.HudIcon
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
    private val iconW = 26f
    private val iconGap = 4f

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
    private data class Slot(val isRight: Boolean, val valueY: Float, val labelY: Float, val iconY: Float)

    /**
     * Slots ordered by field count. The first four fields always take the four corners; a 5th
     * and 6th drop into the centre row (left/right).
     */
    private fun slotsFor(count: Int): Array<Slot> {
        fun row(isRight: Boolean, valueY: Float, gap: Float) =
            Slot(isRight, valueY = valueY, labelY = valueY + gap, iconY = valueY + gap - 4f)
        return if (count <= 4) {
            // Big value face (~33px) stacked over a small unit (~18px): two roomy rows. The gap
            // tucks the unit just under the digits; rows sit clear of the 150px top/bottom edges.
            val gap = 30f
            arrayOf(
                row(false, 14f, gap), row(true, 14f, gap),    // TL, TR
                row(false, 88f, gap), row(true, 88f, gap),    // BL, BR
            )
        } else {
            // Denser value face (~25px) over the same small unit, so three value+unit rows
            // (3 × ~43px) still fit the 150px lens.
            val gap = 25f
            arrayOf(
                row(false, 6f, gap), row(true, 6f, gap),      // TL, TR
                row(false, 102f, gap), row(true, 102f, gap),  // BL, BR
                row(false, 54f, gap), row(true, 54f, gap),    // ML, MR (centre)
            )
        }
    }

    // Element pool is sized for the max (six); only the active fields are positioned and shown.
    private val cellCount = MAX_CELLS
    private var layoutCount = 4
    private var slots = slotsFor(layoutCount)

    private val values = Array(cellCount) { Text() }
    private val units = Array(cellCount) { Text() }
    private val icons = Array(cellCount) { Image() }

    // Custom Roboto Condensed (SemiBold) HUD faces, generated with font2sif.py and bundled under
    // assets/fonts. Each occupies its own glasses font slot. Values use a larger face than their
    // unit labels; the value face also steps down on the denser 5–6 field (three-row) pages so
    // value + unit still clear the 150px lens. The HxW token in each filename is parsed by the SDK
    // to set the glyph dimensions, so the generated names are kept verbatim.
    private val valueFontBig = Font("fonts/RobotoCondensed-SemiBold.ttf.33x25.2bpp.sifz", Font.Slot.s1)
    private val valueFontDense = Font("fonts/RobotoCondensed-SemiBold.ttf.25x18.2bpp.sifz", Font.Slot.s2)
    private val unitFont = Font("fonts/RobotoCondensed-SemiBold.ttf.18x12.2bpp.sifz", Font.Slot.s3)

    /** Big value face on the roomy ≤4-field (two-row) pages; the denser face on 5–6 field pages. */
    private fun valueFontFor(count: Int): Font = if (count <= 4) valueFontBig else valueFontDense
    private val statusText = Text() // centred "waiting for ride" when idle
    private val pauseDot = Text()
    private val clockText = Text() // time of day (shown inside the control window)
    private val batteryText = Text() // glasses battery % (shown inside the control window)

    // One ImgSrc per glyph (each gets its own glasses image slot). Drawn only when the rider
    // enables HUD icons (HudSnapshot.showIcons).
    private val imgPower = ImgSrc("ic_power.png", ImgSrc.Slot.s1)
    private val imgSpeed = ImgSrc("ic_speed.png", ImgSrc.Slot.s2)
    private val imgHeart = ImgSrc("ic_hr.png", ImgSrc.Slot.s3)
    private val imgCadence = ImgSrc("ic_cadence.png", ImgSrc.Slot.s4)
    private val imgTime = ImgSrc("ic_time.png", ImgSrc.Slot.s5)
    private val imgDistance = ImgSrc("ic_distance.png", ImgSrc.Slot.s6)
    private val imgBalance = ImgSrc("ic_balance.png", ImgSrc.Slot.s7)
    private val currentIcon = arrayOfNulls<HudIcon>(cellCount) // avoid redundant setResource

    // Centre control-window widgets.
    private val ctrlBox = Rect()
    private val sigBars = Array(3) { Rect() }
    private val brightText = Text() // "AUTO BRIGHTNESS" or "BRIGHTNESS  60%"

    @Volatile private var snapshot: HudSnapshot = HudSnapshot.empty

    // Last frame actually rendered, so onUpdateUI can no-op when nothing changed. The bridge only
    // pushes a *new* snapshot object when content changes (it dedupes identical frames), so a
    // reference check suffices for the data; control-window state is folded in via [controlSignature].
    @Volatile private var lastRenderedSnapshot: HudSnapshot? = null
    private var lastControlSignature = Int.MIN_VALUE

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
        // Values and units use custom Roboto Condensed faces (see the field declarations): the
        // value face is larger than the unit, and steps down on denser pages via [valueFontFor].
        // The remaining chrome (status line, control window) stays on the stock Small font.
        for (i in 0 until cellCount) {
            icons[i]
                .setVisibility(false)
                .addTo(this)
            values[i]
                .setText("")
                .setResource(valueFontFor(layoutCount))
                .setTextAlign(Align.left)
                .setForegroundColor(EvsColor.White.rgba)
                .addTo(this)
            units[i]
                .setText("")
                .setResource(unitFont)
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
        // ≤4-field pages get the big value face, 5–6-field pages the denser one.
        val vf = valueFontFor(count)
        for (i in 0 until cellCount) values[i].setResource(vf)
    }

    override fun onUpdateUI(timestampMs: Long) {
        val snap = snapshot
        // The glasses call this every frame. When neither the pushed snapshot nor the control
        // window changed since the last render, re-applying identical text/positions just re-dirties
        // elements and re-flushes them over BLE — so bail out and let the prior frame stand.
        val controlSig = controlSignature()
        if (snap === lastRenderedSnapshot && controlSig == lastControlSignature) return
        lastRenderedSnapshot = snap
        lastControlSignature = controlSig

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
            if (i < count) layoutCell(i, slots[i], page.getOrNull(i), snap.showIcons) else blankCell(i)
        }
        pauseDot.setText(if (snap.paused) "‖ PAUSED" else "")
    }

    /** Render one cell: value on top, unit/label (+ optional icon) below, tinted by the zone colour. */
    private fun layoutCell(i: Int, slot: Slot, cell: HudCell?, showIcons: Boolean) {
        val color = colorRgba(cell?.color ?: HudColor.WHITE)
        val align = if (slot.isRight) Align.right else Align.left
        val anchorX = if (slot.isRight) rightX else leftX

        values[i].setText(cell?.value ?: "").setForegroundColor(color)
        values[i].setTextAlign(align).setXY(anchorX, slot.valueY)

        val labelText = cell?.units.orEmpty()
        units[i].setText(labelText).setForegroundColor(color)
        units[i].setTextAlign(align).setXY(anchorX, slot.labelY)

        val icon = cell?.icon
        if (!showIcons || icon == null) {
            icons[i].setVisibility(false)
            currentIcon[i] = null
        } else {
            if (currentIcon[i] != icon) {
                icons[i].setResource(imgSrcFor(icon))
                currentIcon[i] = icon
            }
            // Icon sits on the inner side of the label: after it on the left, before it on the right.
            val labelW = if (labelText.isEmpty()) 0f else units[i].getMeasuredContentWidth()
            val iconX = if (slot.isRight) (rightX - labelW) - iconGap - iconW else leftX + labelW + iconGap
            icons[i].setForegroundColor(color).setXY(iconX, slot.iconY).setVisibility(true)
        }
    }

    private fun imgSrcFor(icon: HudIcon): ImgSrc = when (icon) {
        HudIcon.POWER -> imgPower
        HudIcon.SPEED -> imgSpeed
        HudIcon.HEART -> imgHeart
        HudIcon.CADENCE -> imgCadence
        HudIcon.TIME -> imgTime
        HudIcon.DISTANCE -> imgDistance
        HudIcon.BALANCE -> imgBalance
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

    /** Folds the control-window state into one int so onUpdateUI can detect a change cheaply. */
    private fun controlSignature(): Int {
        var h = if (controlOpen) 1 else 0
        h = h * 131 + ctrlBrightness
        h = h * 131 + (if (ctrlAuto) 1 else 0)
        h = h * 131 + ctrlSignal
        return h
    }

    private fun blankCell(i: Int) {
        values[i].setText("")
        units[i].setText("")
        icons[i].setVisibility(false)
        currentIcon[i] = null
    }

    private fun colorRgba(color: HudColor): Int = when (color) {
        HudColor.WHITE -> EvsColor.White.rgba
        HudColor.GREEN -> EvsColor.Green.rgba
        HudColor.YELLOW -> EvsColor.Yellow.rgba
        HudColor.ORANGE -> EvsColor.Orange.rgba
        HudColor.RED -> LIGHT_RED_RGBA           // EvsColor.Red renders too dim on LCOS in daylight
        HudColor.PURPLE -> PINK_RGBA             // EvsColor.Purple renders too dim on LCOS in daylight
        HudColor.CYAN -> EvsColor.Cyan.rgba
    }

    companion object {
        private val LIGHT_RED_RGBA = EvsColors.fromRgb(0xFF, 0x7B, 0x7B)
        private val PINK_RGBA = EvsColors.fromRgb(0xFF, 0x60, 0xB0)
    }

    override fun onTouch(touch: TouchDirection) {
        onTouchPad?.invoke(touch)
    }
}
