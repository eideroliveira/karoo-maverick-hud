package com.eider.karoomaverickhud.maverick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [CertificateKeeper] reaches EvsKit internals by reflection (the certificate downloader and the
 * SDK's preference store). At runtime a mismatch only degrades to a log line, so this pins the
 * members it needs: if an EvsKit update renames them, this fails at build time instead of offline
 * sign-in silently going back to "needs internet".
 */
class EvsInternalsContractTest {

    // initialize = false: only the shape matters, and static init needs an Android runtime.
    private fun cls(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    @Test
    fun authServiceHoldsDownloaderAndConfig() {
        val auth = cls("UIKit.internal.services.AuthService")
        assertEquals(
            "UIKit.internal.managers.EvsCertificateHelper",
            auth.getDeclaredField("evsCertificateHelper").type.name,
        )
        assertEquals(
            "UIKit.internal.services.ActivationRequestConfig",
            auth.getDeclaredField("activationRequestConfig").type.name,
        )
    }

    @Test
    fun activationConfigExposesServerAndKey() {
        val config = cls("UIKit.internal.services.ActivationRequestConfig")
        assertNotNull(config.getMethod("getBaseUrl"))
        assertNotNull(config.getMethod("getPublicKeyForApiToken"))
    }

    @Test
    fun downloaderTakesKeyConfigSerialAppAndCallback() {
        val helper = cls("UIKit.internal.managers.EvsCertificateHelper")
        val activate = helper.methods.single { it.name == "activateAppOnline" }
        // (apiKey, publicKey, baseUrl, serial, namespace, appVersion, onCompleted)
        assertEquals(List(6) { String::class.java } + Function2::class.java, activate.parameterTypes.toList())

        val result = cls("UIKit.internal.managers.EvsCertificateHelper\$CertificateValidationResult")
        assertNotNull(result.getMethod("getRes"))
        assertEquals(Boolean::class.javaPrimitiveType, result.getMethod("getHasError").returnType)
    }

    @Test
    fun sdkPreferencesReadable() {
        val os = cls("UIKit.app.global.EvsSdkCoreKt").getMethod("getOs").returnType
        val prefs = os.getMethod("getPreferences").returnType
        assertEquals(String::class.java, prefs.getMethod("loadString", String::class.java).returnType)
    }
}
