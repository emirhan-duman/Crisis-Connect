package com.auralis.crisisconnect

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.auralis.crisisconnect.data.hasAnyBleGattContacts
import com.auralis.crisisconnect.data.normalizeClassicCapableBleContacts
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.security.CertificateProvisioningNotifier
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

        // Global safety-net: forward uncaught exceptions to Crashlytics before the
        // default handler terminates the process.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                FirebaseCrashlytics.getInstance().recordException(throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val savedLanguage = runCatching {
            getSavedLanguageSync(this)
        }.getOrDefault("en")
        setLocale(this, savedLanguage, shouldRecreate = false)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            RfcommTelecomCoordinator.initialize(this)
        }
        if (!initializeMapLibreSafely(this, TAG)) {
            Log.w(TAG, "Map runtime is unavailable on this device. Map features will be disabled.")
        }
        attachCertificateAutoProvisioner()
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
