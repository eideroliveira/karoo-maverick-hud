package com.eider.karoomaverickhud.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eider.karoomaverickhud.R
import com.eider.karoomaverickhud.maverick.GlassesLinkState
import com.eider.karoomaverickhud.settings.SettingsActivity
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A tappable ride data field that shows the Maverick link status and, when tapped,
 * opens the app and kicks off pairing. Putting it on a ride page is the practical way
 * to pair on a Karoo 3: a BLE scan only works while Location/GPS is on, and the Karoo
 * only powers GPS during a ride — so there's no menu toggle, but in-ride there is a fix.
 */
class GlassesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            GlassesLinkState.connected.collect { connected ->
                val views = RemoteViews(context.packageName, R.layout.view_glasses_field)
                views.setTextViewText(
                    R.id.glasses_field_text,
                    if (connected) {
                        context.getString(R.string.field_status_connected)
                    } else {
                        context.getString(R.string.field_status_disconnected)
                    },
                )
                // No tap target in the page-editor preview; only attach it for the live field.
                if (!config.preview) {
                    views.setOnClickPendingIntent(R.id.glasses_field_root, pairPendingIntent(context))
                }
                emitter.updateView(views)
            }
        }
        emitter.setCancellable {
            Timber.d("stop glasses view")
            job.cancel()
        }
    }

    private fun pairPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SettingsActivity.EXTRA_AUTO_PAIR, true)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TYPE_ID = "glasses"
    }
}
