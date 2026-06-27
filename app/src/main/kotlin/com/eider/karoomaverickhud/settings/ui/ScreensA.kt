/* ============================================================
   ScreensA.kt — Hub, Rider Profile, Gear.
   Mirrors screens1.jsx (Hub/Profile) + screens2.jsx (Gear).
   ============================================================ */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.eider.karoomaverickhud.settings.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eider.karoomaverickhud.extension.DEFAULT_HR_ZONES
import com.eider.karoomaverickhud.extension.DEFAULT_POWER_ZONES
import com.eider.karoomaverickhud.extension.ZoneBand
import com.eider.karoomaverickhud.settings.GearConfig
import com.eider.karoomaverickhud.settings.HudConfig
import com.eider.karoomaverickhud.settings.HudPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Vertical scroll container with the app background. */
@Composable
fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState()),
        content = content,
    )
}

/** Full-bleed card (no side margins — reclaims width on the narrow panel). */
@Composable
fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    KCard(content = content)
}

/* ---------------- HUB ---------------- */
@Composable
fun HubScreen(
    cfg: HudConfig,
    connected: Boolean,
    battery: Int?,
    values: Map<String, DemoVal>,
    previewPage: Int,
    nav: (String) -> Unit,
    onPreviewTap: () -> Unit = {},
) {
    val totalFields = cfg.pages.sumOf { it.size }
    val dt = matchingDrivetrain(cfg.gear.drivetrainId, cfg.gear.front, cfg.gear.rear)
    // The preview tours every layout the glasses can raise: the numbered pages, the on-climb page,
    // the next-climb radar and the trajectory map (the latter two when enabled).
    val scenes = previewScenes(cfg)
    val sceneIndex = previewPage.coerceIn(0, (scenes.size - 1).coerceAtLeast(0))
    val scene = scenes.getOrNull(sceneIndex) ?: PreviewScene("PAGE 1", emptyList())

    ScreenScroll {
        // hero
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 11.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                KPill(
                    if (connected) "Glasses linked" else "Disconnected",
                    color = if (connected) K.good else K.text3, bg = K.surface2, dot = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KIcon("battery", 17.dp, K.text2)
                    KText(battery?.let { "$it%" } ?: "—", color = K.text2, size = 15.sp, weight = FontWeight.SemiBold, family = CondFamily)
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassesPreview(cfg, scene, values, sceneIndex, scenes.size, width = 448.dp,
                    onTap = if (scenes.size > 1) onPreviewTap else null)
            }
            KText("${scene.label} · TAP TO SWITCH", color = K.text3, size = 13.sp, letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        }

        KSectionLabel("Configuration")
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HubCard("rider", K.cRed, "Rider Profile",
                "FTP ${cfg.ftp} W · Max HR ${cfg.maxHr} · Cadence ${cfg.idealCadence}", onClick = { nav("profile") }) {
                Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    POWER_ZONE_UI_COLORS.forEach {
                        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(it))
                    }
                }
            }
            HubCard("gear", K.cOrange, "Gear",
                "${dt?.let { it.brand + " " + it.name } ?: "Custom"} · ${cfg.gear.front.size}×${cfg.gear.rear.size}",
                status = if (cfg.gear.source == "auto" && dt?.electronic == true) {
                    { KPill("AUTO", color = K.accent, bg = K.accentDim, height = 21.dp) }
                } else null,
                onClick = { nav("gear") })
            HubCard("pages", K.accent, "Data Pages",
                "${cfg.pages.size} pages · $totalFields fields · ${cfg.rows} rows", onClick = { nav("pages") })
            HubCard("glasses", K.cBlue, "Glasses",
                if (connected) "${cfg.maverickDeviceName ?: "Maverick"} · ${battery ?: "—"}% · brightness" else "No glasses connected",
                status = { KStatusDot(if (connected) K.good else K.text3) },
                onClick = { nav("glasses") })
            HubCard("ruler", K.cPurple, "Display & Units",
                "${if (cfg.imperial) "Imperial (mph, mi)" else "Metric (km/h, km)"} · ${pageModeLabel(cfg)}",
                onClick = { nav("display") })
        }
    }
}

private fun pageModeLabel(cfg: HudConfig): String = when (cfg.pageMode) {
    com.eider.karoomaverickhud.settings.PageMode.AUTO -> "Auto-cycle"
    com.eider.karoomaverickhud.settings.PageMode.MANUAL -> "Manual"
}

