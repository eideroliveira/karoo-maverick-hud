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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.eider.karoomaverickhud.extension.FieldFormat
import com.eider.karoomaverickhud.extension.HudFieldId
import com.eider.karoomaverickhud.extension.MAX_ROWS
import com.eider.karoomaverickhud.extension.MIN_ROWS
import com.eider.karoomaverickhud.extension.cellsForRows
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.delay
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

    // The real link state, polled from the SDK rather than inferred from the saved pairing —
    // a paired device is not the same as a live connection.
    var linkConnected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            linkConnected = runCatching { Evs.instance().comm().isConnected() }.getOrDefault(false)
            delay(1_000)
        }
    }

    // Glasses-control readouts (brightness, display state, screen offset). Read ONCE when the
    // link comes up — polling these SDK getters every second destabilises the BLE sync and
    // drops the glasses. Each control action updates the local state directly afterwards.
    var displayOn by remember { mutableStateOf(true) }
    var brightness by remember { mutableStateOf(0) }
    var centerX by remember { mutableStateOf(0) }
    var centerY by remember { mutableStateOf(0) }
    LaunchedEffect(linkConnected) {
        if (linkConnected) {
            runCatching {
                val evs = Evs.instance()
                displayOn = evs.display().isDisplayOn()
                brightness = evs.display().getBrightness().toInt()
                centerX = evs.screens().getRenderingCenterX().toInt()
                centerY = evs.screens().getRenderingCenterY().toInt()
            }
        }
    }

    // Our own pairing dialog (scan + tap to connect). Replaces the SDK's scan screen,
    // whose device list doesn't render on the Karoo.
    var showPairDialog by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) showPairDialog = true else Timber.w("BLE permissions denied: $result")
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
            if (needed.isEmpty()) showPairDialog = true else permLauncher.launch(needed.toTypedArray())
        }
    }

    // Bring the link up for an already-paired device, restoring it into the SDK first if the
    // SDK forgot it (e.g. after a process restart).
    val triggerConnect = {
        runCatching {
            val comm = Evs.instance().comm()
            if (!comm.hasConfiguredDevice() && cfg.maverickDeviceId != null) {
                comm.setDeviceInfo(cfg.maverickDeviceId, cfg.maverickDeviceName ?: "")
            }
            if (comm.hasConfiguredDevice() && !comm.isConnected() && !comm.isConnecting()) comm.connectSecured()
        }.onFailure { Timber.w(it, "Connect failed") }
        Unit
    }

    if (showPairDialog) {
        MaverickPairDialog(
            onDismiss = { showPairDialog = false },
            onPaired = { address, name ->
                scope.launch { HudPreferences.setPairedDevice(ctx, address, name) }
                showPairDialog = false
            },
        )
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Glasses section
        val hasDevice = cfg.maverickDeviceId != null
        val deviceName = cfg.maverickDeviceName ?: cfg.maverickDeviceId ?: ""
        Text(ctx.getString(com.eider.karoomaverickhud.R.string.settings_glasses_section), style = MaterialTheme.typography.titleMedium)
        Text(
            text = when {
                !hasDevice -> "No glasses paired"
                linkConnected -> "Connected: $deviceName"
                else -> "Paired: $deviceName — not connected"
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                // No device yet: the only action is to pair one.
                !hasDevice -> Button(onClick = triggerPair) {
                    Text(ctx.getString(com.eider.karoomaverickhud.R.string.settings_pair))
                }
                // Paired but the link is down: let the user bring it up without re-pairing.
                !linkConnected -> Button(onClick = triggerConnect) { Text("Connect") }
            }

            if (hasDevice) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val comm = Evs.instance().comm()
                            comm.disconnect()
                            comm.setDeviceInfo("", "") // clear the SDK's configured device
                        }.onFailure { Timber.w(it, "Unpair failed") }
                        scope.launch { HudPreferences.setPairedDevice(ctx, null, null) }
                    },
                ) { Text(ctx.getString(com.eider.karoomaverickhud.R.string.settings_unpair)) }
            }
        }

        if (gpsBlocked) {
            Text(
                text = "Location is off, so scanning finds nothing. Start a ride to enable " +
                    "GPS, then tap the Glasses data field to pair.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Glasses control — live device controls, only while the link is up (mirrors the
        // Everysight GlassesControl sample: brightness, display on/off, screen IPD offset).
        if (hasDevice && linkConnected) {
            val setBrightness = { v: Int ->
                val nv = v.coerceIn(0, 100)
                runCatching { Evs.instance().display().setBrightness(nv.toShort()) }.onFailure { Timber.w(it, "brightness") }
                brightness = nv
            }
            val setCenterX = { v: Int ->
                runCatching { Evs.instance().screens().setRenderingCenterX(v.toFloat()) }.onFailure { Timber.w(it, "centerX") }
                centerX = v
            }
            val setCenterY = { v: Int ->
                runCatching { Evs.instance().screens().setRenderingCenterY(v.toFloat()) }.onFailure { Timber.w(it, "centerY") }
                centerY = v
            }

            Spacer(Modifier.height(8.dp))
            Text("Glasses control", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Display on")
                Switch(
                    checked = displayOn,
                    onCheckedChange = { on ->
                        runCatching {
                            if (on) Evs.instance().display().turnDisplayOn() else Evs.instance().display().turnDisplayOff()
                        }.onFailure { Timber.w(it, "toggle display") }
                        displayOn = on
                    },
                )
            }
            ControlRow("Brightness", brightness, { setBrightness(brightness - 10) }, { setBrightness(brightness + 10) })
            ControlRow("Screen X (IPD)", centerX, { setCenterX(centerX - 5) }, { setCenterX(centerX + 5) })
            ControlRow("Screen Y", centerY, { setCenterY(centerY - 5) }, { setCenterY(centerY + 5) })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { runCatching { Evs.instance().showUI("configure") }.onFailure { Timber.w(it, "configure") } }) {
                    Text("Configure")
                }
                OutlinedButton(onClick = { runCatching { Evs.instance().showUI("adjust") }.onFailure { Timber.w(it, "adjust") } }) {
                    Text("Adjust")
                }
            }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Rows per page")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (MIN_ROWS..MAX_ROWS).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = cfg.rows == r,
                            onClick = { scope.launch { HudPreferences.setRows(ctx, r) } },
                        )
                        Text("$r")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Training zones", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used to color power, heart rate and cadence. White = easy (Z1), then green, " +
                "orange, red, purple as effort rises. Set 0 to disable a field's coloring.",
            style = MaterialTheme.typography.bodySmall,
        )
        NumberStepper(
            label = "FTP (W)", value = cfg.ftp, step = 5, min = 0, max = 600,
            onChange = { v -> scope.launch { HudPreferences.setZones(ctx, v, cfg.maxHr, cfg.idealCadence) } },
        )
        NumberStepper(
            label = "Max HR (bpm)", value = cfg.maxHr, step = 1, min = 0, max = 230,
            onChange = { v -> scope.launch { HudPreferences.setZones(ctx, cfg.ftp, v, cfg.idealCadence) } },
        )
        NumberStepper(
            label = "Ideal cadence (rpm)", value = cfg.idealCadence, step = 1, min = 0, max = 130,
            onChange = { v -> scope.launch { HudPreferences.setZones(ctx, cfg.ftp, cfg.maxHr, v) } },
        )

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

        Spacer(Modifier.height(8.dp))
        Text("Custom pages", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used by Auto-cycle and Manual modes. Follow-Karoo ignores these and mirrors " +
                "the Karoo's active page.",
            style = MaterialTheme.typography.bodySmall,
        )

        PagesEditor(
            pages = cfg.pages,
            maxFields = cellsForRows(cfg.rows),
            onChange = { next -> scope.launch { HudPreferences.setPages(ctx, next) } },
        )
    }
}

