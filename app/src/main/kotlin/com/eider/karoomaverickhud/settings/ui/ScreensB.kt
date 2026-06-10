/* ============================================================
   ScreensB.kt — Data Pages (WYSIWYG editor + field picker),
   Glasses (pair & control), Display & Units.
   Mirrors screens2.jsx PagesScreen/FieldPicker + screens1.jsx
   GlassesScreen/DisplayScreen.
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.eider.karoomaverickhud.extension.cellsForRows
import io.hammerhead.karooext.models.DataType
import com.eider.karoomaverickhud.settings.HudConfig
import com.eider.karoomaverickhud.settings.HudPreferences
import com.eider.karoomaverickhud.settings.PageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val MAX_PAGES = 5

/** Sentinel "page index" for the workout page tab (stored separately from the numbered pages). */
private const val WORKOUT_TAB = -1

/** Return a copy of [list] with elements [a] and [b] swapped. */
private fun <T> swap(list: List<T>, a: Int, b: Int): List<T> =
    list.toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

/* ---------------- DATA PAGES ---------------- */
@Composable
fun PagesScreen(cfg: HudConfig, ctx: Context, scope: CoroutineScope, values: Map<String, DemoVal>) {
    val slotCount = cellsForRows(cfg.rows)
    var active by remember { mutableStateOf(0) } // numbered page index, or WORKOUT_TAB
    var picker by remember { mutableStateOf<Pair<Int, Int>?>(null) } // page, slot
    val pages = cfg.pages
    val isWorkout = active == WORKOUT_TAB
    val cur = if (isWorkout) WORKOUT_TAB else active.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val curPage = if (isWorkout) cfg.workoutPage.take(slotCount) else pages.getOrNull(cur) ?: emptyList()

    fun setPages(next: List<List<String>>) { scope.launch { HudPreferences.setPages(ctx, next) } }
    /** Persist an edit to whichever page is being edited — the workout page or a numbered one. */
    fun setCurPage(next: List<String>) {
        if (isWorkout) scope.launch { HudPreferences.setWorkoutPage(ctx, next) }
        else setPages(pages.mapIndexed { i, p -> if (i == cur) next else p })
    }

    Box(Modifier.fillMaxSize()) {
        ScreenScroll {
            KSectionLabel("Layout per page")
            CardBlock {
                Box(Modifier.padding(14.dp)) {
                    KSegmented(listOf(2 to "2 rows · 4 fields", 3 to "3 rows · 6 fields"), cfg.rows) { r ->
                        scope.launch {
                            HudPreferences.setRows(ctx, r)
                            HudPreferences.setPages(ctx, pages.map { it.take(r * 2) })
                            HudPreferences.setWorkoutPage(ctx, cfg.workoutPage.take(r * 2))
                        }
                    }
                }
            }

            KSectionLabel("Pages")
            FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEachIndexed { i, _ ->
                    val on = i == cur
                    Box(
                        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) K.accent else K.surface2)
                            .border(1.dp, if (on) K.accent else K.line2, RoundedCornerShape(10.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { active = i }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { KText("${i + 1}", color = if (on) K.onAccent else K.text2, size = 15.sp, weight = FontWeight.Bold, family = CondFamily) }
                }
                if (pages.size < MAX_PAGES) {
                    Box(
                        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(K.surface2)
                            .border(1.dp, K.line3, RoundedCornerShape(10.dp))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                setPages(pages + listOf(listOf(DataType.Type.POWER, DataType.Type.HEART_RATE)))
                                active = pages.size
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            KIcon("plus", 16.dp, K.accent); KText("Page", color = K.accent, size = 13.sp, weight = FontWeight.SemiBold)
                        }
                    }
                }
                // The workout page — always present, shown on the glasses only mid-workout.
                Box(
                    Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (isWorkout) K.accent else K.surface2)
                        .border(1.dp, if (isWorkout) K.accent else K.line2, RoundedCornerShape(10.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { active = WORKOUT_TAB }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        KIcon("bolt", 14.dp, if (isWorkout) K.onAccent else K.text2)
                        KText("Workout", color = if (isWorkout) K.onAccent else K.text2, size = 13.sp, weight = FontWeight.SemiBold)
                    }
                }
            }

            // editable lens
            Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                KCard {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            KText(if (isWorkout) "Workout page" else "Page ${cur + 1}", color = K.text, size = 17.sp, weight = FontWeight.Bold, family = CondFamily)
                            KText("${curPage.size}/$slotCount fields · tap a slot", color = K.text3, size = 12.sp)
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EditableHud(curPage, slotCount, cfg, values,
                                selected = if (picker?.first == cur) picker!!.second else -1,
                                onSlot = { picker = cur to it }, width = 408.dp)
                        }
                        if (isWorkout) {
                            KText(
                                "Appears by itself as the first page while a structured workout is loaded — it can't be removed or reordered.",
                                color = K.text3, size = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 14.dp),
                            )
                        } else Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KButton("", icon = "back", variant = KBtnVariant.Ghost, height = 44.dp, enabled = cur > 0,
                                modifier = Modifier.width(52.dp)) {
                                if (cur > 0) { setPages(swap(pages, cur, cur - 1)); active = cur - 1 }
                            }
                            KButton("", icon = "chevron", variant = KBtnVariant.Ghost, height = 44.dp, enabled = cur < pages.size - 1,
                                modifier = Modifier.width(52.dp)) {
                                if (cur < pages.size - 1) { setPages(swap(pages, cur, cur + 1)); active = cur + 1 }
                            }
                            KButton("Remove page", icon = "trash", variant = KBtnVariant.Danger, height = 44.dp, enabled = pages.size > 1,
                                modifier = Modifier.weight(1f)) {
                                if (pages.size > 1) { setPages(pages.filterIndexed { i, _ -> i != cur }); active = (cur - 1).coerceAtLeast(0) }
                            }
                        }
                    }
                }
            }

            KText("The centre of the lens stays clear for the road. Fields fill the edges from the corners in.",
                color = K.text3, size = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 30.dp))
        }

        picker?.let { (_, slot) ->
            val current = curPage.getOrNull(slot)
            FieldPickerSheet(
                current = current, used = curPage, onClose = { picker = null },
                onPick = { fid ->
                    setCurPage(curPage.toMutableList().also { if (slot < it.size) it[slot] = fid else it.add(fid) })
                    picker = null
                },
                onRemove = {
                    setCurPage(curPage.filterIndexed { j, _ -> j != slot })
                    picker = null
                },
            )
        }
    }
}

