package com.eider.karoomaverickhud.settings

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eider.karoomaverickhud.maverick.MaverickDevice
import com.eider.karoomaverickhud.maverick.MaverickScanner
import com.eider.karoomaverickhud.maverick.MaverickLink
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Our own Maverick pairing UI, replacing the SDK's scan screen (whose list doesn't render
 * on the Karoo). Scans, lists found glasses, and on tap configures + connects via the same
 * calls the SDK makes (setDeviceInfo + connectSecured).
 *
 * It also handles BLE bonding itself: the Maverick uses passkey-entry pairing (the glasses
 * display a code, the peer must enter it), and the Karoo's system UI gives no way to type
 * it. We intercept [BluetoothDevice.ACTION_PAIRING_REQUEST] and prompt for the code here.
 */
@Composable
fun MaverickPairDialog(onDismiss: () -> Unit, onPaired: (address: String, name: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<MaverickDevice>() }
    var connectingAddress by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Scanning for glasses…") }

    // Set when the BLE stack asks us to enter the passkey shown on the glasses.
    var pinDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var pinInput by remember { mutableStateOf("") }

    // Intercept pairing requests for the whole time the dialog is open.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Timber.i("PAIRING_REQUEST variant=$variant device=${device?.address}")
                when (variant) {
                    // Numeric comparison / consent: confirm automatically (may need privilege).
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION, 3 -> {
                        runCatching { device?.setPairingConfirmation(true); abortBroadcast() }
                            .onFailure { Timber.w(it, "setPairingConfirmation failed") }
                    }
                    // PIN / passkey entry: ask the user for the code shown on the glasses.
                    BluetoothDevice.PAIRING_VARIANT_PIN, 1 -> {
                        runCatching { abortBroadcast() }
                        pinInput = ""
                        pinDevice = device
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // Scan while idle; pause scanning during a connection attempt.
    androidx.compose.runtime.LaunchedEffect(connectingAddress) {
        if (connectingAddress != null) return@LaunchedEffect
        MaverickScanner.scan(ctx).collect { found ->
            val i = devices.indexOfFirst { it.address == found.address }
            if (i >= 0) devices[i] = found else devices.add(found)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Pair Maverick") },
        text = {
            Column {
                Text(status, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                val promptDevice = pinDevice
                if (promptDevice != null) {
                    // Passkey entry: the glasses show a code; the user types it here.
                    Text("Enter the code shown on the glasses:")
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter(Char::isDigit) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Row {
                        TextButton(
                            enabled = pinInput.isNotEmpty(),
                            onClick = {
                                @Suppress("MissingPermission")
                                val ok = runCatching { promptDevice.setPin(pinInput.toByteArray()) }
                                    .getOrDefault(false)
                                Timber.i("setPin(${pinInput.length} digits) ok=$ok")
                                pinDevice = null
                                status = "Pairing…"
                            },
                        ) { Text("Confirm code") }
                    }
                } else if (connectingAddress == null) {
                    if (devices.isEmpty()) {
                        Text("No glasses found yet. Make sure they're on and awake.")
                    }
                    devices.sortedByDescending { it.rssi }.forEach { device ->
                        Text(
                            text = "${device.name}   ${device.rssi} dBm",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    connectingAddress = device.address
                                    status = "Connecting to ${device.name}…\n" +
                                        "When the glasses show a code, enter it here."
                                    scope.launch {
                                        val ok = connectMaverick(device) { err ->
                                            status = "Glasses error: $err"
                                        }
                                        if (ok) {
                                            onPaired(device.address, device.name)
                                        } else {
                                            if (!status.startsWith("Glasses error")) {
                                                status = "Couldn't connect to ${device.name}. Tap a device to retry."
                                            }
                                            connectingAddress = null
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Configure + connect exactly as the SDK's scan screen does — including registering a
 * communication-events listener, which the SDK's own screen does before connecting (and
 * which the connection state machine needs to complete the secured handshake). We resolve
 * on [onConnected]/[onFailedToConnect] rather than polling, and log every transition.
 */
private suspend fun connectMaverick(device: MaverickDevice, onError: (String) -> Unit): Boolean {
    val comm = Evs.instance().comm()
    MaverickLink.lastError.value = null
    Timber.i("connectMaverick ${device.address} ready=${runCatching { Evs.instance().isReady() }.getOrNull()}")

    val started = runCatching {
        comm.setDeviceInfo(device.address, device.name)
        comm.connectSecured()
    }.getOrElse { Timber.w(it, "connectSecured failed"); false }
    Timber.i("connectSecured(${device.address}) started=$started")

    // Persistent listeners in [MaverickLink] drive the state; we just wait for auth to
    // finish (onReady) or an error. ~90s covers bonding (code entry) + the cert handshake.
    return withTimeoutOrNull(90_000L) {
        var ok = false
        while (true) {
            if (MaverickLink.ready.value) { ok = true; break }
            val err = MaverickLink.lastError.value
            if (err != null) { onError(err); ok = false; break }
            delay(300)
        }
        ok
    } ?: false
}
