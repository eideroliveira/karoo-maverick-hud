package com.eider.karoomaverickhud.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.eider.karoomaverickhud.R
import com.eider.karoomaverickhud.maverick.GlassesLinkState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A ride data field that surfaces the Maverick link at a glance — glasses battery (the real
 * ride-limiting resource), BLE signal, and the current display brightness — and doubles as a
 * one-tap control. Karoo data fields are RemoteViews (single click only, no long-press), so the
 * tap is context-aware: connected → cycle brightness 50→75→100→auto; disconnected → force a
 * reconnect, or open pairing if no glasses are paired yet (handled in [RideHudExtension]).
 */
class GlassesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(
                combine(GlassesLinkState.connected, GlassesLinkState.connecting) { c, ing -> c to ing },
                GlassesLinkState.battery,
                GlassesLinkState.signal,
                GlassesLinkState.brightness,
                GlassesLinkState.autoBrightness,
            ) { (connected, connecting), battery, signal, brightness, auto ->
                State(connected, connecting, battery, signal, brightness, auto)
            }.collect { state ->
                val views = RemoteViews(context.packageName, R.layout.view_glasses_field)
                render(views, if (config.preview) PREVIEW else state)
                if (!config.preview) {
                    views.setOnClickPendingIntent(R.id.glasses_field_root, tapPendingIntent(context))
                }
                emitter.updateView(views)
            }
        }
        emitter.setCancellable {
            Timber.d("stop glasses view")
            job.cancel()
        }
    }

    private data class State(
        val connected: Boolean,
        val connecting: Boolean,
        val battery: Int?,
        val signal: Int,
        val brightness: Int?,
        val auto: Boolean,
    )

    private fun render(views: RemoteViews, s: State) {
        if (!s.connected) {
            views.setTextViewText(R.id.gf_top, if (s.connecting) "Reconnecting…" else "Disconnected")
            views.setTextColor(R.id.gf_top, COLOR_DIM)
            views.setTextViewText(R.id.gf_main, "Tap to connect")
            views.setTextColor(R.id.gf_main, COLOR_WHITE)
            return
        }
        // Top line: brightness + signal bars (link quality). Brightness is what the tap cycles.
        val bright = when {
            s.auto -> "Auto"
            s.brightness != null -> "${s.brightness}%"
            else -> "—"
        }
        val bars = signalBars(s.signal)
        views.setTextViewText(R.id.gf_top, if (bars.isEmpty()) bright else "$bright  $bars")
        views.setTextColor(R.id.gf_top, COLOR_DIM)
        // Main line: glasses battery — the hero value, coloured by how worried you should be.
        views.setTextViewText(R.id.gf_main, s.battery?.let { "$it%" } ?: "—")
        views.setTextColor(R.id.gf_main, batteryColor(s.battery))
    }

    private fun signalBars(signal: Int): String = when (signal.coerceIn(0, 3)) {
        3 -> "▮▮▮"
        2 -> "▮▮▯"
        1 -> "▮▯▯"
        else -> "" // no reading yet — show brightness alone rather than an alarming empty meter
    }

    private fun batteryColor(battery: Int?): Int = when {
        battery == null -> COLOR_WHITE
        battery <= 20 -> COLOR_RED
        battery <= 40 -> COLOR_AMBER
        else -> COLOR_WHITE
    }

    private fun tapPendingIntent(context: Context): PendingIntent {
        val intent = Intent(RideHudExtension.ACTION_GLASSES_TAP).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TYPE_ID = "glasses"

        private val COLOR_WHITE = Color.WHITE
        private val COLOR_DIM = Color.parseColor("#B0B0B0")
        private val COLOR_AMBER = Color.parseColor("#FFB300")
        private val COLOR_RED = Color.parseColor("#FF4D4D")

        // Shown in the Karoo field-picker preview so the tile looks alive, not "Disconnected".
        private val PREVIEW = State(
            connected = true,
            connecting = false,
            battery = 78,
            signal = 2,
            brightness = 75,
            auto = false,
        )
    }
}
