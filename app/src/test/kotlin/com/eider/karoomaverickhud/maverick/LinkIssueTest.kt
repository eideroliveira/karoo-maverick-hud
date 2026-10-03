package com.eider.karoomaverickhud.maverick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies SDK errors turn into rider-facing issues that say why and what to do. */
class LinkIssueTest {

    @Test
    fun offlineActivationAfterUpdateSaysAppWasUpdated() {
        val issue = LinkIssue.fromSdkError("ActivationError", CertStatus.WrongVersion("0.1.5", 0L), online = false)
        assertEquals("needs-internet", issue.id)
        assertEquals("Needs internet", issue.title)
        assertTrue(issue.detail.contains("app was updated"))
        assertTrue("retries can't help until online", issue.waitsForNetwork)
        assertTrue(issue.alert)
    }

    @Test
    fun offlineActivationWithExpiredCertSaysExpired() {
        val issue = LinkIssue.fromSdkError("ActivationError", CertStatus.Expired(0L), online = false)
        assertTrue(issue.detail.contains("expired"))
    }

    @Test
    fun offlineActivationWithNoCertSaysFirstSignIn() {
        val issue = LinkIssue.fromSdkError("ActivationError", CertStatus.Missing, online = false)
        assertTrue(issue.detail.contains("haven't signed in online"))
    }

    @Test
    fun onlineActivationFailureIsAServerProblemNotConnectivity() {
        val issue = LinkIssue.fromSdkError("ActivationError", CertStatus.Missing, online = true)
        assertEquals("Sign-in failed", issue.title)
        assertFalse(issue.waitsForNetwork)
    }

    @Test
    fun keyAndGlassesErrorsMapToSpecificIssues() {
        assertEquals(LinkIssue.NO_KEY, LinkIssue.fromSdkError("ApiKeyMissing", null, online = true))
        assertEquals("key-rejected", LinkIssue.fromSdkError("ApiKeyInvalid", null, online = true).id)
        assertEquals("key-rejected", LinkIssue.fromSdkError("ApiKeyExpired", null, online = true).id)
        assertEquals("other-app", LinkIssue.fromSdkError("OtherAppIsConnected", null, online = true).id)
        assertEquals("low-battery", LinkIssue.fromSdkError("DisplayErrLowBat", null, online = true).id)
    }

    @Test
    fun glassesOffIsTileOnly() {
        assertFalse(LinkIssue.fromSdkError("FailedToConnect", null, online = true).alert)
    }

    @Test
    fun unknownCodeStillExplains() {
        val issue = LinkIssue.fromSdkError("SyncError", null, online = true)
        assertEquals("Glasses error", issue.title)
        assertTrue(issue.detail.contains("SyncError"))
    }

    @Test
    fun titlesFitTheTile() {
        val codes = listOf(
            "ActivationError", "ApiKeyMissing", "ApiKeyInvalid", "ApiKeyExpired", "ApiKeyBadSerial",
            "OtherAppIsConnected", "OldSDK", "DisplayErrLowBat", "FailedPairing", "FailedToConnect",
            "GeneralError", "SyncError",
        )
        val issues = codes.flatMap { c -> listOf(true, false).map { LinkIssue.fromSdkError(c, null, it) } } +
            listOf(LinkIssue.NO_KEY, LinkIssue.BLUETOOTH_OFF, LinkIssue.SDK_FAILED)
        for (i in issues) assertTrue("'${i.title}' too long for the tile", i.title.length <= 14)
    }
}
