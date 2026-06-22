/* ============================================================
   HudPreview.kt — live Maverick glasses preview (420×150) and the
   editable lens (tap a slot to assign a field). Mirrors
   hudpreview.jsx / screens2.jsx EditableHud; two edge columns,
   centre kept clear, zone-tinted values.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import com.eider.karoomaverickhud.extension.FieldFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eider.karoomaverickhud.settings.HudConfig
import kotlin.random.Random

/** Demo value for a field: the string the lens shows + the numeric used for zone coloring. */
data class DemoVal(val display: String, val numeric: Double?)

/** Column index order (left, right) for a given field count — mirrors hudpreview.columnOrder. */
fun columnOrder(count: Int): Pair<List<Int>, List<Int>> = when (count.coerceAtMost(6)) {
    0, 1 -> listOf(0) to emptyList()
    2 -> listOf(0) to listOf(1)
    3 -> listOf(0, 2) to listOf(1)
    4 -> listOf(0, 2) to listOf(1, 3)
    5 -> listOf(0, 4, 2) to listOf(1, 3)
    else -> listOf(0, 4, 2) to listOf(1, 5, 3)
}

private val LENS_BG = Brush.radialGradient(
    colors = listOf(Color(0xFF0C1A17), Color(0xFF060807), Color(0xFF000000)),
    center = Offset(210f, 60f), radius = 380f,
)

/** Rolling demo values for every catalog field, jittered every 1.5 s so the preview feels alive. */
@Composable
fun rememberDemoValues(): Map<String, DemoVal> {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            tick++
        }
    }
    return remember(tick) {
        com.eider.karoomaverickhud.extension.FIELD_SPECS.associate { spec -> spec.id to demoFor(spec) }
    }
}

/** A plausible demo value for a field spec, by kind (with a little jitter on the live ones). */
private fun demoFor(spec: com.eider.karoomaverickhud.extension.FieldSpec): DemoVal {
    fun rnd(base: Int, span: Int) = (base + Random.nextInt(span + 1)).toDouble()
    return when (spec.kind) {
        com.eider.karoomaverickhud.extension.FieldKind.POWER -> {
            val w = if (spec.suffix == "max") rnd(420, 120) else rnd(210, 80)
            DemoVal(w.toInt().toString(), w)
        }
        com.eider.karoomaverickhud.extension.FieldKind.HR -> {
            val v = if (spec.suffix == "max") rnd(178, 8) else rnd(150, 20)
            DemoVal(v.toInt().toString(), v)
        }
        com.eider.karoomaverickhud.extension.FieldKind.CADENCE -> {
            val v = rnd(86, 10); DemoVal(v.toInt().toString(), v)
        }
        com.eider.karoomaverickhud.extension.FieldKind.SPEED -> {
            val v = if (spec.suffix == "max") 48.0 else 30.0 + Random.nextDouble() * 8.0
            DemoVal("%.1f".format(v), v)
        }
        com.eider.karoomaverickhud.extension.FieldKind.DISTANCE ->
            DemoVal(if (spec.suffix.isEmpty()) "42.1" else "8.4", null)
        com.eider.karoomaverickhud.extension.FieldKind.TIME -> DemoVal(
            when (spec.unit) { "lap" -> "12:30"; "last lap" -> "11:58"; else -> "1:24:30" }, null,
        )
        com.eider.karoomaverickhud.extension.FieldKind.INTERVAL_TIME -> DemoVal("1:23", null)
        com.eider.karoomaverickhud.extension.FieldKind.RATIO ->
            DemoVal(if (spec.unit == "VI") "1.05" else "0.85", null)
        com.eider.karoomaverickhud.extension.FieldKind.NUMBER -> DemoVal(
            when (spec.unit) { "W tgt" -> "250"; "rpm tgt" -> "90"; else -> "84" }, null,
        )
        com.eider.karoomaverickhud.extension.FieldKind.STEPS -> DemoVal("3/12", null)
        com.eider.karoomaverickhud.extension.FieldKind.BALANCE -> DemoVal("51/49", 51.0)
        com.eider.karoomaverickhud.extension.FieldKind.GEARS -> DemoVal("50/14", null)
        com.eider.karoomaverickhud.extension.FieldKind.DELTA_TIME -> DemoVal("-0:04", -4000.0)
        com.eider.karoomaverickhud.extension.FieldKind.GRADE -> DemoVal("7.2", 7.2)
        com.eider.karoomaverickhud.extension.FieldKind.CLIMB_STEPS -> DemoVal("2/3", null)
    }
}

