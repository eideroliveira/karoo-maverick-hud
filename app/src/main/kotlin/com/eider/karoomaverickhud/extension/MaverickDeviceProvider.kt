package com.eider.karoomaverickhud.extension

import android.content.Context
import com.eider.karoomaverickhud.maverick.GlassesLinkState
import com.eider.karoomaverickhud.maverick.MaverickBridge
import com.eider.karoomaverickhud.maverick.MaverickLink
import com.eider.karoomaverickhud.maverick.MaverickScanner
import com.eider.karoomaverickhud.settings.HudPreferences
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnManufacturerInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Exposes the Maverick glasses as a Karoo device, so the rider pairs and sees them in the native
 * Settings → Sensors flow (status + battery + manufacturer info) instead of going through the
 * Extensions menu.
 *
 * The link itself stays owned by [MaverickBridge] (always-connected, prefs-driven). This provider
 * is a registration + reporting surface:
 *  - [startScan] feeds discovered glasses into the native "Add Sensor" list.
 *  - [connectDevice] records the pairing the bridge already reads (so the loop connects) and then
 *    mirrors the live link state back to Karoo.
 *  - Disconnection (the emitter being cancelled) just stops reporting — it deliberately does NOT
 *    drop the EvsKit link or unpair, so the platform's sensor lifecycle can't quietly turn the
 *    "always connected" behaviour back into ride-gating. Unpairing stays an explicit action in the
 *    settings app.
 */
class MaverickDeviceProvider(
    private val context: Context,
    private val scope: CoroutineScope,
    private val maverick: MaverickBridge,
    private val extension: String,
) {
    /** Names seen during the last scan, so [connectDevice] (which only gets a uid) can label the pairing. */
    private val nameByUid = ConcurrentHashMap<String, String>()

    private val batteryDataTypeId get() = DataType.dataTypeId(extension, BATTERY_TYPE_ID)

    fun startScan(emitter: Emitter<Device>) {
        val job = scope.launch {
            MaverickScanner.scan(context).collect { found ->
                nameByUid[found.address] = found.name
                emitter.onNext(
                    Device(
                        extension = extension,
                        uid = found.address,
                        dataTypes = listOf(batteryDataTypeId),
                        displayName = found.name,
                    ),
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        val name = nameByUid[uid] ?: "Maverick"
        // Record the pairing the bridge's connection loop reads, then nudge it to connect now.
        scope.launch { HudPreferences.setPairedDevice(context, uid, name) }
        maverick.requestConnect()

        emitter.onNext(
            OnManufacturerInfo(
                ManufacturerInfo(
                    manufacturer = "Everysight",
                    modelNumber = "Maverick",
                    serialNumber = MaverickLink.serial.value,
                ),
            ),
        )

        // Mirror the live link state to Karoo: connection status, the battery indicator (status
        // enum) and a numeric battery field (data point). Both battery forms come off the same
        // GlassesLinkState the bridge keeps fresh.
        val statusJob = scope.launch {
            // StateFlow already conflates equal values, so each collect only sees real changes.
            GlassesLinkState.connected.collect { connected ->
                val status = if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.SEARCHING
                emitter.onNext(OnConnectionStatus(status))
            }
        }
        val batteryJob = scope.launch {
            GlassesLinkState.battery.collect { pct ->
                if (pct != null) {
                    emitter.onNext(OnBatteryStatus(BatteryStatus.fromPercentage(pct)))
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                dataTypeId = batteryDataTypeId,
                                values = mapOf(DataType.Field.SINGLE to pct.toDouble()),
                                sourceId = uid,
                            ),
                        ),
                    )
                }
            }
        }
        emitter.setCancellable {
            Timber.d("Maverick device $uid disconnected by Karoo; link stays under bridge control")
            statusJob.cancel()
            batteryJob.cancel()
        }
    }

    companion object {
        /** Matches the `glasses-battery` DataType typeId declared in extension_info.xml. */
        const val BATTERY_TYPE_ID = "glasses-battery"
    }
}
