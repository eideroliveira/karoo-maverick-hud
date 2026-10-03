package com.eider.karoomaverickhud.maverick

/**
 * Why the glasses can't be brought up, phrased for the rider. Auth failures can't be drawn on the
 * glasses (they render nothing until signed in), so these surface on the Karoo: the status tile,
 * a Control Center notification, an in-ride alert, and the settings Glasses screen.
 */
data class LinkIssue(
    /** Stable identity, so the same failure repeating on every retry alerts only once. */
    val id: String,
    /** Short enough for the data-field tile (≤ 14 chars). */
    val title: String,
    /** One or two sentences for notifications and settings: what happened and what to do. */
    val detail: String,
    /** Worth a Karoo notification; false for routine states the tile alone should show. */
    val alert: Boolean = true,
    /** Retrying can't succeed until the Karoo has internet, so the reconnect loop should back off. */
    val waitsForNetwork: Boolean = false,
) {
    companion object {
        private const val GET_ONLINE = "Connect the Karoo to Wi-Fi or your phone's hotspot."

        val NO_KEY = LinkIssue(
            id = "no-key",
            title = "No app key",
            detail = "This build has no Everysight app key (res/raw/app.key), so the glasses can't sign in.",
        )

        val BLUETOOTH_OFF = LinkIssue(
            id = "bluetooth-off",
            title = "Bluetooth off",
            detail = "Turn on Bluetooth on the Karoo to reach the glasses.",
        )

        val SDK_FAILED = LinkIssue(
            id = "sdk-failed",
            title = "SDK error",
            detail = "The glasses SDK failed to start. Restart the Karoo; if it persists, reinstall the app.",
        )

        /**
         * Map an SDK `AppErrorCode` (by [code] name) to a rider-facing issue. [cert] is the cached
         * certificate's state and [online] whether the Karoo has internet — together they say *why*
         * an activation failed, which the SDK's single ActivationError code doesn't.
         */
        fun fromSdkError(code: String, cert: CertStatus?, online: Boolean): LinkIssue = when (code) {
            "ActivationError" ->
                if (online) {
                    LinkIssue(
                        id = "activation-online",
                        title = "Sign-in failed",
                        detail = "Couldn't get the glasses certificate from Everysight. Check the Karoo's internet; retrying.",
                    )
                } else {
                    needsInternet(cert)
                }
            "ApiKeyMissing" -> NO_KEY
            "ApiKeyInvalid", "ApiKeyExpired" -> LinkIssue(
                id = "key-rejected",
                title = "Key rejected",
                detail = "The glasses or Everysight rejected this app's key ($code). The key may be invalid or revoked.",
            )
            "ApiKeyBadSerial" -> LinkIssue(
                id = "bad-serial",
                title = "Not authorised",
                detail = "These glasses aren't authorised for this app's key.",
            )
            "OtherAppIsConnected" -> LinkIssue(
                id = "other-app",
                title = "Glasses busy",
                detail = "Another app is connected to the glasses. Close the Everysight app on your phone, then tap the Glasses field.",
            )
            "OldSDK" -> LinkIssue(
                id = "old-sdk",
                title = "Update app",
                detail = "The glasses firmware needs a newer version of this app's glasses SDK.",
            )
            "DisplayErrLowBat" -> LinkIssue(
                id = "low-battery",
                title = "Low battery",
                detail = "The glasses battery is too low to drive the display. Charge them.",
            )
            "FailedPairing" -> LinkIssue(
                id = "pairing-failed",
                title = "Pairing failed",
                detail = "Bluetooth pairing with the glasses failed. Re-pair from the Glasses settings.",
            )
            // The glasses being off or out of range is routine; the tile says so without a notification.
            "FailedToConnect" -> LinkIssue(
                id = "not-found",
                title = "Not found",
                detail = "Couldn't reach the glasses. Make sure they're on and nearby.",
                alert = false,
            )
            else -> LinkIssue(
                id = "sdk-$code",
                title = "Glasses error",
                detail = "The glasses reported an error: $code.",
            )
        }

        /** Offline activation failure, explained by what's wrong with the cached certificate. */
        private fun needsInternet(cert: CertStatus?): LinkIssue {
            val why = when (cert) {
                is CertStatus.WrongVersion ->
                    "The app was updated, so the glasses must sign in online once."
                is CertStatus.Expired ->
                    "The glasses' offline sign-in has expired and must be renewed online."
                else ->
                    "The glasses haven't signed in online with this app yet."
            }
            return LinkIssue(
                id = "needs-internet",
                title = "Needs internet",
                detail = "$why $GET_ONLINE",
                waitsForNetwork = true,
            )
        }
    }
}
