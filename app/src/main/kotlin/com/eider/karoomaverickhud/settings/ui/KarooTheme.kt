/* ============================================================
   KarooTheme.kt — Karoo OS native design tokens + SVG-path icon.
   Mirrors styles.css :root and data.jsx ICON_PATHS / <Icon>.
   Dark, high-contrast; colour is reserved for data/zone meaning.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eider.karoomaverickhud.extension.HudColor

/** Surfaces, text, accent and the zone/data palette — straight from styles.css. */
object K {
    val bg = Color(0xFF08090B)
    val surface = Color(0xFF121419)
    val surface2 = Color(0xFF1A1D23)
    val surface3 = Color(0xFF23272F)
    val surface4 = Color(0xFF2D323B)
    val line = Color(0xFF2A2F38)
    val line2 = Color(0xFF383E49)
    val line3 = Color(0xFF4A515E)

    val text = Color(0xFFF3F5F7)
    val text2 = Color(0xFF99A1AC)
    val text3 = Color(0xFF646B76)

    val accent = Color(0xFF1FE3C8)
    val accentDim = Color(0x241FE3C8)      // ~0.14 alpha
    val accentLine = Color(0x661FE3C8)     // ~0.40 alpha
    val accentPress = Color(0xFF15B6A0)
    val onAccent = Color(0xFF042720)

    // zone / data palette
    val zWhite = Color(0xFFFFFFFF)
    val zGreen = Color(0xFF36D85F)
    val zYellow = Color(0xFFFFD60A)        // cadence drift (no CSS token; matches HUD yellow)
    val zOrange = Color(0xFFFF9F0A)
    val zRed = Color(0xFFFF7B7B)           // light red — was dim on LCOS
    val zPurple = Color(0xFFFF60B0)        // pink — was dim on LCOS
    val zBlue = Color(0xFF2E8DFF)
    val zCyan = Color(0xFF34D2E0)          // power Z0 (sub-recovery)

    val good = Color(0xFF36D85F)
    val warn = Color(0xFFFF9F0A)
    val bad = Color(0xFFFF453A)
}

/** Map a rendered [HudColor] to its settings-preview Color, so preview == glasses. */
fun HudColor.toComposeColor(): Color = when (this) {
    HudColor.WHITE -> K.zWhite
    HudColor.GREEN -> K.zGreen
    HudColor.YELLOW -> K.zYellow
    HudColor.ORANGE -> K.zOrange
    HudColor.RED -> K.zRed
    HudColor.PURPLE -> K.zPurple
    HudColor.CYAN -> K.zCyan
}

/** Colours per power/HR zone index, aligned with FieldFormat's POWER/HR_ZONE_COLORS. */
val POWER_ZONE_UI_COLORS = listOf(K.zCyan, K.zWhite, K.zGreen, K.zYellow, K.zOrange, K.zRed, K.zPurple)
val HR_ZONE_UI_COLORS = listOf(K.zWhite, K.zGreen, K.zOrange, K.zRed, K.zPurple)

