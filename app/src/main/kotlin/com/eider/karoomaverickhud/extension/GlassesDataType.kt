package com.eider.karoomaverickhud.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eider.karoomaverickhud.R
import com.eider.karoomaverickhud.maverick.GlassesLinkState
import com.eider.karoomaverickhud.maverick.HudState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A ride data field that mirrors what the glasses are showing — the active page's cells, laid
 * out corner-style — so the HUD can be sanity-checked on the Karoo itself. A single tap flips
 * to the next page; to connect/disconnect, use the "Pair glasses" ride-menu action (Karoo data
 * fields are RemoteViews, which only support a single click — no long-press).
 */
class GlassesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            GlassesLinkState.connected.combine(HudState.snapshot) { connected, snap -> connected to snap }
                .collect { (connected, snap) ->
                    val views = RemoteViews(context.packageName, R.layout.view_glasses_field)
                    renderMirror(views, connected, snap)
                    // Live field only: a tap advances the page (no tap target in the editor preview).
                    if (!config.preview) {
                        views.setOnClickPendingIntent(R.id.glasses_field_root, nextPagePendingIntent(context))
                    }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            Timber.d("stop glasses view")
            job.cancel()
        }
    }

    /** Mirror the glasses HUD into the field's corner cells (centre two used for a 5th/6th field). */
    private fun renderMirror(views: RemoteViews, connected: Boolean, snap: HudSnapshot) {
        val status = when {
            !connected -> "Glasses disconnected"
            !snap.recording && !snap.paused -> "Connected\nWaiting for ride"
            else -> ""
        }
        views.setTextViewText(R.id.gf_status, status)

        val page = if (status.isEmpty()) snap.pages.getOrNull(snap.pageIndex).orEmpty() else emptyList()
        fun cell(i: Int): String = page.getOrNull(i)?.let {
            if (it.units.isBlank()) it.value else "${it.value} ${it.units}"
        } ?: ""
        // Corner-first order: 0 TL, 1 TR, 2 BL, 3 BR, 4 ML, 5 MR.
        views.setTextViewText(R.id.gf_tl, cell(0))
        views.setTextViewText(R.id.gf_tr, cell(1))
        views.setTextViewText(R.id.gf_bl, cell(2))
        views.setTextViewText(R.id.gf_br, cell(3))
        views.setTextViewText(R.id.gf_ml, cell(4))
        views.setTextViewText(R.id.gf_mr, cell(5))
    }

    private fun nextPagePendingIntent(context: Context): PendingIntent {
        val intent = Intent(RideHudExtension.ACTION_NEXT_PAGE).setPackage(context.packageName)
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
