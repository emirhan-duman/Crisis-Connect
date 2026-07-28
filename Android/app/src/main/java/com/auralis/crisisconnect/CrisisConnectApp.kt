package com.auralis.crisisconnect

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.hasAnyBleGattContacts
import com.auralis.crisisconnect.data.normalizeClassicCapableBleContacts
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.messaging.PresenceReporter
import com.auralis.crisisconnect.messaging.call.InternetCallManager
import com.auralis.crisisconnect.security.CertificateProvisioningNotifier
import com.auralis.crisisconnect.security.CertificateRenewalWorker
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.auralis.crisisconnect.telecom.RfcommTelecomCoordinator
import com.auralis.crisisconnect.util.initializeMapLibreSafely
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class CrisisConnectApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        SplitCompat.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        installFirebaseAppCheck()

        // Crashlytics – enable early so every subsequent crash is reported.
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        }

        Analytics.init(this)

        // Global safety-net: forward uncaught exceptions to Crashlytics before the
        // default handler terminates the process.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                FirebaseCrashlytics.getInstance().recordException(throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        disableAutofillToPreventComposeAnr()

        val savedLanguage = runCatching {
            getSavedLanguageSync(this)
        }.getOrDefault("en")
        setLocale(this, savedLanguage, shouldRecreate = false)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                clearAutoPinnedSystemLocaleIfNeeded(this@CrisisConnectApp)
            }.onFailure { throwable ->
                Log.w(TAG, "Auto-pinned locale recovery failed", throwable)
            }
            runCatching {
                LocalKeyStorage.getOrCreateRescueDeviceId(this@CrisisConnectApp)
                LocalKeyStorage.getOrCreateP2pDeviceId(this@CrisisConnectApp)
                LocalKeyStorage.getOrCreateP2pSessionCode(this@CrisisConnectApp)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to initialize local device identities", throwable)
            }
            runCatching {
                normalizeClassicCapableBleContacts(this@CrisisConnectApp)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to normalize classic-capable BLE contacts", throwable)
            }
            runCatching {
                if (hasAnyBleGattContacts(this@CrisisConnectApp)) {
                    P2pGattServerService.ensureHosting(this@CrisisConnectApp)
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to bootstrap P2P GATT host", throwable)
            }
            // Warm up the MapLibre native runtime so the first map open is fast. MapLibre
            // enforces UI-thread init (CalledFromWorkerThreadException on this IO dispatcher),
            // but running it synchronously in onCreate blocked background cold starts and
            // ANR'd low-end devices (Crashlytics issue 79031ef1). So: hop to the main thread,
            // then wait for its looper to go IDLE — the warm-up never competes with start-up
            // work. Map screens that open first still lazy-init via createMapViewSafely.
            Handler(Looper.getMainLooper()).post {
                Looper.myQueue().addIdleHandler {
                    runCatching {
                        if (initializeMapLibreSafely(this@CrisisConnectApp, TAG)) {
                            Log.i(TAG, "MapLibre warm-up complete")
                        } else {
                            Log.w(TAG, "Map runtime is unavailable on this device. Map features will be disabled.")
                        }
                    }.onFailure { throwable ->
                        Log.w(TAG, "MapLibre warm-up failed", throwable)
                    }
                    false
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            RfcommTelecomCoordinator.initialize(this)
        }

        // Bring up the internet (WebRTC) call engine HERE — in the Application, which runs on every
        // process start — not only from MainActivity. When the app is swiped away/killed and an incoming
        // internet call arrives, FCM cold-starts the process for CrisisConnectMessagingService alone;
        // MainActivity never runs, so without this the call manager has no app context / signaling sender
        // and the offer dies in createPeerConnection() (and no answer could be sent back). Aggressive
        // OEM process killers (Samsung/Xiaomi/Oppo) make this the common case, not an edge case.
        // init() is lightweight (sets context + sender, warms TURN async); MainActivity's bootstrap
        // re-inits idempotently on launch.
        runCatching {
            InternetCallManager.init(this@CrisisConnectApp) { peer, json ->
                InternetChatTransport(this@CrisisConnectApp).sendCallSignal(peer, json)
            }
        }.onFailure { Log.w(TAG, "Failed to init internet call manager", it) }

        clearStaleCallNotifications()

        attachCertificateAutoProvisioner()
        attachPresenceReporter()
        // Keep an existing rescue certificate fresh in the background (renews when <24h
        // of its 72h validity remains, network permitting). No-op for devices without one.
        CertificateRenewalWorker.schedule(this)
    }

    /**
     * Foreground detection by started-activity counting (no lifecycle-process dependency): drives
     * the "last seen" presence heartbeat — starts on first activity, final stamp when the last stops.
     */
    private fun attachPresenceReporter() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    PresenceReporter.onAppForeground(this@CrisisConnectApp)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    PresenceReporter.onAppBackground(this@CrisisConnectApp)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Opt the whole UI out of platform autofill.
     *
     * Compose's autofill (`AndroidAutofillManager.notifyViewEntered`) performs a synchronous binder
     * call into the system `AutofillManager` when a text field gains focus. On some low-end devices
     * with a slow/unresponsive autofill service this blocks the main thread long enough to ANR
     * (Crashlytics shows the main thread parked in `SyncResultReceiver.waitResult` →
     * `AutofillManager.startSessionLocked`). No stable Compose release fixes this automatically — the
     * only upstream remedy is to suppress autofill events — and the app does not rely on autofill for
     * any of its flows, so we exclude every Activity's view tree from autofill.
     */
    private fun disableAutofillToPreventComposeAnr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                runCatching {
                    activity.window?.decorView?.importantForAutofill =
                        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Watches Firebase Auth state. Whenever a real (non-anonymous) user becomes
     * signed in, kicks off [SecurityRepository.getOrFetchCertificate] in the
     * background so the device-bound rescue certificate is provisioned
     * automatically. Failures are logged and otherwise ignored — the user can
     * still trigger the flow manually from the profile certificate card.
     */
    private fun attachCertificateAutoProvisioner() {
        val lastHandledUid = AtomicReference<String?>(null)
        val inFlight = AtomicReference<Job?>(null)
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user == null || user.isAnonymous) {
                lastHandledUid.set(null)
                inFlight.getAndSet(null)?.cancel()
                return@addAuthStateListener
            }
            val uid = user.uid.trim()
            if (uid.isEmpty()) return@addAuthStateListener
            if (lastHandledUid.getAndSet(uid) == uid && inFlight.get()?.isActive == true) {
                return@addAuthStateListener
            }
            val job = appScope.launch {
                val repo = SecurityRepository(this@CrisisConnectApp)
                val alreadyHadUsable = runCatching { repo.hasUsableStoredCertificate() }
                    .getOrDefault(false)
                if (!alreadyHadUsable) {
                    // The backend only issues certificates to admin/fieldteam (see
                    // functions certificates/issuance.ts), so for everyone else the
                    // attempt is doomed — skip silently instead of spamming a
                    // "could not issue certificate" banner on every sign-in.
                    val role = resolveRescueRole(uid)
                    if (role != "admin" && role != "fieldteam") {
                        Log.i(
                            TAG,
                            "Skipping certificate auto-provision for non-rescue role=${role ?: "unknown"}"
                        )
                        return@launch
                    }
                    CertificateProvisioningNotifier.emitInProgress()
                }
                runCatching {
                    repo.getOrFetchCertificate()
                }.onSuccess {
                    Log.i(TAG, "Auto-provisioned rescue certificate for uid=${uid.take(6)}…")
                    if (!alreadyHadUsable) {
                        CertificateProvisioningNotifier.emitSuccess()
                    }
                }.onFailure { throwable ->
                    // Non-fatal: the user can retry from the profile card.
                    // Common reasons: offline, Play Integrity rejection on this
                    // build (debug APK without Play install), backend missing
                    // service account permission.
                    Log.w(
                        TAG,
                        "Auto-provision of rescue certificate failed for uid=${uid.take(6)}…",
                        throwable
                    )
                    if (!alreadyHadUsable) {
                        CertificateProvisioningNotifier.emitFailure(throwable)
                    }
                }
            }
            inFlight.set(job)
        }
    }

    /**
     * Resolves the user's rescue role the same way the backend does (Firestore
     * users/{uid}.role, with "field-team"/"field_team" normalised to "fieldteam"),
     * falling back to the locally cached role when Firestore is unreachable.
     * Returns null when no role can be determined — treated as "not authorized".
     */
    private suspend fun resolveRescueRole(uid: String): String? {
        val remoteRole = runCatching {
            suspendCancellableCoroutine<String?> { continuation ->
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        continuation.resume(snapshot.getString("role"))
                    }
                    .addOnFailureListener { continuation.resume(null) }
            }
        }.getOrNull()
        val rawRole = remoteRole ?: LocalKeyStorage.getSavedRole(this)
        return rawRole
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace("field-team", "fieldteam")
            ?.replace("field_team", "fieldteam")
            ?.takeIf { it.isNotBlank() }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Removes call notifications orphaned by a process death. Call state never survives the process
     * (both call engines are in-memory), so at Application start ANY ongoing call-category
     * notification is stale by definition. The GATT ongoing/incoming and internet-ring notifications
     * are posted via plain notify() (not attached to a foreground service), so the system does NOT
     * clear them when the process is killed mid-call — without this sweep they linger unswipeably
     * until reboot. Call-ended summaries are kept (category CALL but not ongoing).
     */
    private fun clearStaleCallNotifications() {
        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.activeNotifications
                .filter {
                    it.notification.category == android.app.Notification.CATEGORY_CALL &&
                        (it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
                }
                .forEach { manager.cancel(it.tag, it.id) }
        }.onFailure { Log.w(TAG, "Stale call-notification sweep failed", it) }
    }

    private fun installFirebaseAppCheck() {
        val firebaseApp = FirebaseApp.initializeApp(this) ?: FirebaseApp.getInstance()
        val useDebugAppCheck = BuildConfig.DEBUG && BuildConfig.BUILD_TYPE != INTERNAL_BUILD_TYPE
        if (useDebugAppCheck) {
            seedConfiguredDebugAppCheckToken(firebaseApp)
        }
        val firebaseAppCheck = FirebaseAppCheck.getInstance(firebaseApp)
        val providerFactory = when {
            useDebugAppCheck -> {
                createDebugAppCheckProviderFactory() ?: run {
                    Log.e(TAG, "Debug App Check provider is unavailable; falling back to Play Integrity.")
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                }
            }
            else -> PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        runCatching {
            firebaseAppCheck.installAppCheckProviderFactory(providerFactory, true)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to initialize Firebase App Check", throwable)
        }

        if (useDebugAppCheck) {
            warmUpDebugAppCheckToken(firebaseAppCheck)
        }
    }

    private fun createDebugAppCheckProviderFactory(): AppCheckProviderFactory? {
        return runCatching {
            val factoryClass = Class.forName(
                "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
            )
            val getInstance = factoryClass.getMethod("getInstance")
            getInstance.invoke(null) as AppCheckProviderFactory
        }.onFailure { throwable ->
            Log.w(TAG, "Debug App Check provider lookup failed", throwable)
        }.getOrNull()
    }

    private fun seedConfiguredDebugAppCheckToken(firebaseApp: FirebaseApp) {
        if (!BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == INTERNAL_BUILD_TYPE) {
            return
        }
        val configuredToken = BuildConfig.APP_CHECK_DEBUG_TOKEN.trim()
        if (configuredToken.isBlank()) {
            return
        }
        val prefsName = DEBUG_APP_CHECK_PREFS_TEMPLATE.format(firebaseApp.persistenceKey)
        getSharedPreferences(prefsName, MODE_PRIVATE)
            .edit()
            .putString(DEBUG_APP_CHECK_SECRET_KEY, configuredToken)
            .apply()
        Log.d(TAG, "Seeded Firebase App Check debug token from local configuration.")
    }

    private fun warmUpDebugAppCheckToken(firebaseAppCheck: FirebaseAppCheck) {
        runCatching {
            firebaseAppCheck.getAppCheckToken(false)
                .addOnSuccessListener {
                    Log.d(TAG, "Firebase App Check debug token request succeeded.")
                }
                .addOnFailureListener { throwable ->
                    Log.w(TAG, "Firebase App Check debug token request failed", throwable)
                }
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to warm up Firebase App Check debug token", throwable)
        }
    }

    private companion object {
        private const val TAG = "CrisisConnectApp"
        private const val INTERNAL_BUILD_TYPE = "internal"
        private const val DEBUG_APP_CHECK_PREFS_TEMPLATE =
            "com.google.firebase.appcheck.debug.store.%s"
        private const val DEBUG_APP_CHECK_SECRET_KEY =
            "com.google.firebase.appcheck.debug.DEBUG_SECRET"
    }
}
