package com.eider.karoomaverickhud.maverick

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.everysight.evskit.android.Evs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the glasses able to sign in without internet.
 *
 * The SDK only fetches the per-glasses certificate (see [CertStatus]) while the glasses are mid
 * sign-in, so if the Karoo happens to be offline at that moment — the first connect after an app
 * update, or once the cached certificate lapses — sign-in fails on the road. This fetches it ahead
 * of time, whenever the Karoo is online and the cached one is missing, stale or for another app
 * version, using the SDK's own downloader so it lands in the SDK's cache exactly as a sign-in would
 * leave it. The glasses needn't be on; only their serial (remembered from the last sign-in).
 *
 * The downloader and cache live in SDK internals (UIKit.internal.*), so they're reached by
 * reflection and every call is guarded: if an SDK update moves them, this degrades to logging and
 * the SDK's own online sign-in still works as before.
 */
object CertificateKeeper {
    private const val PREFS = "maverick_link"
    private const val KEY_SERIAL = "glasses_serial"

    // The SDK's cache keys (EvsCertificateHelper): "<prefix>-<serial>" per glasses, and a
    // comma-separated list of every serial it has cached.
    private const val SDK_CERT_KEY = "glassesActivationCertificate"
    private const val SDK_SERIALS_KEY = "serialsList"

    /** Don't re-hit the server more often than this while a refresh keeps failing. */
    private const val RETRY_GAP_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<CertStatus?>(null)
    /** Cached certificate state for the last-known glasses; null until a pair of glasses has signed in. */
    val status: StateFlow<CertStatus?> = _status.asStateFlow()

    private val _online = MutableStateFlow(false)
    /** Whether the Karoo's default network has internet. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private lateinit var appContext: Context
    private var apiKey: String? = null
    private var appVersion: String = ""
    @Volatile private var fetching = false
    @Volatile private var lastAttemptAt = 0L

    fun install(context: Context, key: ByteArray?) {
        appContext = context.applicationContext
        apiKey = key?.decodeToString()
        appVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty()
        watchNetwork()
        refresh("startup")
    }

    /** Remember the glasses' serial at sign-in, so the certificate can be refreshed while they're off. */
    fun rememberSerial(serial: String) {
        if (!::appContext.isInitialized || serial.isBlank()) return
        prefs().edit().putString(KEY_SERIAL, serial).apply()
    }

    /** Re-read the cached certificate and, if online and it needs it, fetch a fresh one. */
    fun refresh(reason: String) {
        if (!::appContext.isInitialized) return
        scope.launch {
            runCatching { refreshNow(reason) }.onFailure { Timber.w(it, "Certificate refresh ($reason) failed") }
        }
    }

    /** Current certificate state, re-read from the SDK cache (for classifying an error as it lands). */
    fun currentStatus(): CertStatus? {
        val serial = knownSerial() ?: return null
        return GlassesCertificate.inspect(cachedCertificate(serial), appVersion, System.currentTimeMillis())
            .also { _status.value = it }
    }

    private fun refreshNow(reason: String) {
        val serial = knownSerial() ?: run {
            Timber.i("Certificate ($reason): no glasses serial yet — first sign-in must be online")
            return
        }
        val now = System.currentTimeMillis()
        val st = currentStatus()
        Timber.i("Certificate ($reason) serial=$serial app=$appVersion status=$st online=${_online.value}")
        if (!GlassesCertificate.needsRefresh(st, now)) return
        if (!_online.value || fetching || now - lastAttemptAt < RETRY_GAP_MS) return
        lastAttemptAt = now
        fetch(serial)
    }

    /** Download a certificate through the SDK's own `activateAppOnline`, so it's validated and cached. */
    private fun fetch(serial: String) {
        val key = apiKey ?: run { Timber.w("Certificate fetch skipped: no API key"); return }
        fetching = true
        runCatching {
            val auth = Evs.instance().auth()
            val helper = field(auth, "evsCertificateHelper")
            val config = field(auth, "activationRequestConfig")
            val baseUrl = config.javaClass.getMethod("getBaseUrl").invoke(config) as String
            val publicKey = config.javaClass.getMethod("getPublicKeyForApiToken").invoke(config) as String
            val activate = helper.javaClass.methods.first { it.name == "activateAppOnline" }
            val onDone: (Any?, Any?) -> Unit = { result, _ -> onFetched(serial, result) }
            Timber.i("Certificate fetch for $serial (app $appVersion)")
            activate.invoke(helper, key, publicKey, baseUrl, serial, appContext.packageName, appVersion, onDone)
        }.onFailure {
            fetching = false
            Timber.w(it, "Certificate fetch couldn't start (SDK internals changed?)")
        }
    }

    private fun onFetched(serial: String, result: Any?) {
        fetching = false
        val res = runCatching { result!!.javaClass.getMethod("getRes").invoke(result) }.getOrNull()
        val failed = runCatching { result!!.javaClass.getMethod("getHasError").invoke(result) as Boolean }.getOrDefault(true)
        Timber.i("Certificate fetch for $serial done: res=$res failed=$failed")
        if (!failed) lastAttemptAt = 0L
        currentStatus()
    }

    private fun knownSerial(): String? =
        prefs().getString(KEY_SERIAL, null)?.takeIf { it.isNotBlank() }
            ?: sdkPref(SDK_SERIALS_KEY)?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }

    private fun cachedCertificate(serial: String): String? = sdkPref("$SDK_CERT_KEY-$serial")

    /** Read a string from the SDK's own preference store (OsCoreServices.getPreferences()). */
    private fun sdkPref(key: String): String? = runCatching {
        val os = Class.forName("UIKit.app.global.EvsSdkCoreKt").getMethod("getOs").invoke(null)!!
        val getPrefs = os.javaClass.getMethod("getPreferences")
        val prefs = getPrefs.invoke(os)!!
        // Resolve loadString on the declared interface, not the (possibly non-public) implementation.
        getPrefs.returnType.getMethod("loadString", String::class.java).invoke(prefs, key) as String?
    }.onFailure { Timber.w(it, "SDK preference read failed") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun field(target: Any, name: String): Any {
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return f.get(target)!!
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Track internet on the default network; coming online is the moment to top up the certificate. */
    private fun watchNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        _online.value = hasInternet(cm.getNetworkCapabilities(cm.activeNetwork))
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = hasInternet(caps)
                    val cameOnline = now && !_online.value
                    _online.value = now
                    if (cameOnline) refresh("online")
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }.onFailure { Timber.w(it, "Network callback registration failed") }
    }

    private fun hasInternet(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