private fun cellColor(field: UiField?, demo: DemoVal?, cfg: HudConfig): Color {
    if (field == null) return K.zWhite
    if (field.id == "TYPE_PEDAL_POWER_BALANCE_ID") return previewBalanceColor(demo?.numeric)
    return previewColorFor(field, demo?.numeric, cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
}

@Composable
private fun LensCell(fieldId: String?, values: Map<String, DemoVal>, cfg: HudConfig, right: Boolean, big: Boolean) {
    if (fieldId == null) {
        Spacer(Modifier.height(1.dp))
        return
    }
    val field = uiFieldFor(fieldId)
    val demo = values[fieldId]
    val color = cellColor(field, demo, cfg)
    val label = field?.unit?.takeIf { it.isNotEmpty() } ?: field?.label ?: ""
    Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        KText(demo?.display ?: "--", color = color, size = (if (big) 33 else 27).sp,
            weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // With icons on, the icon replaces the label outright (only fields with a real glasses
            // icon — extension fields have none, so they keep their text). Label/icon use dim grey
            // (K.text2); only the value takes the zone color — mirrors HudScreen so the preview reads
            // exactly like the glasses.
            val showIcon = cfg.showIcons && FieldFormat.iconFor(fieldId) != null
            val parts: List<@Composable () -> Unit> = buildList {
                if (showIcon) add { KIcon(field?.icon ?: "bolt", (if (big) 16 else 14).dp, K.text2, stroke = 2.4f) }
                else add { KText(label, color = K.text2, size = (if (big) 14 else 12).sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1, softWrap = false) }
            }
            (if (right) parts.asReversed() else parts).forEach { it() }
        }
    }
}

/**
 * The glasses lens at a chosen [width]; renders the 420×150 layout scaled to fit. Shows the given
 * [page]'s fields in two edge columns with zone-tinted values, plus page dots.
 */
/**
 * Renders the lens at a fixed 420×150 design-unit canvas mapped exactly onto [width], by
 * overriding the local density so 1 design unit == width/420 of real width and fontScale == 1.
 * This makes the glasses mock pixel-accurate and immune to the device's density / system font
 * scale (a HUD preview must look the same everywhere, unlike the surrounding chrome).
 */
@Composable
private fun LensBox(width: Dp, lensHeight: Dp = 150.dp, content: @Composable BoxScope.() -> Unit) {
    val base = LocalDensity.current.density
    val target = base * (width.value / 420f)
    CompositionLocalProvider(LocalDensity provides Density(target, 1f)) {
        Box(
            Modifier
                .size(420.dp, lensHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(LENS_BG)
                .border(1.dp, Color(0xFF20262B), RoundedCornerShape(18.dp)),
            content = content,
        )
    }
}

@Composable
fun GlassesPreview(
    cfg: HudConfig,
    page: List<String>,
    values: Map<String, DemoVal>,
    pageIndex: Int,
    totalPages: Int,
    width: Dp = 448.dp,
    onTap: (() -> Unit)? = null,
) {
    val big = page.size <= 4
    // A 3-row page (5–6 fields) needs a taller lens or the bottom row's label clips at the edge.
    val lensH = if (big) 150.dp else 184.dp
    val colH = if (big) 126.dp else 160.dp
    val (left, right) = columnOrder(page.size)
    LensBox(width, lensHeight = lensH) {
        // centre fixation dot
        Box(Modifier.align(Alignment.Center).size(6.dp).clip(RoundedCornerShape(3.dp))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(3.dp)))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.height(colH)) {
                left.forEach { i -> LensCell(page.getOrNull(i), values, cfg, right = false, big = big) }
            }
            Column(verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End,
                modifier = Modifier.height(colH)) {
                right.forEach { i -> LensCell(page.getOrNull(i), values, cfg, right = true, big = big) }
            }
        }
        if (totalPages > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(totalPages) { i ->
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == pageIndex) K.accent else Color(0x40FFFFFF)))
                }
            }
        }
        // Transparent tap target on top, filling the lens — switches page on tap.
        if (onTap != null) {
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap))
        }
    }
}