@Composable
fun HubCard(
    icon: String,
    accent: Color,
    title: String,
    summary: String,
    onClick: () -> Unit,
    status: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(K.surface)
            .border(1.dp, K.line, RoundedCornerShape(16.dp))
            .clickable(src, null, onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) { KIcon(icon, 23.dp, accent) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KText(title, color = K.text, size = 21.sp, weight = FontWeight.Bold, family = CondFamily, maxLines = 1)
                status?.invoke()
            }
            KText(summary, color = K.text2, size = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            extra?.invoke()
        }
        KIcon("chevron", 20.dp, K.text3)
    }
}

/* ---------------- RIDER PROFILE ---------------- */
@Composable
fun ProfileScreen(cfg: HudConfig, ctx: Context, scope: CoroutineScope) {
    // Local zone state for smooth dragging; persisted debounced.
    var ftpZones by remember { mutableStateOf(cfg.ftpZones) }
    var hrZones by remember { mutableStateOf(cfg.hrZones) }
    LaunchedEffect(ftpZones) { delay(300); HudPreferences.setFtpZones(ctx, ftpZones) }
    LaunchedEffect(hrZones) { delay(300); HudPreferences.setHrZones(ctx, hrZones) }

    ScreenScroll {
        // POWER
        KSectionLabel("Power · FTP")
        CardBlock {
            KRow {
                KIconChip("power", color = K.cOrange)
                Column(Modifier.weight(1f)) {
                    KText("Functional Threshold", color = K.text, size = 19.sp, weight = FontWeight.Medium)
                    KText("Your 1-hour max power. Sets the power zones below.", color = K.text2, size = 15.sp, lineHeight = 20.sp)
                }
                KStepper(cfg.ftp, { scope.launch { HudPreferences.setFtp(ctx, it) } }, min = 80, max = 500, step = 5, unit = "watts")
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    KText("POWER ZONES", color = K.text2, size = 14.sp, weight = FontWeight.SemiBold)
                    KText("% of FTP", color = K.text3, size = 13.sp)
                }
                ZoneRowsEditor(ftpZones, cfg.ftp, "W", POWER_ZONE_UI_COLORS) { ftpZones = it }
                KButton("Reset to default zones", icon = "refresh", variant = KBtnVariant.Ghost, height = 42.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { ftpZones = DEFAULT_POWER_ZONES }
            }
        }

        // HEART RATE
        KSectionLabel("Heart Rate")
        CardBlock {
            KRow {
                KIconChip("heart", color = K.cRed)
                Column(Modifier.weight(1f)) {
                    KText("Max Heart Rate", color = K.text, size = 19.sp, weight = FontWeight.Medium)
                    KText("Your highest sustained bpm. Sets the HR zones below.", color = K.text2, size = 15.sp, lineHeight = 20.sp)
                }
                KStepper(cfg.maxHr, { scope.launch { HudPreferences.setMaxHr(ctx, it) } }, min = 120, max = 220, step = 1, unit = "bpm")
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    KText("HR ZONES", color = K.text2, size = 14.sp, weight = FontWeight.SemiBold)
                    KText("% of max", color = K.text3, size = 13.sp)
                }
                ZoneRowsEditor(hrZones, cfg.maxHr, "bpm", HR_ZONE_UI_COLORS) { hrZones = it }
                KButton("Reset to default zones", icon = "refresh", variant = KBtnVariant.Ghost, height = 42.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { hrZones = DEFAULT_HR_ZONES }
            }
        }

        // CADENCE
        KSectionLabel("Cadence")
        CardBlock {
            KRow(last = true) {
                KIconChip("cadence", color = K.cGreen)
                Column(Modifier.weight(1f)) {
                    KText("Ideal Cadence", color = K.text, size = 19.sp, weight = FontWeight.Medium)
                    KText("Target rpm. The HUD drifts yellow, then orange, as you move off it.", color = K.text2, size = 15.sp, lineHeight = 20.sp)
                }
                KStepper(cfg.idealCadence, { scope.launch { HudPreferences.setIdealCadence(ctx, it) } }, min = 50, max = 120, step = 1, unit = "rpm")
            }
            Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp)) { CadenceScale(cfg.idealCadence) }
        }

        KText(
            "Zones drive the live colouring on the glasses — cyan when soft-pedalling, white when easy, then green, yellow, orange, red and purple as effort rises.",
            color = K.text3, size = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 30.dp),
        )
    }
}

