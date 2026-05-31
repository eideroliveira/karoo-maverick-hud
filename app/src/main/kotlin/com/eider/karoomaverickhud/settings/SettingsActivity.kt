package com.eider.karoomaverickhud.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsActivity : ComponentActivity() {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoPair = intent?.getBooleanExtra(EXTRA_AUTO_PAIR, false) == true
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text(getString(com.eider.karoomaverickhud.R.string.settings_title)) }) },
                    ) { inner ->
                        SettingsScreen(modifier = Modifier.padding(inner), autoPair = autoPair)
                    }
                }
            }
        }
    }

    companion object {
        /** When true, the screen starts the pair flow on its own (used by the ride data field). */
        const val EXTRA_AUTO_PAIR = "auto_pair"
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier, autoPair: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by HudPreferences.flow(ctx).collectAsState(initial = HudConfig.DEFAULT)

    // Set when pairing is blocked because Location/GPS is off. A BLE scan finds nothing
    // without it, and the Karoo only powers GPS during a ride.
    var gpsBlocked by remember { mutableStateOf(value = false) }

    // Launch the EvsKit scan/pair UI (EvsGlassesScanActivity). On return the SDK has
    // persisted the chosen device; we mirror it into our prefs from onResume below.
    val launchPairing = {
        val started = runCatching { Evs.instance().showUI("pair") }
            .getOrElse { Timber.w(it, "showUI(pair) failed"); false }
        Timber.i("showUI(pair) returned $started")
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) launchPairing() else Timber.w("BLE permissions denied: $result")
    }

    // Single entry point for "pair": guard on Location, then request BLE permissions, then scan.
    val triggerPair = {
        if (!isLocationEnabled(ctx)) {
            gpsBlocked = true
            Timber.w("Pair blocked: Location/GPS is off")
        } else {
            gpsBlocked = false
            val needed = blePermissions().filter {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) launchPairing() else permLauncher.launch(needed.toTypedArray())
        }
    }

    // Entered from the ride data field: kick off pairing automatically (GPS is on in a ride).
    LaunchedEffect(autoPair) {
        if (autoPair) triggerPair()
    }

    // After the pairing activity finishes, copy the SDK's configured device into our
    // prefs so the HUD knows what to connect to and the UI reflects it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                runCatching {
                    val comm = Evs.instance().comm()
                    if (comm.hasConfiguredDevice()) {
                        val id = comm.getDeviceId()
                        val name = comm.getDeviceName()
                        if (!id.isNullOrEmpty() && (id != cfg.maverickDeviceId)) {
                            scope.launch { HudPreferences.setPairedDevice(ctx, id, name) }
                        }
                    }
                }.onFailure { Timber.w(it, "Mirroring configured device failed") }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Glasses section
        Text(ctx.getString(com.eider.karoomaverickhud.R.string.settings_glasses_section), style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (cfg.maverickDeviceName != null)
                ctx.getString(com.eider.karoomaverickhud.R.string.settings_status_connected, cfg.maverickDeviceName)
            else
                ctx.getString(com.eider.karoomaverickhud.R.string.settings_status_disconnected),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = triggerPair) { Text(ctx.getString(com.eider.karoomaverickhud.R.string.settings_pair)) }

            OutlinedButton(
                onClick = {
                    runCatching {
                        val comm = Evs.instance().comm()
                        comm.disconnect()
                        comm.setDeviceInfo("", "") // clear the SDK's configured device
                    }.onFailure { Timber.w(it, "Unpair failed") }
                    scope.launch { HudPreferences.setPairedDevice(ctx, null, null) }
                },
                enabled = cfg.maverickDeviceId != null,
            ) { Text(ctx.getString(com.eider.karoomaverickhud.R.string.settings_unpair)) }
        }

        if (gpsBlocked) {
            Text(
                text = "Location is off, so scanning finds nothing. Start a ride to enable " +
                    "GPS, then tap the Glasses data field to pair.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Display", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (cfg.imperial) ctx.getString(com.eider.karoomaverickhud.R.string.settings_units_imperial) else ctx.getString(com.eider.karoomaverickhud.R.string.settings_units_metric))
            Switch(
                checked = cfg.imperial,
                onCheckedChange = { v -> scope.launch { HudPreferences.setImperial(ctx, v) } },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Page switching", style = MaterialTheme.typography.titleMedium)

        PageMode.entries.forEach { mode ->
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
                        PageMode.AUTO -> ctx.getString(com.eider.karoomaverickhud.R.string.settings_page_mode_auto)
                        PageMode.FOLLOW_KAROO -> ctx.getString(com.eider.karoomaverickhud.R.string.settings_page_mode_follow)
                        PageMode.MANUAL -> ctx.getString(com.eider.karoomaverickhud.R.string.settings_page_mode_manual)
                    },
                )
            }
        }
    }
}

/**
 * Runtime permissions a BLE scan needs. On Android 12+ scanning uses BLUETOOTH_SCAN /
 * BLUETOOTH_CONNECT (with neverForLocation, so no location); below that it needs
 * ACCESS_FINE_LOCATION. Anything older than the install minSdk is unreachable.
 */
private fun blePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Whether system Location is on — the EvsKit BLE scan finds nothing without it. */
private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return false
    return LocationManagerCompat.isLocationEnabled(lm)
}