/** A label with −/＋ buttons and the current value; clamps to [min]..[max] by [step]. */
@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange((value - step).coerceIn(min, max)) }, enabled = value > min) { Text("−") }
            Text("$value", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onChange((value + step).coerceIn(min, max)) }, enabled = value < max) { Text("＋") }
        }
    }
}

/** A label with −/＋ buttons and the current value; the callbacks apply the change to the SDK. */
@Composable
private fun ControlRow(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMinus) { Text("−") }
            Text("$value", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onPlus) { Text("＋") }
        }
    }
}

/** Add/remove glasses pages and the fields on each (max [maxFields] fields per page). */
@Composable
private fun PagesEditor(pages: List<List<String>>, maxFields: Int, onChange: (List<List<String>>) -> Unit) {
    var addFieldForPage by remember { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pages.forEachIndexed { pageIndex, page ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Page ${pageIndex + 1}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = {
                    onChange(pages.filterIndexed { i, _ -> i != pageIndex })
                }) { Text("Remove") }
            }

            page.forEachIndexed { fieldIndex, dtid ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("• ${FieldFormat.labelFor(dtid)}")
                    TextButton(onClick = {
                        onChange(
                            pages.mapIndexed { i, p ->
                                if (i == pageIndex) p.filterIndexed { j, _ -> j != fieldIndex } else p
                            },
                        )
                    }) { Text("✕") }
                }
            }

            if (page.size < maxFields) {
                Box {
                    TextButton(onClick = { addFieldForPage = pageIndex }) { Text("+ Add field") }
                    DropdownMenu(
                        expanded = addFieldForPage == pageIndex,
                        onDismissRequest = { addFieldForPage = -1 },
                    ) {
                        HudFieldId.entries.forEach { field ->
                            DropdownMenuItem(
                                text = { Text(field.label) },
                                onClick = {
                                    addFieldForPage = -1
                                    onChange(
                                        pages.mapIndexed { i, p ->
                                            if (i == pageIndex) p + field.dataTypeId else p
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (pages.size < MAX_PAGES) {
            TextButton(onClick = { onChange(pages + listOf(emptyList<String>())) }) { Text("+ Add page") }
        }
    }
}

private const val MAX_PAGES = 5

/**
 * Runtime permissions a BLE scan needs. On Android 12+ scanning uses BLUETOOTH_SCAN /
 * BLUETOOTH_CONNECT (with neverForLocation, so no location); below that it needs
 * ACCESS_FINE_LOCATION. Anything older than the install minSdk is unreachable.
 */
private fun blePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Fine location is needed too: without neverForLocation, an Android 12 BLE scan
        // is treated as location-deriving and returns nothing unless it's granted.
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Whether system Location is on — the EvsKit BLE scan finds nothing without it. */
private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return false
    return LocationManagerCompat.isLocationEnabled(lm)
}
