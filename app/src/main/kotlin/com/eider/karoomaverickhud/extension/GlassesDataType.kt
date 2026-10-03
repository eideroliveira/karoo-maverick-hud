package com.eider.karoomaverickhud.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import com.eider.karoomaverickhud.R
import com.eider.karoomaverickhud.maverick.Eco
import com.eider.karoomaverickhud.maverick.GlassesLinkState
import com.eider.karoomaverickhud.maverick.LinkIssue
import com.eider.karoomaverickhud.maverick.MaverickLink
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
 * ride-limiting resource), BLE signal, and the current display brightness or ECO badge — and
 * doubles as a one-tap control. Karoo data fields are RemoteViews (single click only, no
 * long-press), so the tap is context-aware: connected → toggle battery-saver (brightness now lives
 * on the temple pad / settings); disconnected → force a reconnect, or open pairing if no glasses
 * are paired yet (handled in [RideHudExtension]).
 */
class GlassesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(
                combine(
                    GlassesLinkState.connected, GlassesLinkState.connecting, Eco.active, Eco.auto,
                    MaverickLink.issue,
                ) { c, ing, eco, ecoAuto, issue -> Link(c, ing, eco, ecoAuto, issue) },
                GlassesLinkState.battery,
                GlassesLinkState.signal,
                GlassesLinkState.brightness,
                GlassesLinkState.autoBrightness,
            ) { link, battery, signal, brightness, auto ->
                State(link.connected, link.connecting, battery, signal, brightness, auto, link.eco, link.ecoAuto, link.issue)
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

    private data class Link(
        val connected: Boolean,
        val connecting: Boolean,
        val eco: Boolean,
        val ecoAuto: Boolean,
        val issue: LinkIssue?,
    )

    private data class State(
        val connected: Boolean,
        val connecting: Boolean,
        val battery: Int?,
        val signal: Int,
        val brightness: Int?,
        val auto: Boolean,
        val eco: Boolean,
        val ecoAuto: Boolean,
        val issue: LinkIssue? = null,
    )

    private fun render(views: RemoteViews, s: State) {
        if (!s.connected && s.issue != null) {
            // Something specific is stopping the glasses (e.g. no internet for sign-in): name it,
            // amber, instead of a generic "Disconnected". The tap still retries.
            views.setTextViewText(R.id.gf_top, "Tap to retry")
            views.setTextColor(R.id.gf_top, COLOR_DIM)
            views.setTextViewText(R.id.gf_main, s.issue.title)
            views.setTextColor(R.id.gf_main, COLOR_AMBER)
            views.setTextViewTextSize(R.id.gf_main, TypedValue.COMPLEX_UNIT_SP, ISSUE_TEXT_SP)
            return
        }
        if (!s.connected) {
            views.setTextViewText(R.id.gf_top, if (s.connecting) "Reconnecting…" else "Disconnected")
            views.setTextColor(R.id.gf_top, COLOR_DIM)
            views.setTextViewText(R.id.gf_main, "Tap to connect")
            views.setTextColor(R.id.gf_main, COLOR_WHITE)
            return
        }
        // Top line: current brightness (amber while battery-saver is engaged), then signal bars.
        val lead = when {
            s.auto -> "Auto"
            s.brightness != null -> "${s.brightness}%"
            else -> "—"
        }
        val bars = signalBars(s.signal)
        views.setTextViewText(R.id.gf_top, if (bars.isEmpty()) lead else "$lead  $bars")
        views.setTextColor(R.id.gf_top, if (s.eco) COLOR_AMBER else COLOR_DIM)
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

        // gf_main is 26sp in view_glasses_field.xml; a ≤14-char issue title needs a notch less to fit
        // a half-width tile.
        private const val ISSUE_TEXT_SP = 20f

        // Shown in the Karoo field-picker preview so the tile looks alive, not "Disconnected".
        private val PREVIEW = State(
            connected = true,
            connecting = false,
            battery = 78,
            signal = 2,
            brightness = 75,
            auto = false,
            eco = false,
            ecoAuto = false,
        )
    }
}
