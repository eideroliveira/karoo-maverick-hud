/* ============================================================
   KarooWidgets.kt — shared widgets matching ui.jsx + styles.css:
   KText, KSwitch, KStepper, KSegmented, KSectionLabel, KCard,
   KRow/KSettingRow, KButton, KPill, KRadio, KIconChip, KBottomSheet.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText

/** Condensed face for numbers/titles — approximated with the platform sans + tight spacing. */
val CondFamily = FontFamily.Default

/** Theme-independent text (no MaterialTheme needed); colour always explicit. */
@Composable
fun KText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = K.text,
    size: TextUnit = 19.sp,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.Default,
    letterSpacing: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 6,
    align: TextAlign = TextAlign.Unspecified,
    softWrap: Boolean = true,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        softWrap = softWrap,
        // maxLines defaults to a finite cap (above): an unbounded maxLines on free-wrapping
        // text drives BasicText into an infinite measure loop inside a weighted/constrained column.
        overflow = if (softWrap) TextOverflow.Ellipsis else TextOverflow.Clip,
        style = TextStyle(
            color = color, fontSize = size, fontWeight = weight, fontFamily = family,
            letterSpacing = letterSpacing, lineHeight = lineHeight, textAlign = align,
        ),
    )
}


/* ---- toggle switch ---- */
@Composable
fun KSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val knobX by animateDpAsState(if (on) 27.dp else 3.dp, label = "knob")
    Box(
        Modifier
            .size(56.dp, 32.dp)
            .clip(CircleShape)
            .background(if (on) K.accent else K.surface4)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(start = knobX, top = 3.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White)
                // Hairline so the white knob stays defined against the light-grey off-track.
                .border(1.dp, if (on) Color.Transparent else K.line2, CircleShape),
        )
    }
}

/* ---- stepper (− value ＋) ---- */
@Composable
fun KStepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int = 0,
    max: Int = 999,
    step: Int = 1,
    unit: String? = null,
    valueWidth: Dp = 78.dp,
) {
    // EXPLICIT total width (two 46dp buttons + 2×10dp gaps + value column). A wrap-content
    // stepper sitting next to a weight(1f) sibling produced an intermittent infinite measure
    // loop in a scrolling card; a fixed width makes it behave like the fixed-size switch.
    Row(
        Modifier.width(valueWidth + 112.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepBtn("−", enabled = value > min) { onChange((value - step).coerceIn(min, max)) }
        Column(Modifier.width(valueWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            KText(value.toString(), color = K.text, size = 28.sp, weight = FontWeight.Bold, family = CondFamily,
                align = TextAlign.Center, maxLines = 1, softWrap = false, modifier = Modifier.fillMaxWidth())
            if (unit != null) KText(unit, color = K.text3, size = 13.sp, maxLines = 1, softWrap = false)
        }
        StepBtn("＋", enabled = value < max) { onChange((value + step).coerceIn(min, max)) }
    }
}

@Composable
private fun StepBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPressed && enabled) K.surface4 else K.surface3)
            .border(1.dp, K.line2, RoundedCornerShape(12.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        KText(label, color = if (enabled) K.text else K.text3.copy(alpha = 0.5f), size = 28.sp)
    }
}

/* ---- segmented control ---- */
@Composable
fun <T> KSegmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(K.surface2)
            .border(1.dp, K.line2, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (v, label) ->
            val on = v == value
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) K.accent else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onChange(v) },
                contentAlignment = Alignment.Center,
            ) {
                KText(label, color = if (on) K.onAccent else K.text2, size = 17.sp,
                    weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1)
            }
        }
    }
}

/* ---- section label ---- */
@Composable
fun KSectionLabel(text: String, modifier: Modifier = Modifier) {
    KText(
        text.uppercase(), color = K.text3, size = 14.sp, weight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp, modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 9.dp),
    )
}

/* ---- card ---- */
@Composable
fun KCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(K.surface)
            .border(1.dp, K.line, RoundedCornerShape(16.dp)),
        content = content,
    )
}

/* ---- list row container ---- */
@Composable
fun KRow(
    onClick: (() -> Unit)? = null,
    last: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .let { if (onClick != null) it.clickable(remember { MutableInteractionSource() }, null, onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(K.line))
    }
}

/* ---- composed settings row: chip + title/sub + value/trailing (+ chevron) ---- */
@Composable
fun KSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    iconColor: Color? = null,
    sub: String? = null,
    value: String? = null,
    danger: Boolean = false,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    KRow(onClick = onClick, last = last) {
        if (icon != null) KIconChip(icon, color = iconColor)
        Column(Modifier.weight(1f)) {
            KText(title, color = if (danger) K.bad else K.text, size = 19.sp, weight = FontWeight.Medium)
            if (sub != null) KText(sub, color = K.text2, size = 15.sp, lineHeight = 20.sp)
        }
        if (value != null) KText(value, color = K.text2, size = 20.sp, weight = FontWeight.SemiBold, family = CondFamily)
        trailing?.invoke()
        if (onClick != null && trailing == null && value == null) KIcon("chevron", 18.dp, K.text3)
    }
}

/* ---- icon chip ---- */
@Composable
fun KIconChip(name: String, color: Color? = null, bg: Color = K.surface3, chipSize: Dp = 40.dp, iconSize: Dp = 20.dp) {
    Box(
        Modifier.size(chipSize).clip(RoundedCornerShape(11.dp)).background(if (color != null) Color.Transparent else bg),
        contentAlignment = Alignment.Center,
    ) { KIcon(name, iconSize, color ?: K.text) }
}

/* ---- buttons ---- */
enum class KBtnVariant { Primary, Ghost, Danger }

@Composable
fun KButton(
    label: String,
    modifier: Modifier = Modifier,
    variant: KBtnVariant = KBtnVariant.Ghost,
    icon: String? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val (bg, fg, border) = when (variant) {
        KBtnVariant.Primary -> Triple(if (isPressed) K.accentPress else K.accent, K.onAccent, null)
        KBtnVariant.Ghost -> Triple(if (isPressed) K.surface4 else K.surface3, K.text, K.line2)
        KBtnVariant.Danger -> Triple(if (isPressed) K.bad.copy(alpha = 0.14f) else Color.Transparent, K.bad, K.bad.copy(alpha = 0.4f))
    }
    Row(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(13.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .let { if (border != null) it.border(1.dp, border, RoundedCornerShape(13.dp)) else it }
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            KIcon(icon, 18.dp, fg)
            Spacer(Modifier.width(9.dp))
        }
        KText(label, color = fg, size = 19.sp, weight = FontWeight.SemiBold, family = CondFamily, maxLines = 1)
    }
}

/* ---- pill ---- */
@Composable
fun KPill(text: String, color: Color = K.text2, bg: Color = K.surface3, dot: Boolean = false, height: Dp = 26.dp) {
    Row(
        Modifier.heightIn(min = height).clip(CircleShape).background(bg).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        KText(text, color = color, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/* ---- radio ---- */
@Composable
fun KRadio(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (on) K.accent else K.line3, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(K.accent)) }
}

@Composable
fun KStatusDot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
