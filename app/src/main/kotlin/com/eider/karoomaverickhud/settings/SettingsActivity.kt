package com.eider.karoomaverickhud.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsActivity : ComponentActivity() {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text(getString(com.eider.karoomaverickhud.R.string.settings_title)) }) },
                    ) { inner ->
                        SettingsScreen(modifier = Modifier.padding(inner))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by HudPreferences.flow(ctx).collectAsState(initial = HudConfig.DEFAULT)

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Glasses section
        Text("Glasses", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (cfg.maverickDeviceName != null)
                "Paired: ${cfg.maverickDeviceName}"
            else
                "No glasses paired",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    // The SDK manages the scan + pair dialog; on success the
                    // configured device is persisted in EvsKit and we mirror it
                    // to our prefs from the comm callback (v2).
                    runCatching { Evs.instance().comm().connectSecured() }
                        .onFailure { Timber.w(it, "connectSecured failed") }
                },
            ) { Text("Pair / Connect") }

            OutlinedButton(
                onClick = {
                    runCatching { Evs.instance().comm().disconnect() }
                    scope.launch { HudPreferences.setPairedDevice(ctx, null, null) }
                },
                enabled = cfg.maverickDeviceId != null,
            ) { Text("Unpair") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Display", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (cfg.imperial) "Imperial (mph, mi)" else "Metric (km/h, km)")
            Switch(
                checked = cfg.imperial,
                onCheckedChange = { v -> scope.launch { HudPreferences.setImperial(ctx, v) } },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Page switching", style = MaterialTheme.typography.titleMedium)

        PageMode.values().forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = cfg.pageMode == mode,
                    onClick = { scope.launch { HudPreferences.setPageMode(ctx, mode) } },
                )
                Text(
                    text = when (mode) {
                        PageMode.AUTO -> "Auto-cycle every ${cfg.autoCycleMs / 1000}s"
                        PageMode.FOLLOW_KAROO -> "Follow Karoo page (v2)"
                        PageMode.MANUAL -> "Manual (v2)"
                    },
                )
            }
        }
    }
}