@Composable
private fun FieldPickerSheet(
    current: String?,
    used: List<String>,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    KBottomSheet("Choose field", onClose = onClose) {
        Column(Modifier.padding(bottom = 20.dp)) {
            if (current != null) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    KButton("Remove this field", icon = "trash", variant = KBtnVariant.Danger, modifier = Modifier.fillMaxWidth(), onClick = onRemove)
                }
            }
            UI_FIELD_GROUPS.forEach { (group, ids) ->
                KText(group.uppercase(), color = K.text3, size = 11.5.sp, weight = FontWeight.SemiBold,
                    letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp))
                ids.forEach { id ->
                    val f = UI_FIELDS[id] ?: return@forEach
                    val isUsed = id in used && id != current
                    val isCur = id == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .background(if (isCur) K.accentDim else K.surface)
                            .clickable(remember { MutableInteractionSource() }, null) { onPick(id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            KIcon(f.icon, 20.dp, pickerIconColor(f))
                        }
                        Column(Modifier.weight(1f)) {
                            Row {
                                KText(f.label, color = if (isUsed) K.text.copy(alpha = 0.5f) else K.text, size = 16.sp, weight = FontWeight.Medium)
                                if (f.unit.isNotEmpty()) KText(" · ${f.unit}", color = K.text3, size = 13.sp)
                            }
                            if (isUsed) KText("already on this page", color = K.text2, size = 12.5.sp)
                            else if (f.zone != null) KText("zone-coloured", color = K.text2, size = 12.5.sp)
                        }
                        if (isCur) KIcon("check", 20.dp, K.accent)
                    }
                }
            }
        }
    }
}

