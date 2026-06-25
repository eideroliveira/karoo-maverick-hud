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
import UIKit.widgets.Polygon
import UIKit.widgets.Polyline
import UIKit.widgets.Rect
import UIKit.widgets.Text
import UIKit.widgets.UIElement
import com.eider.karoomaverickhud.extension.BatteryWarn
import com.eider.karoomaverickhud.extension.HudCell
import com.eider.karoomaverickhud.extension.HudColor
import com.eider.karoomaverickhud.extension.HudIcon
import com.eider.karoomaverickhud.extension.HudSnapshot
import com.eider.karoomaverickhud.extension.MAX_CELLS
import com.eider.karoomaverickhud.extension.Trajectory

/**
 * HUD on a 420×150 Maverick screen. Data lives in the two edge columns (the first and last of
 * a notional four) so the centre stays a clear field of vision, with two or three rows each.
 *
 * Cell layout depends on how many fields the page packs:
 *  - ≤4 fields (two roomy rows): the value sits on top with its unit/label stacked just below.
 *  - 5–6 fields (three rows): the value hugs the column's outer edge and its unit/label (or icon)
 *    tucks in beside it on the inboard side. Dropping the stacked label line frees the vertical
 *    room three rows would otherwise need, so the value runs the same large face as the ≤4 pages
 *    instead of a shrunken one. The inboard offset is the value's measured text width
 *    ([Text.getMeasuredContentWidth], populated on setText) plus [sideGap], so it tucks against
 *    the digits no matter how wide the number is.
 *
 * The value is tinted by its training-zone colour and is the only coloured element; the label/tag
 * text renders white and the icon stays dim grey. The SDK's [Align] decides which edge of a single
 * line sits at its anchor X, so values right-align to [rightX] on the right column and left-align to
 * [leftX] on the left. Forward/back temple-pad swipes flip pages.
 */
class HudScreen : Screen(420f, 150f) {

    private val screenW = 420f
    private val leftX = 8f
    private val rightX = screenW - 8f
    // Reduced icon footprint (was 26, matching the source PNGs): a smaller glyph leaves room for a
    // differentiator tag beside it and reads as secondary to the value. [iconTextGap] is the space
    // between the icon and that tag. Icons are scaled to [iconW] at render via Image.setWidthHeight.
    private val iconW = 18f
    private val iconTextGap = 3f

    // Side-by-side (5–6 field) tuning, in screen px. [sideGap] is the space between the value and
    // its inboard icon/label; [sideLabelDy] drops the smaller icon/label down to sit against the
    // tall ~42px value face. Tune on-device if the label crowds the digits or sits high/low.
    private val sideGap = 6f
    private val sideLabelDy = 16f

    // Centre control-window geometry (a bordered box with time/signal/battery + a brightness slider).
    private val boxX = 50f
    private val boxY = 31f
    private val boxW = 320f
    private val boxH = 88f
    private val ctrlLeft = 72f
    private val ctrlRight = 348f
    private val ctrlTopY = 44f
    private val sliderY = 94f // baseline for the brightness status line

    /**
     * Per-cell placement: which edge it hugs, the value's Y, where the unit/label and icon sit, and
     * whether the label is beside the value ([side]) or stacked beneath it. X is resolved per frame —
     * from the column edge for the value, and (in side mode) from the value's measured width.
     */
    private data class Slot(
        val isRight: Boolean,
        val valueY: Float,
        val labelY: Float,
        val iconY: Float,
        val side: Boolean,
    )

