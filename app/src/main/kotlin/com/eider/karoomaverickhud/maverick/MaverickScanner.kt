package com.eider.karoomaverickhud.maverick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/** A Maverick advertised on BLE. */
data class MaverickDevice(val address: String, val name: String, val rssi: Int)

/**
 * Scans for Everysight Maverick glasses directly via Android BLE, filtered on the
 * EvsKit service UUID (the same filter the SDK uses). We do this ourselves because the
 * SDK's bundled scan/pair screen doesn't render its device list on the Karoo.
 *
 * Caller must hold BLUETOOTH_SCAN (+ BLUETOOTH_CONNECT for the name) and have Location on.
 */
object MaverickScanner {
    // UIKit.app.data.BTConstants.serviceUUID
    private val SERVICE_UUID = ParcelUuid.fromString("e73091e0-45e9-f9aa-514b-fa5349b08e50")

    @SuppressLint("MissingPermission") // permissions are gated by the caller before scanning
    fun scan(context: Context): Flow<MaverickDevice> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("No BLE scanner (adapter off?)")
            close()
            return@callbackFlow
        }

        val filters = listOf(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: "Maverick"
                trySend(MaverickDevice(device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("BLE scan failed: $errorCode")
                close()
            }
        }

        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { Timber.w(it, "startScan failed"); close(it) }

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