/* ---- Icon set: crisp line glyphs, viewBox 24, stroked with the given colour ---- */
val ICON_PATHS: Map<String, String> = mapOf(
    "power" to "M13 2 4 14h7l-1 8 9-12h-7l1-8Z",
    "cadence" to "M21 12a9 9 0 1 1-3-6.7 M21 4v5h-5 M12 12 m-2.4 0 a2.4 2.4 0 1 0 4.8 0 a2.4 2.4 0 1 0 -4.8 0",
    "heart" to "M19.5 13.6c1.5-1.5 2.5-3 2.5-5.1A4.5 4.5 0 0 0 12 5.7a4.5 4.5 0 0 0-10 2.8c0 2.1 1 3.6 2.5 5.1L12 21Z",
    "speed" to "M3.5 18a9 9 0 1 1 17 0 M12 14.5 16 9 M12 15 m-1.5 0 a1.5 1.5 0 1 0 3 0 a1.5 1.5 0 1 0 -3 0",
    "distance" to "M6 18 m-2.4 0 a2.4 2.4 0 1 0 4.8 0 a2.4 2.4 0 1 0 -4.8 0 M18 6 m-2.4 0 a2.4 2.4 0 1 0 4.8 0 a2.4 2.4 0 1 0 -4.8 0 M8 16.5 16 7.5",
    "time" to "M12 12 m-9 0 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0 M12 7v5l3.5 2",
    "balance" to "M3 8h7.5v8H3z M13.5 8H21v8h-7.5z",
    "gear" to "M12 12 m-3.2 0 a3.2 3.2 0 1 0 6.4 0 a3.2 3.2 0 1 0 -6.4 0 M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1",
    "grade" to "M3 19 21 5 M3 19h18",
    "ascent" to "m3 18 5-8 4 5 3-4 6 7Z",
    "temp" to "M14 14.8V5a2 2 0 1 0-4 0v9.8a4 4 0 1 0 4 0Z",
    "np" to "M4 18V6l8 8 8-8v12",
    "calories" to "M12 3c1.5 3-1.5 4.5-1.5 7A1.5 1.5 0 0 0 12 11c2 0 2.5-2 2.5-2 2 2 2.5 4 2.5 5a5 5 0 1 1-10 0c0-3 3-5 5-11Z",
    // ui glyphs
    "chevron" to "m9 6 6 6-6 6",
    "back" to "m15 6-6 6 6 6",
    "check" to "m5 12 5 5 9-11",
    "plus" to "M12 5v14M5 12h14",
    "glasses" to "M2 12a3 3 0 0 1 3-3h2.2a2 2 0 0 1 1.9 1.4l.4 1.2a2.5 2.5 0 0 0 4.8 0l.4-1.2A2 2 0 0 1 18.8 9H21M2 12v1.5A3.5 3.5 0 0 0 5.5 17h.5a3.5 3.5 0 0 0 3.5-3.5V12M22 12v1.5A3.5 3.5 0 0 1 18.5 17H18a3.5 3.5 0 0 1-3.5-3.5V12",
    "rider" to "M12 6 m-3 0 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0 M6 21v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2",
    "pages" to "M3 4h8v16H3z M13 4h8v16h-8z",
    "bolt" to "M13 2 4 14h7l-1 8 9-12h-7l1-8Z",
    "bt" to "m7 7 10 10-5 4V3l5 4L7 17",
    "battery" to "M2 8h17v9H2z M22 11v3",
    "brightness" to "M12 12 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0 M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M19 5l-1.5 1.5M6.5 17.5 5 19",
    "trash" to "M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13",
    "link" to "M9 15 15 9M10 6l1-1a4 4 0 0 1 6 6l-1 1M14 18l-1 1a4 4 0 0 1-6-6l1-1",
    "search" to "M11 11 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 M20 20L16.5 16.5",
    "refresh" to "M21 12a9 9 0 1 1-3-6.7 M21 4v5h-5",
    "eye" to "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z M12 12 m-3 0 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
    "ruler" to "M2 8h20v8H2z M6 8v3M10 8v4M14 8v3M18 8v4",
    "sun" to "M12 12 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0 M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M19 5l-1.5 1.5M6.5 17.5 5 19",
    "arrows" to "M8 7 4 11l4 4M4 11h16M16 17l4-4-4-4",
)

/**
 * Renders one [ICON_PATHS] glyph stroked (or filled) with [color]. The path is authored in a
 * 24-unit viewBox, so we scale the canvas to the requested [size]; the stroke width scales with
 * it, matching the SVG's strokeWidth=2-in-24-units look.
 */
@Composable
fun KIcon(
    name: String,
    size: Dp = 20.dp,
    color: Color = K.text,
    stroke: Float = 2f,
    fill: Boolean = false,
) {
    val data = ICON_PATHS[name] ?: ICON_PATHS["bolt"]!!
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            if (fill) {
                drawPath(path, color)
            } else {
                drawPath(path, color, style = Stroke(width = stroke))
            }
        }
    }
}
