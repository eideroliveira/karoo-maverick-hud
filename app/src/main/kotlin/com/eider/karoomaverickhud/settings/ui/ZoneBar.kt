/* ============================================================
   ZoneBar.kt — training-zone editors. ZoneRowsEditor lays each
   zone boundary on its own row with a stepper (far more legible
   on the narrow panel than a draggable bar), above a thin
   non-interactive colour ramp. Plus CadenceScale.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eider.karoomaverickhud.extension.ZoneBand
import kotlin.math.roundToInt

/**
 * Editable zones as a vertical list — one row per zone, each showing its colour, name, range
 * (in [unit], converted from the % bands), and a stepper on its upper boundary. Adjusting a
 * boundary moves the shared edge with the next zone. The last zone (open-topped) has no stepper.
 * A thin colour ramp at the top gives the visual the old draggable bar did, without the
 * overlapping value readouts that crowded the narrow panel.
 */
@Composable
fun ZoneRowsEditor(
    zones: List<ZoneBand>,
    base: Int,
    unit: String,
    colors: List<Color>,
    onChange: (List<ZoneBand>) -> Unit,
) {
    if (zones.isEmpty()) return
    fun pctToVal(p: Int) = (p / 100f * base).roundToInt()

    Column(Modifier.fillMaxWidth()) {
        // thin colour ramp (segments only — no handles, no labels)
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            zones.forEachIndexed { i, z ->
                Box(
                    Modifier
                        .weight((z.hi - z.lo).toFloat().coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(colors.getOrElse(i) { K.zWhite }),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        zones.forEachIndexed { i, z ->
            val isLast = i == zones.lastIndex
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(13.dp).clip(CircleShape).background(colors.getOrElse(i) { K.zWhite }))
                Column(Modifier.weight(1f)) {
                    KText("${z.name} · ${z.sub}", color = K.text, size = 18.sp, weight = FontWeight.Medium, maxLines = 1)
                    KText(
                        if (isLast) "${pctToVal(z.lo)}+ $unit" else "${pctToVal(z.lo)}–${pctToVal(z.hi)} $unit",
                        color = K.text2, size = 15.sp, maxLines = 1,
                    )
                }
                if (!isLast) {
                    KStepper(
                        value = z.hi, min = z.lo + 1, max = zones[i + 1].hi - 1, step = 1, unit = "%",
                        valueWidth = 70.dp,
                        onChange = { newHi ->
                            onChange(
                                zones.mapIndexed { j, zz ->
                                    when (j) {
                                        i -> zz.copy(hi = newHi)
                                        i + 1 -> zz.copy(lo = newHi)
                                        else -> zz
                                    }
                                },
                            )
                        },
                    )
                } else {
                    KText("top", color = K.text3, size = 15.sp)
                }
            }
            if (!isLast) Box(Modifier.fillMaxWidth().height(1.dp).background(K.line))
        }
    }
}

/** Cadence colour scale with a marker at the ideal rpm (50..120 visualized). */
@Composable
fun CadenceScale(ideal: Int) {
    val lo = 50f; val hi = 120f
    val frac = ((ideal - lo) / (hi - lo)).coerceIn(0f, 1f)
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0f) }
    val brush = Brush.horizontalGradient(
        0.0f to K.zRed, 0.18f to K.zOrange, 0.42f to K.zGreen,
        0.58f to K.zGreen, 0.82f to K.zOrange, 1.0f to K.zRed,
    )
    Column {
        Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(brush))
        Box(Modifier.fillMaxWidth().height(22.dp).onSizeChanged { widthPx = it.width.toFloat() }) {
            if (widthPx > 0f) {
                Column(
                    Modifier.offset { IntOffset((widthPx * frac - with(density) { 10.dp.toPx() }).roundToInt(), 0) }.width(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(2.dp, 8.dp).background(Color.White))
                    KText(ideal.toString(), color = K.text, size = 15.sp, weight = FontWeight.Bold, family = CondFamily)
                }
            }
        }
    }
}