/**
 * Editable lens: every slot up to [slotCount] is a tappable target. Filled slots show the field;
 * the first empty slot is an "Add" affordance, later empties read "Empty". [selected] highlights
 * the active slot. Mirrors screens2.jsx EditableHud.
 */
@Composable
fun EditableHud(
    page: List<String>,
    slotCount: Int,
    cfg: HudConfig,
    values: Map<String, DemoVal>,
    selected: Int,
    onSlot: (Int) -> Unit,
    width: Dp = 452.dp,
) {
    val big = slotCount <= 4
    val (left, right) = columnOrder(slotCount)
    // The editor's slots are bordered tap targets (taller than the glasses cells), so the editor
    // lens gets extra height — otherwise the bottom row's label clips at the lens edge.
    val lensH = if (big) 150.dp else 182.dp
    val colH = if (big) 134.dp else 166.dp
    LensBox(width, lensHeight = lensH) {
        Box(Modifier.align(Alignment.Center).size(6.dp).clip(RoundedCornerShape(3.dp))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(3.dp)))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.height(colH)) {
                left.forEach { i -> EditSlot(i, page, cfg, values, selected, big, onSlot) }
            }
            Column(verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End,
                modifier = Modifier.height(colH)) {
                right.forEach { i -> EditSlot(i, page, cfg, values, selected, big, onSlot, right = true) }
            }
        }
    }
}

@Composable
private fun EditSlot(
    i: Int,
    page: List<String>,
    cfg: HudConfig,
    values: Map<String, DemoVal>,
    selected: Int,
    big: Boolean,
    onSlot: (Int) -> Unit,
    right: Boolean = false,
) {
    val fieldId = page.getOrNull(i)
    val field = fieldId?.let { uiFieldFor(it) }
    val isAdd = fieldId == null && i == page.size
    val sel = selected == i
    val demo = fieldId?.let { values[it] }
    val color = if (field != null) cellColor(field, demo, cfg) else K.text3
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        field != null -> if (sel) K.accent else Color.Transparent
        sel -> K.accent
        else -> Color(0x38FFFFFF)
    }
    val bg = if (sel) K.accentDim else if (field != null) Color.Transparent else Color(0x08FFFFFF)
    Box(
        Modifier
            // Fixed width — a min-width slot whose Add/Empty content fillMaxWidth would otherwise
            // expand to the whole lens and push the opposite column off-screen.
            .width(104.dp)
            .defaultMinSize(minHeight = (if (big) 56 else 46).dp)
            .clip(shape)
            .background(bg)
            .border(1.5.dp, borderColor, shape)
            .clickable(remember { MutableInteractionSource() }, null) { onSlot(i) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (field != null) {
            Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
                KText(demo?.display ?: "--", color = color, size = (if (big) 27 else 23).sp,
                    weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    // With icons on, the icon replaces the label outright (fields without a real glasses
                    // icon keep their text). Label/icon dim grey (K.text2); value keeps the zone color —
                    // same split as the glasses (HudScreen) and the read-only LensCell preview.
                    val showIcon = cfg.showIcons && fieldId?.let { FieldFormat.iconFor(it) } != null
                    val parts: List<@Composable () -> Unit> = buildList {
                        if (showIcon) add { KIcon(field.icon, 10.dp, K.text2, stroke = 2.4f) }
                        else add { KText(field.unit.ifEmpty { field.label }, color = K.text2, size = 9.sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1, softWrap = false) }
                    }
                    (if (right) parts.asReversed() else parts).forEach { it() }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                KIcon("plus", 18.dp, if (isAdd) K.accent else K.text3)
                KText(if (isAdd) "Add" else "Empty", color = if (isAdd) K.accent else K.text3,
                    size = 10.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}
