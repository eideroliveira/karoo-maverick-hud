@file:OptIn(ExperimentalUnsignedTypes::class) // EvsKit's UIKit (Screen/UIElement/EvsColor.rgba) is built on UInt

package com.eider.karoomaverickhud.maverick

import UIKit.app.Screen
import UIKit.app.data.Align
import UIKit.app.data.EvsColor
import UIKit.app.data.TouchDirection
import UIKit.app.resources.Font
import UIKit.widgets.Text
import UIKit.widgets.UIElement
import com.eider.karoomaverickhud.extension.HudCell
import com.eider.karoomaverickhud.extension.HudSnapshot

/**
 * A 2×2 HUD rendered on a 420×150 Maverick screen. The page contents are dynamic — a
 * page is just a list of up to four [HudCell]s, supplied per snapshot — so the same
 * screen serves the user's custom pages and the mirrored Karoo page.
 *
 * The screen owns 12 [Text] widgets (4 cells × 3 elements: label, value, units) plus a
 * tiny status indicator. `onUpdateUI` pulls the current [HudSnapshot] off a volatile
 * field and rewrites only the strings — no widgets are added or removed across pages, so
 * the layout never reflows. Temple-pad touches are forwarded to [onTouch] for MANUAL mode.
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

    @Volatile private var snapshot: HudSnapshot = HudSnapshot.empty

    /** Set by [MaverickBridge] to handle temple-pad touches (MANUAL page switching). */
    @Volatile var onTouchPad: ((TouchDirection) -> Unit)? = null

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

    override fun onUpdateUI(timestampMs: Long) {
        val snap = snapshot
        val page: List<HudCell> = snap.pages.getOrNull(snap.pageIndex).orEmpty()
        for (i in 0..3) {
            val cell = page.getOrNull(i)
            labels[i].setText(cell?.label.orEmpty())
            values[i].setText(cell?.value ?: "")
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

    override fun onTouch(touch: TouchDirection) {
        onTouchPad?.invoke(touch)
    }
}
