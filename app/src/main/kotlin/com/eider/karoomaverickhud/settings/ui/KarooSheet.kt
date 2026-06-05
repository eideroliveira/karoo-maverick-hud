/* ============================================================
   KarooSheet.kt — bottom sheet (scrim + bottom-anchored panel),
   mirrors ui.jsx <Sheet>. Rendered at the top of the screen
   stack so it overlays all content.
   ============================================================ */
package com.eider.karoomaverickhud.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A modal bottom sheet. Tapping the scrim or the Done action calls [onClose]. [content] scrolls
 * inside the panel. Place this composable last in the screen Box so it draws on top.
 */
@Composable
fun KBottomSheet(
    title: String,
    onClose: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(K.surface)
                .border(1.dp, K.line2, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                // swallow taps so they don't reach the scrim
                .clickable(remember { MutableInteractionSource() }, null) {},
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(K.line3))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                KText(title, color = K.text, size = 20.sp, weight = FontWeight.Bold, family = CondFamily)
                if (action != null) {
                    action()
                } else {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) { KText("Done", color = K.accent, size = 15.sp, weight = FontWeight.SemiBold, family = CondFamily) }
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
        }
    }
}
