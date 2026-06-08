package com.eider.karoomaverickhud.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * A ride data field that shows the Maverick link status and current display brightness, and
 * cycles brightness through 50% → 75% → 100% → auto on a single tap. Karoo data fields are
 * RemoteViews — single click only, no long-press — so cycling is the only gesture exposed
 * here; pairing still lives in the ride-menu action.
 */
class GlassesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(
                GlassesLinkState.connected,
                GlassesLinkState.brightness,
                GlassesLinkState.autoBrightness,
            ) { connected, brightness, auto -> Triple(connected, brightness, auto) }
                .collect { (connected, brightness, auto) ->
                    val views = RemoteViews(context.packageName, R.layout.view_glasses_field)
                    render(views, connected, brightness, auto)
                    if (!config.preview) {
                        views.setOnClickPendingIntent(R.id.glasses_field_root, cyclePendingIntent(context))
                    }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            Timber.d("stop glasses view")
            job.cancel()
        }
    }

    private fun render(views: RemoteViews, connected: Boolean, brightness: Int?, auto: Boolean) {
        val status = if (connected) "Connected" else "Disconnected"
        val value = when {
            !connected -> "—"
            auto -> "Auto"
            brightness != null -> "$brightness%"
            else -> "—"
        }
        views.setTextViewText(R.id.gf_status, status)
        views.setTextViewText(R.id.gf_brightness, value)
    }

    private fun cyclePendingIntent(context: Context): PendingIntent {
        val intent = Intent(RideHudExtension.ACTION_CYCLE_BRIGHTNESS).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TYPE_ID = "glasses"
    }
}
