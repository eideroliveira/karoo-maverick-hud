/* ============================================================
   HudPreview.kt — live Maverick glasses preview (420×150) and the
   editable lens (tap a slot to assign a field). Mirrors
   hudpreview.jsx / screens2.jsx EditableHud; two edge columns,
   centre kept clear, zone-tinted values.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import com.eider.karoomaverickhud.extension.FieldFormat
import com.eider.karoomaverickhud.extension.HudFontSize
import com.eider.karoomaverickhud.extension.NextClimb
import com.eider.karoomaverickhud.extension.cellsForRows
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eider.karoomaverickhud.settings.HudConfig
import kotlin.math.sin
import kotlin.random.Random

/** Demo value for a field: the string the lens shows + the numeric used for zone coloring. */
data class DemoVal(val display: String, val numeric: Double?)

/** The centre overlay a preview scene draws over the page's clear centre (mirrors HudScreen). */
enum class PreviewOverlay { NONE, RADAR, TRAJECTORY, WORKOUT }

/**
 * One scene the hub's live preview cycles through: a data page ([fields]) plus an optional centre
 * [overlay]. Beyond the rider's numbered pages, the preview tours the route layouts the glasses raise
 * on a ride — the on-climb page, and the next-climb radar / heading-up trajectory overlays (which the
 * glasses draw over the clear centre of whatever page is showing), so the rider sees each beforehand.
 */
data class PreviewScene(
    /** Short caption shown under the lens (e.g. "PAGE 1", "CLIMB", "NEXT-CLIMB RADAR"). */
    val label: String,
    /** The page's field ids, rendered as the two edge columns of cells. */
    val fields: List<String>,
    /** The centre overlay drawn over the cells, if any. */
    val overlay: PreviewOverlay = PreviewOverlay.NONE,
)

/**
 * The scenes the hub preview cycles through: the rider's numbered pages, then the on-climb auto-page
 * and — when enabled — the next-climb radar and the trajectory map. Radar/trajectory are centre
 * overlays the glasses draw over the current page, so the preview shows them over the first page,
 * exactly as they appear mid-ride. A faithful tour of every layout the rider can meet.
 */
fun previewScenes(cfg: HudConfig): List<PreviewScene> {
    val cap = cellsForRows(cfg.rows)
    val base = cfg.pages.firstOrNull()?.take(cap) ?: cfg.climbPage.take(cap)
    return buildList {
        cfg.pages.forEachIndexed { i, p -> add(PreviewScene("PAGE ${i + 1}", p.take(cap))) }
        add(PreviewScene("CLIMB", cfg.climbPage.take(cap)))
        add(PreviewScene("WORKOUT OVERLAY", base, PreviewOverlay.WORKOUT))
        if (cfg.radarEnabled) add(PreviewScene("NEXT-CLIMB RADAR", base, PreviewOverlay.RADAR))
        if (cfg.trajectoryEnabled) add(PreviewScene("TRAJECTORY", base, PreviewOverlay.TRAJECTORY))
    }
}

/** Column index order (left, right) for a given field count — mirrors hudpreview.columnOrder. */
fun columnOrder(count: Int): Pair<List<Int>, List<Int>> = when (count.coerceAtMost(6)) {
    0, 1 -> listOf(0) to emptyList()
    2 -> listOf(0) to listOf(1)
    3 -> listOf(0, 2) to listOf(1)
    4 -> listOf(0, 2) to listOf(1, 3)
    5 -> listOf(0, 4, 2) to listOf(1, 3)
    else -> listOf(0, 4, 2) to listOf(1, 5, 3)
}

/** Read-only preview value sp for the chosen HUD font size — mirrors the glasses ~22 (stock) / 33 / 42 px faces. */
fun hudValueSp(size: HudFontSize): Float = when (size) {
    HudFontSize.SMALL -> 22f
    HudFontSize.MEDIUM -> 33f
    HudFontSize.LARGE -> 42f
}