/* ---------------- GLASSES ---------------- */
@Composable
fun GlassesScreen(
    hasDevice: Boolean,
    connected: Boolean,
    deviceName: String,
    battery: Int?,
    displayOn: Boolean,
    brightness: Int,
    autoBrightness: Boolean,
    centerX: Int,
    centerY: Int,
    gpsBlocked: Boolean,
    onPair: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onDisplayOn: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    onAutoBrightness: (Boolean) -> Unit,
    onCenterX: (Int) -> Unit,
    onCenterY: (Int) -> Unit,
    onConfigure: () -> Unit,
    onAdjust: () -> Unit,
) {
    ScreenScroll {
        KSectionLabel("Connection")
        CardBlock {
            KRow(last = !connected) {
                KIconChip("glasses", color = if (connected) K.good else K.text3, iconSize = 21.dp)
                Column(Modifier.weight(1f)) {
                    KText(if (hasDevice) deviceName else "No glasses paired", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                    KText(
                        if (!hasDevice) "Tap pair to scan" else if (connected) "Connected over Bluetooth" else "Paired — not connected",
                        color = if (connected) K.good else K.text2, size = 12.5.sp,
                    )
                }
                if (connected) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KIcon("battery", 20.dp, K.text2)
                    KText("${battery ?: "—"}%", color = K.text2, size = 18.sp, weight = FontWeight.Bold, family = CondFamily)
                }
            }
            if (connected) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KButton("Disconnect", icon = "link", variant = KBtnVariant.Ghost, modifier = Modifier.weight(1f), onClick = onDisconnect)
                    KButton("Unpair", variant = KBtnVariant.Danger, modifier = Modifier.weight(1f), onClick = onUnpair)
                }
            } else if (hasDevice) {
                Box(Modifier.padding(14.dp)) {
                    KButton("Connect", icon = "bt", variant = KBtnVariant.Primary, modifier = Modifier.fillMaxWidth(), onClick = onConnect)
                }
            } else {
                Box(Modifier.padding(14.dp)) {
                    KButton("Pair Maverick", icon = "search", variant = KBtnVariant.Primary, modifier = Modifier.fillMaxWidth(), onClick = onPair)
                }
            }
        }

        if (gpsBlocked) {
            KText("Location is off, so scanning finds nothing. Start a ride to enable GPS, then tap the Glasses data field to pair.",
                color = K.bad, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
        }

        if (connected) {
            KSectionLabel("Glasses control")
            CardBlock {
                KRow {
                    KIconChip("eye"); Column(Modifier.weight(1f)) { KText("Display on", color = K.text, size = 16.sp, weight = FontWeight.Medium) }
                    KSwitch(displayOn, onDisplayOn)
                }
                KRow {
                    KIconChip("brightness")
                    Column(Modifier.weight(1f)) {
                        KText("Auto-brightness", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                        KText("Adjusts to ambient light", color = K.text2, size = 12.5.sp)
                    }
                    KSwitch(autoBrightness, onAutoBrightness)
                }
                KRow {
                    KIconChip("sun")
                    Column(Modifier.weight(1f)) {
                        KText("Brightness", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                        if (autoBrightness) KText("Auto on — adjusting turns it off", color = K.text2, size = 12.5.sp)
                    }
                    KStepper(brightness, onBrightness, min = 0, max = 100, step = 10, unit = "%")
                }
                KRow {
                    KIconChip("arrows")
                    Column(Modifier.weight(1f)) {
                        KText("Screen X · IPD", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                        KText("Nudge the image to your eye spacing", color = K.text2, size = 12.5.sp)
                    }
                    // The SDK rendering centre is an absolute pixel position (≈320), not a small
                    // offset — a wide range keeps "＋" usable; the SDK clamps out-of-bounds values.
                    KStepper(centerX, onCenterX, min = 0, max = 2000, step = 5, unit = "px")
                }
                KRow(last = true) {
                    KIconChip("arrows"); Column(Modifier.weight(1f)) { KText("Screen Y", color = K.text, size = 16.sp, weight = FontWeight.Medium) }
                    KStepper(centerY, onCenterY, min = 0, max = 2000, step = 5, unit = "px")
                }
            }
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KButton("Configure", variant = KBtnVariant.Ghost, modifier = Modifier.weight(1f), onClick = onConfigure)
                KButton("Adjust fit", variant = KBtnVariant.Ghost, modifier = Modifier.weight(1f), onClick = onAdjust)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/* ---------------- DISPLAY & UNITS ---------------- */
@Composable
fun DisplayScreen(cfg: HudConfig, ctx: Context, scope: CoroutineScope) {
    ScreenScroll {
        KSectionLabel("Units")
        CardBlock {
            Box(Modifier.padding(14.dp)) {
                KSegmented(listOf(false to "Metric · km/h", true to "Imperial · mph"), cfg.imperial) {
                    scope.launch { HudPreferences.setImperial(ctx, it) }
                }
            }
        }

        KSectionLabel("Page switching")
        CardBlock {
            val modes = listOf(
                Triple(PageMode.AUTO, "Auto-cycle pages", "Rotate pages on a timer"),
                Triple(PageMode.FOLLOW_KAROO, "Follow Karoo page", "Mirror the page you swipe on the head unit"),
                Triple(PageMode.MANUAL, "Manual", "Tap the glasses temple pad to switch"),
            )
            modes.forEachIndexed { i, (mode, title, sub) ->
                KRow(onClick = { scope.launch { HudPreferences.setPageMode(ctx, mode) } }, last = i == modes.lastIndex && cfg.pageMode != PageMode.AUTO) {
                    KRadio(cfg.pageMode == mode)
                    Column(Modifier.weight(1f)) {
                        KText(title, color = K.text, size = 16.sp, weight = FontWeight.Medium)
                        KText(sub, color = K.text2, size = 12.5.sp)
                    }
                }
            }
            if (cfg.pageMode == PageMode.AUTO) {
                Box(Modifier.background(K.surface2)) {
                    KRow(last = true) {
                        KIconChip("time")
                        Column(Modifier.weight(1f)) { KText("Seconds per page", color = K.text, size = 16.sp, weight = FontWeight.Medium) }
                        KStepper((cfg.autoCycleMs / 1000).toInt(), { scope.launch { HudPreferences.setAutoCycleMs(ctx, it * 1000L) } }, min = 2, max = 30, step = 1, unit = "sec")
                    }
                }
            }
        }

        KSectionLabel("On glasses")
        CardBlock {
            KRow {
                KIconChip("time")
                Column(Modifier.weight(1f)) {
                    KText("Show clock", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                    KText("Time of day in the corner", color = K.text2, size = 12.5.sp)
                }
                KSwitch(cfg.showClock) { scope.launch { HudPreferences.setShowClock(ctx, it) } }
            }
            KRow {
                KIconChip("eye")
                Column(Modifier.weight(1f)) {
                    KText("Field icons", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                    KText("Draw each field's icon next to its unit", color = K.text2, size = 12.5.sp)
                }
                KSwitch(cfg.showIcons) { scope.launch { HudPreferences.setShowIcons(ctx, it) } }
            }
            KRow(last = true) {
                KIconChip("refresh")
                Column(Modifier.weight(1f)) {
                    KText("Refresh rate", color = K.text, size = 16.sp, weight = FontWeight.Medium)
                    KText("Locked to the Karoo's 1 Hz sensor cadence", color = K.text2, size = 12.5.sp)
                }
                KText("1 Hz", color = K.text2, size = 17.sp, weight = FontWeight.SemiBold, family = CondFamily)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
