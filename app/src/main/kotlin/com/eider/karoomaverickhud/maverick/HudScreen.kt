@file:OptIn(ExperimentalUnsignedTypes::class) // EvsKit's UIKit (Screen/UIElement/EvsColor.rgba) is built on UInt

package com.eider.karoomaverickhud.maverick

import UIKit.app.Screen
import UIKit.app.data.Align
import UIKit.app.data.EvsColor
import UIKit.app.resources.Font
import UIKit.widgets.Text
import UIKit.widgets.UIElement
import com.eider.karoomaverickhud.extension.HudCell
import com.eider.karoomaverickhud.extension.HudFieldId
import com.eider.karoomaverickhud.extension.HudSnapshot

/**
 * Two-page 2×2 HUD rendered on a 420×150 Maverick screen.
 *
 *  Page 0 ─ Power | Cadence
 *           L/R   | Speed
 *
 *  Page 1 ─ Dist  | Avg Speed
 *           HR    | Time
 *
 * The screen owns 12 [Text] widgets (4 cells × 3 elements: label, value, units) plus
 * a tiny pause indicator. `onUpdateUI` pulls the current [HudSnapshot] off a
 * volatile field and rewrites only the strings that changed — no widgets are added
 * or removed across pages, so the layout never reflows.
 */
class HudScreen : Screen(420f, 150f) {

    // 2×2 grid: column centers at x=105 / x=315; row centers at y≈45 / y≈110
    private data class Slot(val cx: Float, val labelY: Float, val valueY: Float, val unitsY: Float)
    private val slots = arrayOf(
        Slot(cx = 105f, labelY = 18f, valueY = 48f, unitsY = 70f),
        Slot(cx = 315f, labelY = 18f, valueY = 48f, unitsY = 70f),
        Slot(cx = 105f, labelY = 82f, valueY = 112f, unitsY = 134f),
        Slot(cx = 315f, labelY = 82f, valueY = 112f, unitsY = 134f),
    )

    private val labels = Array(4) { Text() }
    private val values = Array(4) { Text() }
    private val units = Array(4) { Text() }
    private val pauseDot = Text()

    private val pages: Array<Array<HudFieldId>> = arrayOf(
        arrayOf(HudFieldId.POWER, HudFieldId.CADENCE, HudFieldId.LR_BALANCE, HudFieldId.SPEED),
        arrayOf(HudFieldId.DISTANCE, HudFieldId.AVG_SPEED, HudFieldId.HEART_RATE, HudFieldId.ELAPSED_TIME),
    )

    @Volatile private var snapshot: HudSnapshot = HudSnapshot.empty

    fun apply(next: HudSnapshot) {
        snapshot = next
    }

    /** Builder-style sugar over [Screen.add] so widget setup reads as a single chain. */
    private fun UIElement.addTo(screen: Screen): UIElement = also { screen.add(it) }

    override fun onCreate() {
        for (i in 0..3) {
            val s = slots[i]
            labels[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.center)
                .setXY(s.cx, s.labelY)
                .setForegroundColor(EvsColor.Green.rgba)
                .addTo(this)

            values[i]
                .setText("--")
                .setResource(Font.StockFont.Large)
                .setTextAlign(Align.center)
                .setXY(s.cx, s.valueY)
                .setForegroundColor(EvsColor.Green.rgba)
                .addTo(this)

            units[i]
                .setText("")
                .setResource(Font.StockFont.Small)
                .setTextAlign(Align.center)
                .setXY(s.cx, s.unitsY)
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
    }

    override fun onUpdateUI(time: Long) {
        val snap = snapshot
        val page = pages[snap.pageIndex.coerceIn(0, pages.lastIndex)]
        for (i in 0..3) {
            val cell: HudCell? = snap.cells[page[i]]
            labels[i].setText(cell?.label.orEmpty().ifEmpty { page[i].label })
            values[i].setText(cell?.value ?: "--")
            units[i].setText(cell?.units.orEmpty())
        }
        pauseDot.setText(
            when {
                snap.paused -> "‖ PAUSED"
                !snap.recording -> "○ IDLE"
                else -> ""
            },
        )
    }
}