/** Editor-slot value sp for the chosen size — a touch smaller, since slots are bordered tap targets. */
fun editValueSp(size: HudFontSize): Float = when (size) {
    HudFontSize.SMALL -> 18f
    HudFontSize.MEDIUM -> 27f
    HudFontSize.LARGE -> 33f
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
    // Value size mirrors the glasses faces, picked by the rider's HUD font size (~22/33/42 px) rather
    // than the field count. Only the label changes place. Labels render white and only the value
    // takes the zone color (icons stay dim grey), so the preview reads exactly like the glasses.
    val value: @Composable () -> Unit = {
        KText(demo?.display ?: "--", color = color, size = hudValueSp(cfg.hudFontSize).sp,
            weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
    }
    // With icons on, the icon replaces the unit, plus a short white differentiator tag for fields
    // that share an icon (e.g. "to top", "NP") — the original field that owns the icon shows it
    // alone (only fields with a real glasses icon; extension fields have none, so they keep text).
    val showIcon = cfg.showIcons && FieldFormat.iconFor(fieldId) != null
    val tag = if (showIcon) FieldFormat.iconTagFor(fieldId) else ""
    val labelOrIcon: @Composable () -> Unit = {
        if (showIcon) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KIcon(field?.icon ?: "bolt", 12.dp, Lens.icon, stroke = 2.4f)
                if (tag.isNotEmpty()) KText(tag, color = K.zWhite, size = 13.sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1, softWrap = false)
            }
        } else {
            KText(label, color = K.zWhite, size = 14.sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1, softWrap = false)
        }
    }
    if (big) {
        // ≤4-field pages: value on top, label/icon stacked just below.
        Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
            value()
            Row(verticalAlignment = Alignment.CenterVertically) { labelOrIcon() }
        }
    } else {
        // 5–6-field pages: value hugs the edge, label/icon beside it on the inboard side — the value
        // stays on the outer edge so the order flips per column (mirrors HudScreen's side layout).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val parts = listOf(value, labelOrIcon)
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
    scene: PreviewScene,
    values: Map<String, DemoVal>,
    pageIndex: Int,
    totalPages: Int,
    width: Dp = 448.dp,
    onTap: (() -> Unit)? = null,
) {
    // The lens is a FIXED size regardless of scene. The glasses screen doesn't resize per page, and
    // the hub preview auto-cycles scenes with different field counts (and the drawn trajectory map) —
    // a height that flipped between them would make the box jump on every cycle. Sized tall enough
    // for the worst case (three rows of the side-by-side 42sp value) plus room for the page dots.
    val lensH = 208.dp
    val colH = 172.dp
    LensBox(width, lensHeight = lensH) {
        // The page cells always draw the two edge columns; radar / trajectory are centre overlays the
        // glasses paint over the clear middle, so they sit on top of the cells (as on the Maverick).
        PageCells(cfg, scene.fields, values, colH, showCenterDot = scene.overlay == PreviewOverlay.NONE)
        when (scene.overlay) {
            PreviewOverlay.RADAR -> RadarOverlayCells(cfg)
            PreviewOverlay.TRAJECTORY -> TrajectoryOverlayCells()
            PreviewOverlay.WORKOUT -> WorkoutOverlayCells(cfg)
            PreviewOverlay.NONE -> {}
        }
        if (totalPages > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(totalPages) { i ->
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == pageIndex) Lens.accent else Color(0x40FFFFFF)))
                }
            }
        }
        // Transparent tap target on top, filling the lens — switches page on tap.
        if (onTap != null) {
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap))
        }
    }
}

/** A data page (numbered or climb): the centre fixation dot + the two edge columns of cells. */
@Composable
private fun BoxScope.PageCells(
    cfg: HudConfig,
    page: List<String>,
    values: Map<String, DemoVal>,
    colH: Dp,
    showCenterDot: Boolean = true,
) {
    val big = page.size <= 4
    val (left, right) = columnOrder(page.size)
    // centre fixation dot — suppressed when a centre overlay (radar / trajectory) takes the middle.
    if (showCenterDot) {
        Box(Modifier.align(Alignment.Center).size(6.dp).clip(RoundedCornerShape(3.dp))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(3.dp)))
    }
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
}

