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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eider.karoomaverickhud.settings.ui.CondFamily
import com.eider.karoomaverickhud.settings.ui.DisplayScreen
import com.eider.karoomaverickhud.settings.ui.GearScreen
import com.eider.karoomaverickhud.settings.ui.GlassesScreen
import com.eider.karoomaverickhud.settings.ui.HubScreen
import com.eider.karoomaverickhud.settings.ui.K
import com.eider.karoomaverickhud.settings.ui.KIcon
import com.eider.karoomaverickhud.settings.ui.KPill
import com.eider.karoomaverickhud.settings.ui.KText
import com.eider.karoomaverickhud.settings.ui.PagesScreen
import com.eider.karoomaverickhud.settings.ui.ProfileScreen
import com.eider.karoomaverickhud.settings.ui.rememberDemoValues
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoPair = intent?.getBooleanExtra(EXTRA_AUTO_PAIR, false) == true
        setContent {
            // The Karoo 3 panel is 480×800 px at 300 dpi (1.875×), i.e. only ~256 dp wide. The
            // design is authored in 480×800 *px*, so we pin density to 1 (1 dp == 1 px) and
            // fontScale to 1 for this UI — the dp/sp values then match the design's px exactly
            // and the 480-wide canvas matches the screen. A fixed-purpose device with one known
            // panel is exactly where overriding density is appropriate.
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                SettingsRoot(autoPair = autoPair)
            }
        }
    }

    companion object {
        /** When true, the screen starts the pair flow on its own (used by the ride data field). */
        const val EXTRA_AUTO_PAIR = "auto_pair"
    }
}

/** Screen metadata for the app bar (title + uppercase subtitle). */
private val SCREEN_META = mapOf(
    "hub" to ("Maverick HUD" to "Glasses settings"),
    "profile" to ("Rider Profile" to "FTP · HR · Cadence"),
    "gear" to ("Gear" to "Drivetrain"),
    "pages" to ("Data Pages" to "Glasses layout"),
    "glasses" to ("Glasses" to "Pair & control"),
    "display" to ("Display & Units" to "Units · paging"),
)

