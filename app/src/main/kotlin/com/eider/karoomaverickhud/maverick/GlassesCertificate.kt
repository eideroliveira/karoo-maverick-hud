package com.eider.karoomaverickhud.maverick

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * What the Everysight SDK's cached glasses certificate allows right now.
 *
 * Neither app.key nor sdk.key authenticates the glasses on its own: the SDK trades the key for a
 * per-glasses certificate (a JWT from Everysight's server) and caches it. Offline sign-in works only
 * while that cached certificate is valid — the SDK rejects it once it has expired, or when it was
 * issued for a different app version (so an app update needs one online sign-in).
 */
sealed interface CertStatus {
    /** Nothing cached for these glasses — the next sign-in must go online. */
    data object Missing : CertStatus

    /** Cached, but not a certificate we can read — the SDK will treat it as missing. */
    data object Unreadable : CertStatus

    /** Issued for another app version; the SDK refuses it after an update until re-fetched online. */
    data class WrongVersion(val certVersion: String, val expMs: Long) : CertStatus

    /** Past its expiry; the SDK must fetch a new one online. */
    data class Expired(val expMs: Long) : CertStatus

    /** Usable offline until [expMs]. */
    data class Valid(val expMs: Long) : CertStatus
}

object GlassesCertificate {
    /**
     * Fetch a replacement once a valid certificate has less than this left. The SDK only refreshes
     * inside 4 days, and only while the glasses are connecting; a week gives the prefetch a few
     * online moments to catch it before an offline ride.
     */
    const val REFRESH_AHEAD_MS = 7L * 24 * 60 * 60 * 1000

    /** Below this many whole days of offline validity left, warn on the glasses' waiting screen. */
    const val WARN_DAYS = 4

    /**
     * Mirror of the SDK's checks on a cached certificate [jwt] (expiry first, then app version),
     * without verifying the signature — the SDK does that; we only need to predict its verdict.
     */
    fun inspect(jwt: String?, appVersion: String, nowMs: Long): CertStatus {
        if (jwt.isNullOrBlank()) return CertStatus.Missing
        val claims = decodeClaims(jwt) ?: return CertStatus.Unreadable
        val expSec = claims["exp"]?.jsonPrimitive?.longOrNull ?: return CertStatus.Unreadable
        val certVersion = claims["appVersion"]?.jsonPrimitive?.contentOrNull ?: return CertStatus.Unreadable
        val expMs = expSec * 1000
        return when {
            expMs <= nowMs -> CertStatus.Expired(expMs)
            certVersion != "*" && certVersion != appVersion -> CertStatus.WrongVersion(certVersion, expMs)
            else -> CertStatus.Valid(expMs)
        }
    }

    /** True when the glasses can sign in without internet. */
    fun offlineReady(status: CertStatus?, nowMs: Long): Boolean =
        status is CertStatus.Valid && status.expMs > nowMs

    /** True when a fresh certificate is worth fetching the next time the Karoo is online. */
    fun needsRefresh(status: CertStatus?, nowMs: Long): Boolean =
        status !is CertStatus.Valid || status.expMs - nowMs < REFRESH_AHEAD_MS

    /** Whole days of offline validity left (0 when less than a day), or null when not offline-ready. */
    fun daysLeft(status: CertStatus?, nowMs: Long): Int? {
        if (!offlineReady(status, nowMs)) return null
        return (((status as CertStatus.Valid).expMs - nowMs) / (24L * 60 * 60 * 1000)).toInt()
    }

    /**
     * One line for the settings Glasses screen on whether the glasses can sign in offline, or null
     * before any glasses have signed in. [formatDate] renders the expiry (kept out for locale-free
     * tests).
     */
    fun summary(status: CertStatus?, nowMs: Long, online: Boolean, formatDate: (Long) -> String): String? {
        if (status == null) return null
        if (offlineReady(status, nowMs)) {
            return "Offline sign-in ready until ${formatDate((status as CertStatus.Valid).expMs)}"
        }
        val why = when (status) {
            is CertStatus.WrongVersion -> "Offline sign-in needs renewing after the app update"
            is CertStatus.Expired -> "Offline sign-in expired"
            else -> "Offline sign-in not set up"
        }
        return if (online) "$why — renewing while online" else "$why — connect the Karoo to the internet"
    }

    private fun decodeClaims(jwt: String): JsonObject? = runCatching {
        val payload = jwt.trim().split('.').getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        Json.parseToJsonElement(json).jsonObject
    }.getOrNull()
}