/**
 * The next-climb radar centre overlay, mirroring HudScreen.renderRadar: a cyan "NEXT CLIMB" title and
 * two centred readout lines (distance + ETA, then grade + length), the grade line tinted by climb
 * severity. A representative demo climb runs through the real [FieldFormat.radarOverlay] so the
 * strings (and imperial units) read exactly like the glasses.
 */
@Composable
private fun BoxScope.RadarOverlayCells(cfg: HudConfig) {
    val demo = NextClimb(distanceToStart = 800.0, etaSeconds = 72.0, grade = 8.0, length = 1200.0, totalElevation = 96.0)
    val radar = FieldFormat.radarOverlay(demo, cfg.imperial) ?: return
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KText("NEXT CLIMB", color = K.zCyan, size = 16.sp, weight = FontWeight.Bold,
            family = CondFamily, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
        KText("↔ ${radar.distance}   ${radar.eta}", color = K.zWhite, size = 20.sp,
            weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false)
        KText("${radar.grade}   ↔ ${radar.length}", color = radar.gradeColor.toComposeColor(),
            size = 20.sp, weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false)
    }
}

/**
 * The heading-up trajectory centre overlay, mirroring HudScreen.renderTrajectory: the rider sits at
 * bottom-centre looking up, with a cyan road bending ahead, a white "you" triangle at the origin, and
 * a centred footer carrying grade + zoom. The road is a demo S-curve so the preview conveys the
 * layout without a live route — the on-glasses map projects the real polyline.
 */