    /**
     * Slots ordered by field count. The first four fields always take the four corners; a 5th
     * and 6th drop into the centre row (left/right). ≤4-field pages stack the unit under the value;
     * 5–6-field pages put it beside the value (see the class doc) and centre the three rows.
     */
    private fun slotsFor(count: Int): Array<Slot> {
        return if (count <= 4) {
            // Big value face (~33px) stacked over a small unit (~18px): two roomy rows. The gap
            // tucks the unit just under the digits; rows sit clear of the 150px top/bottom edges.
            // The icon shares the label's line (icon + differentiator tag side by side), so iconY == labelY.
            fun stack(isRight: Boolean, valueY: Float) =
                Slot(isRight, valueY = valueY, labelY = valueY + 30f, iconY = valueY + 30f, side = false)
            arrayOf(
                stack(false, 14f), stack(true, 14f),    // TL, TR
                stack(false, 88f), stack(true, 88f),    // BL, BR
            )
        } else {
            // Side-by-side: the value runs the tall ~42px face and hugs the edge, with the unit/icon
            // beside it on the inboard side. Without a stacked unit line, three of these taller value
            // rows still sit evenly down the 150px lens (digits occupy the upper part of each box).
            fun side(isRight: Boolean, valueY: Float) =
                Slot(isRight, valueY = valueY, labelY = valueY + sideLabelDy, iconY = valueY + sideLabelDy, side = true)
            arrayOf(
                side(false, 4f), side(true, 4f),       // TL, TR
                side(false, 104f), side(true, 104f),   // BL, BR
                side(false, 54f), side(true, 54f),     // ML, MR (centre)
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
    // assets/fonts. Each occupies its own glasses font slot; the HxW token in each filename is parsed
    // by the SDK to set the glyph dimensions, so the generated names are verbatim. ≤4-field pages stack
    // the big 33×25 value over the 18×12 unit. 5–6-field pages lay the unit/icon *beside* the value
    // (see [slotsFor]); freeing that vertical room lets them run an even taller 42×31 value face for a
    // markedly more legible three-row readout. The unit is the 18×12 face on every page. (The old
    // 31×22 / 13×9 faces are now unused.)
    private val valueFontBig = Font("fonts/RobotoCondensed-SemiBold.ttf.33x25.2bpp.sifz", Font.Slot.s1)
    private val valueFontTall = Font("fonts/RobotoCondensed-SemiBold.ttf.42x31.2bpp.sifz", Font.Slot.s2)
    private val unitFontBig = Font("fonts/RobotoCondensed-SemiBold.ttf.18x12.2bpp.sifz", Font.Slot.s3)

    /** Value face: the big 33×25 face on ≤4-field pages; the tall 42×31 face on the side-by-side 5–6 pages. */
    private fun valueFontFor(count: Int): Font = if (count <= 4) valueFontBig else valueFontTall

    /** Unit face — the 18×12 face on every count. */
    private fun unitFontFor(@Suppress("UNUSED_PARAMETER") count: Int): Font = unitFontBig
    private val statusText = Text() // centred "waiting for ride" when idle
    private val pauseDot = Text()
    private val clockText = Text() // time of day (shown inside the control window)
    private val batteryText = Text() // glasses battery % (shown inside the control window)
    private val batteryWarnText = Text() // low-battery % overlaid top-centre, tinted by BatteryWarn tier
    private val ecoText = Text() // "ECO" badge, top-left, while battery-saver is engaged

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
    private val brightText = Text() // focused control item, e.g. "BRIGHTNESS  60%" / "RADAR  ON"
    private val ctrlHint = Text() // "hold: next  ·  swipe/tap: change" — how to drive the control window

    // Trajectory map (shown on descents / when paged to): the heading-up route polyline, a small
    // "you" marker at the bottom, and corner readouts for speed, grade and the current zoom.
    private val trajLine = Polyline()
    private val trajMarker = Polygon()
    private val trajSpeed = Text()
    private val trajGrade = Text()
    private val trajZoom = Text()
    private var trajMaxPoints = 0 // the Polyline's point cap, read once on first render

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

    // Which control item the temple pad is acting on (0=Brightness, 1=Auto, 2=Radar, 3=Trajectory,
    // 4=Race), and the route-feature states, so the window can show the focused item and its value.
    @Volatile private var ctrlFocus = 0
    @Volatile private var ctrlRadar = false
    @Volatile private var ctrlTraj = false
    @Volatile private var ctrlRace = false

    // Trajectory zoom: metres of road ahead that fill the screen. Cycled by temple-pad taps via
    // [setTrajectoryZoom] while the trajectory page is shown.
    @Volatile private var trajLookaheadM = 200f

    fun apply(next: HudSnapshot) {
        snapshot = next
    }

    /**
     * Push control-window state: open + brightness 0..100 + auto + signal bars 0..3, plus the
     * focused item (0=Brightness, 1=Auto, 2=Radar, 3=Trajectory, 4=Race) and the route-feature states.
     */
    fun setControl(open: Boolean, brightness: Int, auto: Boolean, signal: Int, focus: Int, radarOn: Boolean, trajOn: Boolean, raceOn: Boolean) {
        controlOpen = open
        ctrlBrightness = brightness
        ctrlAuto = auto
        ctrlSignal = signal
        ctrlFocus = focus
        ctrlRadar = radarOn
        ctrlTraj = trajOn
        ctrlRace = raceOn
    }

    /** Set how many metres of road ahead the trajectory map shows (zoom), cycled by temple-pad taps. */
    fun setTrajectoryZoom(lookaheadMeters: Float) {
        trajLookaheadM = lookaheadMeters
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
                .setResource(unitFontFor(layoutCount))
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

        // Low-battery readout: hugs the very top centre, above the pause/status marker (which drops
        // a line while this is visible). Hidden until the glasses battery falls into a BatteryWarn tier.
        batteryWarnText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(getWidth() / 2f, 4f)
            .setForegroundColor(EvsColor.White.rgba)
            .setVisibility(false)
            .addTo(this)

        // ECO badge: top-left corner, lit amber while battery-saver is engaged; clear of the
        // centred pause/battery markers. Hidden until a saver-stamped snapshot arrives.
        ecoText
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.left)
            .setXY(leftX, 4f)
            .setForegroundColor(EvsColor.Yellow.rgba)
            .setVisibility(false)
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
        setupTrajectory()
    }

    /** Create the trajectory map's polyline, "you" marker and corner readouts (all hidden). */
    private fun setupTrajectory() {
        trajLine.setForegroundColor(CYAN_RGBA).setPenThickness(2).setVisibility(false)
        add(trajLine)
        trajMarker.setForegroundColor(EvsColor.White.rgba).setPenThickness(2).setVisibility(false)
        add(trajMarker)
        trajSpeed
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.left)
            .setXY(leftX, 6f)
            .setForegroundColor(EvsColor.White.rgba)
            .setVisibility(false)
            .addTo(this)
        trajGrade
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.right)
            .setXY(rightX, 6f)
            .setForegroundColor(EvsColor.White.rgba)
            .setVisibility(false)
            .addTo(this)
        trajZoom
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.left)
            .setXY(leftX, 120f)
            .setForegroundColor(LABEL_RGBA)
            .setVisibility(false)
            .addTo(this)
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