@Composable
private fun SettingsRoot(autoPair: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by HudPreferences.flow(ctx).collectAsState(initial = HudConfig.DEFAULT)

    var screen by remember { mutableStateOf("hub") }
    val values = rememberDemoValues()
    var previewPage by remember { mutableStateOf(0) }
    // Bumped on a manual tap to restart the auto-cycle timer, so a tap gives full control.
    var previewCycleReset by remember { mutableStateOf(0) }

    // Cycle the hub's live preview through the configured pages (restarts when tapped).
    LaunchedEffect(cfg.pages.size, cfg.autoCycleMs, previewCycleReset) {
        while (true) {
            delay(cfg.autoCycleMs.coerceAtLeast(1500L))
            if (cfg.pages.isNotEmpty()) previewPage = (previewPage + 1) % cfg.pages.size
        }
    }
    LaunchedEffect(cfg.pages.size) { if (previewPage >= cfg.pages.size) previewPage = 0 }

    // --- Pairing / connection (Location guard → BLE perms → our scan dialog), preserved ---
    var gpsBlocked by remember { mutableStateOf(false) }
    var linkConnected by remember { mutableStateOf(false) }
    // True only while the activity is actually on-screen. The live preview owns the glasses while
    // settings is in front; when the rider returns to the ride the activity is paused (not
    // destroyed), so we must gate the preview on this — otherwise the loop keeps pushing demo
    // frames forever and the HUD stays stuck on random data instead of resuming realtime.
    var inForeground by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            linkConnected = runCatching { Evs.instance().comm().isConnected() }.getOrDefault(false)
            delay(1_000)
        }
    }

    // Glasses-control readouts. Brightness/auto are observed off [GlassesLinkState] which the
    // bridge's connection loop keeps live; displayOn/centerX/Y are read once on link-up plus on
    // resume (so on-bike temple-pad changes show up when the rider opens settings).
    var displayOn by remember { mutableStateOf(true) }
    var centerX by remember { mutableStateOf(0) }
    var centerY by remember { mutableStateOf(0) }
    val brightnessLive by com.eider.karoomaverickhud.maverick.GlassesLinkState.brightness.collectAsState()
    val autoBrightness by com.eider.karoomaverickhud.maverick.GlassesLinkState.autoBrightness.collectAsState()
    val brightness = brightnessLive ?: 0
    var settingsResumeTick by remember { mutableStateOf(0) }
    LaunchedEffect(linkConnected, settingsResumeTick) {
        if (linkConnected) runCatching {
            val evs = Evs.instance()
            displayOn = evs.display().isDisplayOn()
            centerX = evs.screens().getRenderingCenterX().toInt()
            centerY = evs.screens().getRenderingCenterY().toInt()
        }
    }

    // Mirror a live preview of the current config onto the glasses while settings is open and
    // connected, so the rider sees their edits on the Maverick. The MaverickBridge honours
    // HudState.previewSnapshot; we clear it when leaving. Re-keys on cfg so each edit shows at once.
    LaunchedEffect(linkConnected, inForeground, cfg) {
        if (!linkConnected || !inForeground) {
            com.eider.karoomaverickhud.maverick.HudState.previewSnapshot.value = null
            return@LaunchedEffect
        }
        var seed = 0
        var page = 0
        var sinceCycleMs = 0L
        while (true) {
            com.eider.karoomaverickhud.maverick.HudState.previewSnapshot.value =
                com.eider.karoomaverickhud.extension.HudPreviewBuilder.snapshot(cfg, seed, page)
            seed++
            delay(1_500)
            sinceCycleMs += 1_500
            if (sinceCycleMs >= cfg.autoCycleMs.coerceAtLeast(1_500L)) { page++; sinceCycleMs = 0 }
        }
    }
    DisposableEffect(Unit) {
        onDispose { com.eider.karoomaverickhud.maverick.HudState.previewSnapshot.value = null }
    }

    var showPairDialog by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) showPairDialog = true else Timber.w("BLE permissions denied: $result")
    }
    val triggerPair = {
        if (!isLocationEnabled(ctx)) {
            gpsBlocked = true
            Timber.w("Pair blocked: Location/GPS is off")
        } else {
            gpsBlocked = false
            val needed = blePermissions().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) showPairDialog = true else permLauncher.launch(needed.toTypedArray())
        }
    }
    val triggerConnect = {
        runCatching {
            val comm = Evs.instance().comm()
            if (!comm.hasConfiguredDevice() && cfg.maverickDeviceId != null) comm.setDeviceInfo(cfg.maverickDeviceId, cfg.maverickDeviceName ?: "")
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

    LaunchedEffect(autoPair) { if (autoPair) { screen = "glasses"; triggerPair() } }

    // After the pairing activity finishes, mirror the SDK's configured device into our prefs.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    inForeground = true
                    // Re-key the glasses-control LaunchedEffect so displayOn/centerX/Y get re-read
                    // after on-bike temple-pad changes (brightness/auto already stream live).
                    settingsResumeTick++
                    runCatching {
                        val comm = Evs.instance().comm()
                        if (comm.hasConfiguredDevice()) {
                            val id = comm.getDeviceId()
                            val name = comm.getDeviceName()
                            if (!id.isNullOrEmpty() && id != cfg.maverickDeviceId) scope.launch { HudPreferences.setPairedDevice(ctx, id, name) }
                        }
                    }.onFailure { Timber.w(it, "Mirroring configured device failed") }
                }
                // Settings left the foreground (rider returned to the ride): release the glasses so
                // the ride pipeline regains the screen and realtime data resumes.
                Lifecycle.Event.ON_PAUSE -> inForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasDevice = cfg.maverickDeviceId != null
    val deviceName = cfg.maverickDeviceName ?: cfg.maverickDeviceId ?: ""

    Column(Modifier.fillMaxSize().background(K.bg)) {
        AppBar(screen, onBack = { screen = "hub" })
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                "hub" -> HubScreen(cfg, linkConnected, battery = null, values = values, previewPage = previewPage, nav = { screen = it },
                    onPreviewTap = {
                        if (cfg.pages.isNotEmpty()) previewPage = (previewPage + 1) % cfg.pages.size
                        previewCycleReset++
                    })
                "profile" -> ProfileScreen(cfg, ctx, scope)
                "gear" -> GearScreen(cfg, ctx, scope)
                "pages" -> PagesScreen(cfg, ctx, scope, values)
                "glasses" -> GlassesScreen(
                    hasDevice = hasDevice, connected = linkConnected, deviceName = deviceName, battery = null,
                    displayOn = displayOn, brightness = brightness, autoBrightness = autoBrightness,
                    centerX = centerX, centerY = centerY, gpsBlocked = gpsBlocked,
                    onPair = triggerPair, onConnect = triggerConnect,
                    onDisconnect = { runCatching { Evs.instance().comm().disconnect() }.onFailure { Timber.w(it, "disconnect") } },
                    onUnpair = {
                        runCatching {
                            val comm = Evs.instance().comm(); comm.disconnect(); comm.setDeviceInfo("", "")
                        }.onFailure { Timber.w(it, "Unpair failed") }
                        scope.launch { HudPreferences.setPairedDevice(ctx, null, null) }
                    },
                    onDisplayOn = { on ->
                        runCatching { if (on) Evs.instance().display().turnDisplayOn() else Evs.instance().display().turnDisplayOff() }
                            .onFailure { Timber.w(it, "toggle display") }
                        displayOn = on
                    },
                    onBrightness = { v ->
                        val nv = v.coerceIn(0, 100)
                        // Force auto off before writing — otherwise the firmware keeps
                        // overriding the manual level and the slider silently fails to "stick".
                        runCatching {
                            Evs.instance().display().autoBrightness().enable(isEnabled = false)
                            Evs.instance().display().setBrightness(nv.toShort())
                            Timber.d("settings setBrightness($nv) readback=${runCatching { Evs.instance().display().getBrightness().toInt() }.getOrNull()}")
                        }.onFailure { Timber.w(it, "brightness") }
                        com.eider.karoomaverickhud.maverick.GlassesLinkState.brightness.value = nv
                        com.eider.karoomaverickhud.maverick.GlassesLinkState.autoBrightness.value = false
                    },
                    onAutoBrightness = { on ->
                        runCatching {
                            Evs.instance().display().autoBrightness().enable(isEnabled = on)
                            Timber.d("settings autoBrightness.enable($on) readback=${runCatching { Evs.instance().display().autoBrightness().isEnabled() }.getOrNull()}")
                        }.onFailure { Timber.w(it, "auto-brightness") }
                        com.eider.karoomaverickhud.maverick.GlassesLinkState.autoBrightness.value = on
                    },
                    onCenterX = { v ->
                        runCatching { Evs.instance().screens().setRenderingCenterX(v.toFloat()) }.onFailure { Timber.w(it, "centerX") }
                        centerX = v
                    },
                    onCenterY = { v ->
                        runCatching { Evs.instance().screens().setRenderingCenterY(v.toFloat()) }.onFailure { Timber.w(it, "centerY") }
                        centerY = v
                    },
                    onConfigure = { runCatching { Evs.instance().showUI("configure") }.onFailure { Timber.w(it, "configure") } },
                    onAdjust = { runCatching { Evs.instance().showUI("adjust") }.onFailure { Timber.w(it, "adjust") } },
                )
                "display" -> DisplayScreen(cfg, ctx, scope)
            }
        }
    }
}

