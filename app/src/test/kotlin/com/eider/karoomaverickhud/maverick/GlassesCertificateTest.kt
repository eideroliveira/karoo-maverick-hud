package com.eider.karoomaverickhud.maverick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Verifies we predict the SDK's verdict on a cached glasses certificate (expiry, then app version). */
class GlassesCertificateTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_790_000_000_000L

    /** An unsigned JWT with the given payload — the SDK checks the signature, we only read claims. */
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        return "$header.${enc.encodeToString(payload.toByteArray())}.c2ln"
    }

    private fun cert(expMs: Long, appVersion: String = "0.1.6") =
        jwt("""{"glassesSerial":"MAV123","appNamespace":"com.eider.karoomaverickhud","appVersion":"$appVersion","exp":${expMs / 1000}}""")

    @Test
    fun missingOrBlankIsMissing() {
        assertEquals(CertStatus.Missing, GlassesCertificate.inspect(null, "0.1.6", now))
        assertEquals(CertStatus.Missing, GlassesCertificate.inspect("  ", "0.1.6", now))
    }

    @Test
    fun garbageIsUnreadable() {
        assertEquals(CertStatus.Unreadable, GlassesCertificate.inspect("not-a-jwt", "0.1.6", now))
        assertEquals(CertStatus.Unreadable, GlassesCertificate.inspect(jwt("""{"exp":1}"""), "0.1.6", now))
    }

    @Test
    fun matchingVersionInDateIsValid() {
        val st = GlassesCertificate.inspect(cert(now + 30 * day), "0.1.6", now)
        assertTrue(st is CertStatus.Valid)
        assertTrue(GlassesCertificate.offlineReady(st, now))
        assertFalse("a month left needs no refresh", GlassesCertificate.needsRefresh(st, now))
        assertEquals(30, GlassesCertificate.daysLeft(st, now))
    }

    @Test
    fun wildcardVersionMatchesAnyApp() {
        val st = GlassesCertificate.inspect(cert(now + 30 * day, appVersion = "*"), "9.9.9", now)
        assertTrue(st is CertStatus.Valid)
    }

    @Test
    fun appUpdateInvalidatesCertificate() {
        // Issued for 0.1.5; the app is now 0.1.6 — the SDK refuses it until re-fetched online.
        val st = GlassesCertificate.inspect(cert(now + 30 * day, appVersion = "0.1.5"), "0.1.6", now)
        assertEquals(CertStatus.WrongVersion("0.1.5", now + 30 * day), st)
        assertFalse(GlassesCertificate.offlineReady(st, now))
        assertTrue(GlassesCertificate.needsRefresh(st, now))
        assertNull(GlassesCertificate.daysLeft(st, now))
    }

    @Test
    fun expiryWinsOverVersion() {
        // The SDK checks expiry first, so an expired cert for another version reads as Expired.
        val st = GlassesCertificate.inspect(cert(now - day, appVersion = "0.1.5"), "0.1.6", now)
        assertEquals(CertStatus.Expired(now - day), st)
        assertFalse(GlassesCertificate.offlineReady(st, now))
    }

    @Test
    fun validButCloseToExpiryWantsRefresh() {
        val st = GlassesCertificate.inspect(cert(now + 3 * day), "0.1.6", now)
        assertTrue(GlassesCertificate.offlineReady(st, now))
        assertTrue("under a week left — top it up while online", GlassesCertificate.needsRefresh(st, now))
        assertEquals(3, GlassesCertificate.daysLeft(st, now))
    }

    @Test
    fun summaryExplainsState() {
        val fmt: (Long) -> String = { "DATE" }
        assertNull(GlassesCertificate.summary(null, now, online = false, formatDate = fmt))
        assertEquals(
            "Offline sign-in ready until DATE",
            GlassesCertificate.summary(CertStatus.Valid(now + day), now, online = false, formatDate = fmt),
        )
        assertEquals(
            "Offline sign-in needs renewing after the app update — connect the Karoo to the internet",
            GlassesCertificate.summary(CertStatus.WrongVersion("0.1.5", now + day), now, online = false, formatDate = fmt),
        )
        assertEquals(
            "Offline sign-in expired — renewing while online",
            GlassesCertificate.summary(CertStatus.Expired(now - day), now, online = true, formatDate = fmt),
        )
    }
}