@Composable
private fun BoxScope.TrajectoryOverlayCells() {
    Canvas(Modifier.matchParentSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val bottomY = h * 0.89f  // TRAJ_BOTTOM_Y (134/150)
        val topY = h * 0.107f    // TRAJ_TOP_Y (16/150)
        val span = bottomY - topY
        val amp = w * 0.16f
        val n = 26
        val road = Path()
        for (i in 0..n) {
            val f = i / n.toFloat()
            val y = bottomY - f * span
            // gentle S-bend that grows with distance for a sense of perspective — reads as a road
            val bend = sin(f * 3.6f) * (0.4f + 0.6f * f)
            val x = cx + bend * amp
            if (i == 0) road.moveTo(x, y) else road.lineTo(x, y)
        }
        drawPath(road, color = K.zCyan,
            style = Stroke(width = w * 0.013f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // "You" — a small upward triangle at the bottom-centre origin.
        val marker = Path().apply {
            moveTo(cx - w * 0.012f, bottomY)
            lineTo(cx + w * 0.012f, bottomY)
            lineTo(cx, bottomY - h * 0.075f)
            close()
        }
        drawPath(marker, color = K.zWhite)
    }
    // Centred footer under the marker: current grade + zoom, exactly as the glasses render it.
    KText("-6 %   200 m", color = K.zWhite, size = 14.sp, weight = FontWeight.SemiBold,
        family = CondFamily, maxLines = 1, softWrap = false,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
}

/**
 * The mid-workout centre overlay, mirroring HudScreen.renderWorkout: a cyan "INTERVAL" caption, the
 * interval countdown in the big value face, and the zone-coloured "AVG n" / "NP n" pair astride the
 * centre line. Demo streams run through the real [FieldFormat.workoutOverlay] so strings, the 1 s
 * countdown lead and the zone colours read exactly like the glasses — and the demo countdown sits
 * inside the 5 s blink window, so the preview shows the flash the rider will see at an interval's end.
 */
@Composable
private fun BoxScope.WorkoutOverlayCells(cfg: HudConfig) {
    val overlay = remember(cfg) {
        val zones = com.eider.karoomaverickhud.extension.ZoneConfig(cfg.ftp, cfg.maxHr, cfg.idealCadence, cfg.ftpZones, cfg.hrZones)
        fun streaming(id: String, value: Double) = io.hammerhead.karooext.models.StreamState.Streaming(
            io.hammerhead.karooext.models.DataPoint(dataTypeId = id, values = mapOf(id to value)),
        )
        FieldFormat.workoutOverlay(
            remainState = io.hammerhead.karooext.models.StreamState.Streaming(
                io.hammerhead.karooext.models.DataPoint(
                    dataTypeId = io.hammerhead.karooext.models.DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION,
                    values = mapOf(io.hammerhead.karooext.models.DataType.Field.WORKOUT_TIME_TO_STEP_FINISH to 5_800.0), // → "0:04", blinking
                ),
            ),
            avgPowerState = streaming(io.hammerhead.karooext.models.DataType.Type.AVERAGE_POWER, cfg.ftp * 0.86),
            npState = streaming(io.hammerhead.karooext.models.DataType.Type.NORMALIZED_POWER, cfg.ftp * 0.92),
            zones = zones,
        )
    }
    // The glasses flash the countdown over the interval's last 5 s (500 ms half-period) — mirror it.
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            blinkOn = !blinkOn
        }
    }
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KText("INTERVAL", color = K.zCyan, size = 16.sp, weight = FontWeight.Bold,
            family = CondFamily, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
        KText(if (!overlay.blink || blinkOn) overlay.remaining else " ", color = K.zWhite, size = 33.sp,
            weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            KText("AVG ${overlay.avg.value}", color = overlay.avg.color.toComposeColor(), size = 20.sp,
                weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false)
            KText("NP ${overlay.np.value}", color = overlay.np.color.toComposeColor(), size = 20.sp,
                weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false)
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
    val color = if (field != null) cellColor(field, demo, cfg) else Lens.faint
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        field != null -> if (sel) Lens.accent else Color.Transparent
        sel -> Lens.accent
        else -> Color(0x38FFFFFF)
    }
    val bg = if (sel) Lens.accentDim else if (field != null) Color.Transparent else Color(0x08FFFFFF)
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
            // Value size echoes the glasses faces, picked by the rider's HUD font size (a touch
            // smaller than the read-only lens, since slots are bordered tap targets); only its label
            // changes place. With icons on, the icon replaces the unit, plus a short white tag for
            // fields that share an icon (the original owner shows it alone; fields without a real
            // glasses icon keep their text). Labels render white, the value keeps the zone color —
            // same split as the glasses (HudScreen) and the LensCell preview.
            val value: @Composable () -> Unit = {
                KText(demo?.display ?: "--", color = color, size = editValueSp(cfg.hudFontSize).sp,
                    weight = FontWeight.Bold, family = CondFamily, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
            }
            val showIcon = cfg.showIcons && fieldId?.let { FieldFormat.iconFor(it) } != null
            val tag = if (showIcon) fieldId?.let { FieldFormat.iconTagFor(it) }.orEmpty() else ""
            val labelOrIcon: @Composable () -> Unit = {
                if (showIcon) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        KIcon(field.icon, 8.dp, Lens.icon, stroke = 2.4f)
                        if (tag.isNotEmpty()) KText(tag, color = K.zWhite, size = 8.sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1, softWrap = false)
                    }
                } else {
                    KText(field.unit.ifEmpty { field.label }, color = K.zWhite, size = 9.sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1, softWrap = false)
                }
            }
            if (big) {
                // ≤4-field pages: value on top, label/icon stacked just below.
                Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
                    value()
                    Row(verticalAlignment = Alignment.CenterVertically) { labelOrIcon() }
                }
            } else {
                // 5–6-field pages: value and label/icon side-by-side, value on the outer edge.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    val parts = listOf(value, labelOrIcon)
                    (if (right) parts.asReversed() else parts).forEach { it() }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                KIcon("plus", 18.dp, if (isAdd) Lens.accent else Lens.faint)
                KText(if (isAdd) "Add" else "Empty", color = if (isAdd) Lens.accent else Lens.faint,
                    size = 10.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}