/** Top app bar: back (or glasses logo on the hub) + title/subtitle + version pill on the hub. */
@Composable
private fun AppBar(screen: String, onBack: () -> Unit) {
    val (title, sub) = SCREEN_META[screen] ?: ("Maverick HUD" to "")
    val isHub = screen == "hub"
    Column {
        Row(
            Modifier.fillMaxWidth().height(68.dp).background(K.surface).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isHub) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(K.accentDim)
                    .padding(0.dp), contentAlignment = Alignment.Center) { KIcon("glasses", 22.dp, K.accent) }
            } else {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(K.surface2)
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onBack),
                    contentAlignment = Alignment.Center) { KIcon("back", 22.dp, K.text) }
            }
            Column(Modifier.weight(1f)) {
                KText(title, color = K.text, size = 22.sp, weight = FontWeight.Bold, family = CondFamily, maxLines = 1)
                KText(sub.uppercase(), color = K.text3, size = 11.5.sp, letterSpacing = 0.7.sp, maxLines = 1)
            }
            if (isHub) KPill("v0.1", color = K.text2, bg = K.surface2)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(K.line))
    }
}

/**
 * Runtime permissions a BLE scan needs. On Android 12+ scanning uses BLUETOOTH_SCAN /
 * BLUETOOTH_CONNECT plus ACCESS_FINE_LOCATION (without neverForLocation the scan is treated as
 * location-deriving and returns nothing); below that it needs ACCESS_FINE_LOCATION.
 */
private fun blePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Whether system Location is on — the EvsKit BLE scan finds nothing without it. */
private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return false
    return LocationManagerCompat.isLocationEnabled(lm)
}