        ctrlHint
            .setText("")
            .setResource(Font.StockFont.Small)
            .setTextAlign(Align.center)
            .setXY(boxX + boxW / 2f, sliderY + 10f)
            .setForegroundColor(LABEL_RGBA)
            .setVisibility(false)
            .addTo(this)
    }

    /** Re-flow slot positions when the active field count changes. */
    private fun applyCount(count: Int) {
        layoutCount = count
        slots = slotsFor(count)
        // ≤4-field pages get the big value + medium unit; 5–6-field pages the larger value + smaller unit.
        val vf = valueFontFor(count)
        val uf = unitFontFor(count)
        for (i in 0 until cellCount) {
            values[i].setResource(vf)
            units[i].setResource(uf)
        }
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
        renderBatteryWarning(snap)

        // The glasses are see-through, so the control window can't occlude — clear the HUD
        // behind it instead, leaving only the window legible.
        if (controlOpen) {
            for (i in 0 until cellCount) blankCell(i)
            hideTrajectory()
            statusText.setText("")
            pauseDot.setText("")
            ecoText.setVisibility(false)
            return
        }

        // ECO badge (top-left) whenever battery-saver is engaged — shown over both the waiting
        // screen and the live HUD below.
        ecoText.setText(if (snap.eco) "ECO" else "").setVisibility(snap.eco)

        // Connected to the Karoo but no ride yet — show a holding message, blank the grid.
        if (!snap.recording && !snap.paused) {
            for (i in 0 until cellCount) blankCell(i)
            hideTrajectory()
            statusText.setText("WAITING FOR RIDE")
            pauseDot.setText("KAROO CONNECTED")
            return
        }

        statusText.setText("")

        // Trajectory map page: draw the heading-up route polyline instead of data cells.
        if (snap.pageIndex == snap.trajectoryPageIndex && snap.trajectory != null) {
            for (i in 0 until cellCount) blankCell(i)
            renderTrajectory(snap.trajectory!!)
            pauseDot.setText(if (snap.paused) "‖ PAUSED" else "")
            return
        }
        hideTrajectory()

        val page: List<HudCell> = snap.pages.getOrNull(snap.pageIndex).orEmpty()
        val count = page.size.coerceIn(1, cellCount)
        if (count != layoutCount) applyCount(count)
        for (i in 0 until cellCount) {
            if (i < count) layoutCell(i, slots[i], page.getOrNull(i), snap.showIcons) else blankCell(i)
        }
        pauseDot.setText(if (snap.paused) "‖ PAUSED" else "")
    }

    /**
     * Draw the heading-up trajectory: the rider sits at bottom-centre looking up, so +forward maps
     * upward and +right rightward. Metres are scaled to pixels so [trajLookaheadM] of road fills the
     * vertical span; points beyond the zoom window (or the widget's point cap) are dropped.
     */
    private fun renderTrajectory(traj: Trajectory) {
        if (trajMaxPoints == 0) trajMaxPoints = trajLine.getMaxPoints().coerceAtLeast(2)
        val ppm = TRAJ_SPAN_PX / trajLookaheadM
        val cx = screenW / 2f

        trajLine.clear()
        var added = 0
        for (p in traj.points) {
            if (p.forwardM > trajLookaheadM) break // past the visible window (points run forward)
            if (added >= trajMaxPoints) break
            trajLine.add(cx + p.rightM * ppm, TRAJ_BOTTOM_Y - p.forwardM * ppm)
            added++
        }
        trajLine.setForegroundColor(CYAN_RGBA).setPenThickness(2).setVisibility(added >= 2)

        // "You" — a small upward triangle at the bottom-centre origin.
        trajMarker.clear()
        trajMarker.addPoint(cx - 5f, TRAJ_BOTTOM_Y)
            .addPoint(cx + 5f, TRAJ_BOTTOM_Y)
            .addPoint(cx, TRAJ_BOTTOM_Y - 11f)
        trajMarker.setForegroundColor(EvsColor.White.rgba).setPenThickness(2).setVisibility(true)

        // Corner readouts: speed (left), grade (right, tinted), zoom (bottom-left).
        val speed = traj.overlay.getOrNull(0)
        val grade = traj.overlay.getOrNull(1)
        trajSpeed.setText(overlayText(speed)).setForegroundColor(EvsColor.White.rgba).setVisibility(true)
        trajGrade.setText(overlayText(grade)).setForegroundColor(colorRgba(grade?.color ?: HudColor.WHITE)).setVisibility(true)
        trajZoom.setText("${trajLookaheadM.toInt()} m").setVisibility(true)
    }

    /** Combine a readout cell's value and unit into one short string, e.g. "32.5 km/h". */
    private fun overlayText(cell: HudCell?): String =
        if (cell == null) "" else "${cell.value} ${cell.units}".trim()

    /** Hide every trajectory-map element (when not on the trajectory page). */
    private fun hideTrajectory() {
        trajLine.setVisibility(false)
        trajMarker.setVisibility(false)
        trajSpeed.setVisibility(false)
        trajGrade.setVisibility(false)
        trajZoom.setVisibility(false)
    }

    /**
     * Top-centre low-battery readout. Shows the glasses battery % tinted by its [BatteryWarn] tier
     * (yellow ≤30, orange ≤20, red ≤10) and nudges the pause/status marker down a line so the two
     * don't share the top edge; hidden entirely when the battery is healthy or unknown.
     */
    private fun renderBatteryWarning(snap: HudSnapshot) {
        val warn = BatteryWarn.forLevel(snap.battery)
        if (warn == null) {
            batteryWarnText.setText("").setVisibility(false)
            pauseDot.setXY(getWidth() / 2f, 4f)
        } else {
            batteryWarnText
                .setText("${snap.battery}%")
                .setForegroundColor(colorRgba(warn.color))
                .setVisibility(true)
            pauseDot.setXY(getWidth() / 2f, 26f)
        }
    }

    /**
     * Render one cell: the value, then its label area. With icons on the label area is the field's
     * icon plus a short white differentiator tag for fields that share an icon ([HudCell.iconLabel])
     * — the original field that owns an icon shows it alone; with icons off it's the full unit text.
     * On ≤4-field pages the label area stacks under the value; on 5–6-field pages ([Slot.side]) it
     * sits beside the value, offset by the value's measured width so it tucks against the digits.
     */
    private fun layoutCell(i: Int, slot: Slot, cell: HudCell?, showIcons: Boolean) {
        val color = colorRgba(cell?.color ?: HudColor.WHITE)
        val align = if (slot.isRight) Align.right else Align.left
        val anchorX = if (slot.isRight) rightX else leftX

        values[i].setText(cell?.value ?: "").setForegroundColor(color)
        values[i].setTextAlign(align).setXY(anchorX, slot.valueY)

        // The label/tag text. With icons on it's the short differentiator that tells fields sharing
        // an icon apart — empty for the original that owns the icon, so it shows the icon alone;
        // with icons off it's the full unit. White either way now (a legible second line), while
        // the zone-coloured value stays the only coloured element on the LCOS.
        val icon = cell?.icon
        val iconOn = showIcons && icon != null
        val text = if (iconOn) cell?.iconLabel.orEmpty() else cell?.units.orEmpty()
        units[i].setText(text).setForegroundColor(WHITE_RGBA)
        units[i].setTextAlign(align)

        // Where the icon+tag pair hugs. Stacked pages anchor it at the column edge under the value;
        // side pages push it inboard past the value by the value's measured width + a gap, so it
        // tucks against the digits however wide the number is. setText (above) already refreshed
        // each element's cachedMeasuredWidth, so getMeasuredContentWidth is current this frame.
        val tagX: Float = if (slot.side) {
            val valueW = values[i].getMeasuredContentWidth()
            if (slot.isRight) rightX - valueW - sideGap else leftX + valueW + sideGap
        } else {
            anchorX
        }

        // Lay the icon and its tag side by side, growing inboard (rightward on the left column,
        // leftward on the right) so the pair never overlaps the value or the clear centre.
        if (icon == null || !showIcons) {
            units[i].setXY(tagX, slot.labelY)
            icons[i].setVisibility(false)
            currentIcon[i] = null
        } else {
            val textW = if (text.isEmpty()) 0f else units[i].getMeasuredContentWidth()
            val gap = if (textW > 0f) iconTextGap else 0f
            val iconX: Float
            if (slot.isRight) {
                units[i].setXY(tagX, slot.labelY)                 // tag right-aligns at tagX
                iconX = tagX - textW - gap - iconW                // icon sits to the tag's left
            } else {
                iconX = tagX
                units[i].setXY(tagX + iconW + gap, slot.labelY)   // tag left-aligns just past the icon
            }
            if (currentIcon[i] != icon) {
                icons[i].setResource(imgSrcFor(icon))
                currentIcon[i] = icon
            }
            icons[i].setWidthHeight(iconW, iconW).setForegroundColor(LABEL_RGBA)
                .setXY(iconX, slot.iconY).setVisibility(true)
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

        // The focused control item and its value; temple-pad long-tap cycles which item this is.
        val (line, color) = when (ctrlFocus) {
            1 -> "AUTO BRIGHT  ${onOff(ctrlAuto)}" to (if (ctrlAuto) EvsColor.Yellow.rgba else EvsColor.White.rgba)
            2 -> "RADAR  ${onOff(ctrlRadar)}" to EvsColor.White.rgba
            3 -> "TRAJECTORY  ${onOff(ctrlTraj)}" to EvsColor.White.rgba
            4 -> "RACE  ${onOff(ctrlRace)}" to (if (ctrlRace) EvsColor.Yellow.rgba else EvsColor.White.rgba)
            else -> if (ctrlAuto) {
                "BRIGHTNESS  AUTO" to EvsColor.Yellow.rgba
            } else {
                "BRIGHTNESS  ${ctrlBrightness.coerceIn(0, 100)}%" to EvsColor.White.rgba
            }
        }
        brightText.setText(line).setForegroundColor(color)
        ctrlHint.setText("hold: next  ·  swipe/tap: change")
    }

    private fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

    private fun setControlVisible(visible: Boolean) {
        ctrlBox.setVisibility(visible)
        clockText.setVisibility(visible)
        batteryText.setVisibility(visible)
        brightText.setVisibility(visible)
        ctrlHint.setVisibility(visible)
        if (!visible) sigBars.forEach { it.setVisibility(false) }
    }

    /**
     * Folds glasses-side UI state that isn't in the snapshot (control window + trajectory zoom) into
     * one int, so onUpdateUI re-renders on a change even when the pushed snapshot is unchanged.
     */
    private fun controlSignature(): Int {
        var h = if (controlOpen) 1 else 0
        h = h * 131 + ctrlBrightness
        h = h * 131 + (if (ctrlAuto) 1 else 0)
        h = h * 131 + ctrlSignal
        h = h * 131 + trajLookaheadM.toInt()
        h = h * 131 + ctrlFocus
        h = h * 131 + (if (ctrlRadar) 1 else 0)
        h = h * 131 + (if (ctrlTraj) 1 else 0)
        h = h * 131 + (if (ctrlRace) 1 else 0)
        return h
    }

    private fun blankCell(i: Int) {
        values[i].setText("")
        units[i].setText("")
        icons[i].setVisibility(false)
        currentIcon[i] = null
    }

    private fun colorRgba(color: HudColor): Int = when (color) {
        HudColor.WHITE -> WHITE_RGBA
        HudColor.GREEN -> GREEN_RGBA
        HudColor.YELLOW -> YELLOW_RGBA
        HudColor.ORANGE -> ORANGE_RGBA
        HudColor.RED -> RED_RGBA
        HudColor.PURPLE -> PURPLE_RGBA
        HudColor.CYAN -> CYAN_RGBA
    }

    companion object {
        // Single tuning surface for the on-glasses zone palette. Red/Purple use explicit bright RGB
        // because stock EvsColor.Red/Purple render too dim on the LCOS in daylight (confirmed
        // on-device); the rest keep the stock hues. The closest pairs to read apart at a glance are
        // YELLOW vs ORANGE and WHITE vs CYAN — if they're hard to distinguish on-device, separate
        // them here (push Orange redder / Cyan bluer via EvsColors.fromRgb) and mirror the new value
        // in KarooTheme's K.z* tokens so the settings preview still matches the glasses.
        private val WHITE_RGBA = EvsColor.White.rgba
        private val GREEN_RGBA = EvsColor.Green.rgba
        private val YELLOW_RGBA = EvsColor.Yellow.rgba
        private val ORANGE_RGBA = EvsColor.Orange.rgba
        private val RED_RGBA = EvsColors.fromRgb(0xFF, 0x7B, 0x7B)     // light red — stock too dim
        private val PURPLE_RGBA = EvsColors.fromRgb(0xFF, 0x60, 0xB0)  // pink — stock too dim
        private val CYAN_RGBA = EvsColor.Cyan.rgba

        // Secondary ink: a dim grey for the field icons and the control-window hint/zoom chrome, so
        // the colored value stays the brightest element. (Unit/tag labels now render white — see
        // layoutCell — so they read clearly as the value's second line.) Matches Lens.label in
        // KarooTheme so the settings preview reads the same.
        private val LABEL_RGBA = EvsColors.fromRgb(0x99, 0xA1, 0xAC)

        // Trajectory map geometry: the rider sits near the bottom looking up; the road ahead fills
        // the vertical span above. [TRAJ_SPAN_PX] of pixels represents one full zoom look-ahead.
        private const val TRAJ_BOTTOM_Y = 134f
        private const val TRAJ_TOP_Y = 16f
        private const val TRAJ_SPAN_PX = TRAJ_BOTTOM_Y - TRAJ_TOP_Y
    }

    override fun onTouch(touch: TouchDirection) {
        onTouchPad?.invoke(touch)
    }
}