/* ---------------- GEAR ---------------- */
@Composable
fun GearScreen(cfg: HudConfig, ctx: Context, scope: CoroutineScope) {
    val gear = cfg.gear
    val dt = matchingDrivetrain(gear.drivetrainId, gear.front, gear.rear)
    var scanning by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showCassettes by remember { mutableStateOf(false) }
    fun save(g: GearConfig) { scope.launch { HudPreferences.setGear(ctx, g) } }

    LaunchedEffect(scanning) {
        if (scanning) {
            delay(1600)
            val found = DRIVETRAINS[(0..3).random()]
            save(gear.copy(drivetrainId = found.id, front = found.front, rear = found.rear))
            scanning = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        ScreenScroll {
            KSectionLabel("Source")
            CardBlock {
                Column(Modifier.padding(14.dp)) {
                    KSegmented(listOf("auto" to "Auto-detect", "manual" to "Manual"), gear.source) { save(gear.copy(source = it)) }
                    KText(
                        if (gear.source == "auto")
                            "Read live from your connected SRAM AXS or Shimano Di2 during the ride — chainrings, cassette and current gear come straight from the sensor."
                        else "Enter your drivetrain by hand for mechanical groupsets that don't report gearing.",
                        color = K.text3, size = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 11.dp),
                    )
                }
            }

            KSectionLabel(if (gear.source == "auto") "Detected drivetrain" else "Drivetrain")
            CardBlock {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).background(K.surface3), contentAlignment = Alignment.Center) {
                        KIcon("gear", 26.dp, K.cOrange)
                    }
                    Column(Modifier.weight(1f)) {
                        KText(dt?.let { "${it.brand} ${it.name}" } ?: "Custom drivetrain", color = K.text, size = 22.sp, weight = FontWeight.Bold, family = CondFamily)
                        Row {
                            KText("${gear.front.size}×${gear.rear.size}", color = K.text2, size = 15.sp)
                            if (dt?.electronic == true) KText(" · Electronic", color = K.accent, size = 15.sp)
                        }
                    }
                    if (gear.source == "auto" && dt?.electronic == true) KPill("Live", color = K.good, bg = K.surface2, dot = true)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(K.line))
                // chainrings + cassette
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        KText("CHAINRINGS", color = K.text3, size = 13.sp, letterSpacing = 0.7.sp, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            gear.front.forEach { t ->
                                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(K.surface3).padding(horizontal = 11.dp, vertical = 3.dp)) {
                                    KText("${t}T", color = K.text, size = 21.sp, weight = FontWeight.Bold, family = CondFamily)
                                }
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).height(64.dp).background(K.line))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        KText("CASSETTE", color = K.text3, size = 13.sp, letterSpacing = 0.7.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            KText("${gear.rear.first()}–${gear.rear.last()}T", color = K.text, size = 21.sp, weight = FontWeight.Bold, family = CondFamily)
                            KText(" · ${gear.rear.size}sp", color = K.text3, size = 15.sp)
                        }
                    }
                }
                if (gear.source == "auto") {
                    Box(Modifier.padding(14.dp)) {
                        KButton(if (scanning) "Scanning sensors…" else "Re-scan sensors", icon = if (scanning) "refresh" else "search",
                            variant = KBtnVariant.Ghost, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { scanning = true }
                    }
                } else {
                    ManualGear(gear, onSave = { save(it) }, onPresets = { showPresets = true }, onChooseCassette = { showCassettes = true })
                }
            }

            KSectionLabel("Gear in the HUD")
            CardBlock {
                KRow(last = !gear.showField) {
                    KIconChip("gear", color = K.zOrange)
                    Column(Modifier.weight(1f)) {
                        KText("Show gear field", color = K.text, size = 19.sp, weight = FontWeight.Medium)
                        KText("Add a GEAR readout to your data pages", color = K.text2, size = 15.sp)
                    }
                    KSwitch(gear.showField) { save(gear.copy(showField = it)) }
                }
                if (gear.showField) {
                    Column(Modifier.padding(14.dp)) {
                        KText("READOUT STYLE", color = K.text2, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 9.dp))
                        val displayValue = if (gear.display == "gear") "teeth" else gear.display // migrate legacy
                        KSegmented(listOf("teeth" to "Teeth", "ratio" to "Ratio", "inches" to "Inches"), displayValue) { save(gear.copy(display = it)) }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp))
                                .background(K.bg).border(1.dp, K.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            KIcon("gear", 22.dp, K.text)
                            Column {
                                KText(gearExample(gear), color = K.text, size = 34.sp, weight = FontWeight.Bold, family = CondFamily)
                                KText("example · ${gearStyleLabel(gear.display)}", color = K.text3, size = 13.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }

        if (showPresets) {
            KBottomSheet("Drivetrain presets", onClose = { showPresets = false }) {
                Column(Modifier.padding(bottom = 20.dp)) {
                    DRIVETRAINS.forEach { d ->
                        KRow(onClick = { save(gear.copy(drivetrainId = d.id, front = d.front, rear = d.rear)); showPresets = false }) {
                            KIconChip("gear", color = K.cOrange)
                            Column(Modifier.weight(1f)) {
                                KText("${d.brand} ${d.name}", color = K.text, size = 19.sp, weight = FontWeight.Medium)
                                KText("${d.front.joinToString("/")}T · ${d.rear.first()}–${d.rear.last()}T · ${d.speeds}sp${if (d.electronic) " · electronic" else ""}",
                                    color = K.text2, size = 15.sp)
                            }
                        }
                    }
                }
            }
        }

        if (showCassettes) {
            KBottomSheet("Choose cassette", onClose = { showCassettes = false }) {
                Column(Modifier.padding(bottom = 20.dp)) {
                    CASSETTES.forEach { c ->
                        val selected = c.cogs == gear.rear
                        KRow(onClick = { save(gear.copy(rear = c.cogs)); showCassettes = false }) {
                            KIconChip("gear", color = if (selected) K.accent else K.cOrange)
                            Column(Modifier.weight(1f)) {
                                KText(c.label, color = K.text, size = 19.sp, weight = FontWeight.Medium)
                                KText(c.cogs.joinToString("·"), color = K.text2, size = 14.sp, maxLines = 1)
                            }
                            if (selected) KIcon("check", 20.dp, K.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualGear(gear: GearConfig, onSave: (GearConfig) -> Unit, onPresets: () -> Unit, onChooseCassette: () -> Unit) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KButton("Start from a preset", icon = "search", variant = KBtnVariant.Ghost, modifier = Modifier.fillMaxWidth(), onClick = onPresets)
        // Chainrings — one ring per row.
        KText("CHAINRINGS", color = K.text2, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
        gear.front.forEachIndexed { i, t ->
            GearValueRow(if (i == 0) "Inner ring" else "Outer ring", t, 24, 60, "T") { v ->
                onSave(gear.copy(front = gear.front.toMutableList().also { it[i] = v }))
            }
        }
        // Cassette — pick a real one from the datasheet (exact cog teeth, not evenly spaced).
        KText("CASSETTE", color = K.text2, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.surface2)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onChooseCassette)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                KText("${gear.rear.first()}–${gear.rear.last()}T · ${gear.rear.size}sp", color = K.text, size = 19.sp, weight = FontWeight.Medium)
                KText(gear.rear.joinToString("·"), color = K.text3, size = 14.sp, maxLines = 1)
            }
            KIcon("chevron", 18.dp, K.text3)
        }
    }
}

/** A labelled value row: title on the left, stepper on the right. */
@Composable
private fun GearValueRow(label: String, value: Int, mn: Int, mx: Int, unit: String, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.surface2).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        KText(label, color = K.text, size = 18.sp, weight = FontWeight.Medium)
        KStepper(value, onChange, min = mn, max = mx, step = 1, unit = unit.ifEmpty { null }, valueWidth = 56.dp)
    }
}

/* Example readout in the rider's chosen style (big ring vs a mid cog). */
private fun gearExample(gear: GearConfig): String {
    val fT = gear.front.maxOrNull() ?: return "--"
    val rMid = gear.rear.getOrNull(gear.rear.size / 3) ?: return "--"
    val ratio = fT.toFloat() / rMid
    return when (gear.display) {
        "ratio" -> "%.2f".format(ratio)
        "inches" -> "${(ratio * 27).toInt()}″"
        else -> "$fT/$rMid" // teeth (and legacy "gear")
    }
}

private fun gearStyleLabel(display: String): String = when (display) {
    "ratio" -> "gear ratio"
    "inches" -> "gear inches"
    else -> "front · rear teeth"
}
