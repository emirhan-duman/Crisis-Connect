package com.auralis.crisisconnect.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.AtomicFile
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.database.AgencyRouter
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.security.RescueDeviceRegistry
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.sync.MobileSyncClient
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that continuously syncs rescue telemetry to the Crisis Link Firestore schema.
 * SOS entries are deduplicated by signal document ID (`cc-...`) so the same victim is not counted
 * as a different person when multiple rescuers scan the same signal.
 */
class CrisisLinkForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val observedSignals = mutableMapOf<String, SignalObservation>()
    private var totalScanEventCount: Long = 0L
    private var totalUniqueSignalCount: Long = 0L

    private var signalCollectorJob: Job? = null
    private var syncJob: Job? = null

    private var cachedProfile: RescuerProfile? = null
    private var profileFetchedAtMillis: Long = 0L

    private val syncMutex = Mutex()
    private val queueFile by lazy(LazyThreadSafetyMode.NONE) {
        AtomicFile(File(applicationContext.filesDir, PENDING_QUEUE_FILE_NAME))
    }
    private val firstSeenFile by lazy(LazyThreadSafetyMode.NONE) {
        AtomicFile(File(applicationContext.filesDir, FIRST_SEEN_FILE_NAME))
    }
    private val persistedFirstSeenMillis = mutableMapOf<String, Long>()
    private var firstSeenDirty = false
    private var lastFirstSeenWriteAtMillis = 0L
    private var lastTrackPointLocation: LocationSnapshot? = null
    private var lastTrackPointWrittenAtMillis = 0L
    private val queueCodec by lazy(LazyThreadSafetyMode.NONE) {
        CrisisLinkQueueCodec(keyResolver = ::resolveQueueEncryptionKey)
    }
    private val syncDecisionEngine = CrisisLinkSyncDecisionEngine()
    private val queueLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastSuccessfulSyncState: LastSuccessfulSyncState? = null
    private var lastImmediateSyncAtMillis: Long = 0L
    private var consecutiveAuthorizationFailures: Int = 0
    private var lastObservedActiveSignalIds: Set<String> = emptySet()
    private var lastNotifiedActiveSignalCount: Int = 0
    private var lastNotificationUpdateAtMillis: Long = System.currentTimeMillis()
    private var currentSyncProfile: SyncProfile = SyncProfile.BALANCED
    private var lastLoggedSyncProfile: SyncProfile? = null
    private var cachedBatteryState: BatteryState? = null
    private var batteryStateFetchedAtMillis: Long = 0L
    private var lastNoNetworkBackoffLogAtMillis: Long = 0L
    private var lastLiveLocationUnavailableLogAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(activeSignalCount = 0))
        lastNotifiedActiveSignalCount = 0
        lastNotificationUpdateAtMillis = System.currentTimeMillis()
        serviceScope.launch {
            if (!canStartSuspend(applicationContext)) {
                Log.w(TAG, "Crisis Link service start rejected: missing rescue authorization")
                stopSelf()
                return@launch
            }
            loadPersistedFirstSeen()
            registerNetworkCallback()
            observeBleSignals()
            startSyncLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RescueFeatureManager.ACTION_SIGNAL_LOCATION_UPDATED -> {
                val reason = intent.getStringExtra(RescueFeatureManager.EXTRA_IMMEDIATE_SYNC_REASON)
                    ?: IMMEDIATE_REASON_SIGNAL_LOCATION_CHANGED
                requestImmediateSync(reason)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        signalCollectorJob?.cancel()
        syncJob?.cancel()
        markResponderInactiveBestEffort()
        persistFirstSeenIfNeeded(System.currentTimeMillis(), force = true)
        unregisterNetworkCallback()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    private fun observeBleSignals() {
        signalCollectorJob?.cancel()
        signalCollectorJob = serviceScope.launch {
            BleBroadcastDirectory.entries.collectLatest { entries ->
                val activeSignalIds = mutableSetOf<String>()
                entries.values.forEach { entry ->
                    val signalId = normalizeSignalId(entry.broadcastId) ?: return@forEach
                    activeSignalIds += signalId
                    val previous = observedSignals[signalId]
                    if (previous == null) {
                        observedSignals[signalId] = SignalObservation(
                            signalId = signalId,
                            address = normalizeAddress(entry.address),
                            firstSeenMillis = resolveFirstSeenMillis(signalId, entry.lastSeen),
                            lastSeenMillis = entry.lastSeen
                        )
                        totalScanEventCount += 1L
                        totalUniqueSignalCount += 1L
                    } else {
                        val normalizedAddress = normalizeAddress(entry.address)
                        val hasUpdate = entry.lastSeen > previous.lastSeenMillis ||
                            !normalizedAddress.equals(previous.address, ignoreCase = true)
                        if (hasUpdate) {
                            totalScanEventCount += 1L
                            observedSignals[signalId] = previous.copy(
                                address = normalizedAddress,
                                lastSeenMillis = maxOf(previous.lastSeenMillis, entry.lastSeen)
                            )
                        }
                    }
                }

                val now = System.currentTimeMillis()
                observedSignals.entries.removeIf { (signalId, sample) ->
                    signalId !in activeSignalIds &&
                        now - sample.lastSeenMillis > STALE_SIGNAL_RETENTION_MS
                }
                updateNotificationIfNeeded(activeSignalCount = activeSignalIds.size, now = now)

                val immutableActiveSignalIds = activeSignalIds.toSet()
                if (immutableActiveSignalIds != lastObservedActiveSignalIds) {
                    lastObservedActiveSignalIds = immutableActiveSignalIds
                    requestImmediateSync(IMMEDIATE_REASON_ACTIVE_SIGNALS_CHANGED)
                }
            }
        }
    }

    /**
     * First-contact time for a signal, surviving service restarts. `observedSignals` is memory-only,
     * so without the persisted map a restart reset firstSeenMillis to "now" and the merge write
     * regressed the earliest-contact time on the dashboard.
     */
    private fun resolveFirstSeenMillis(signalId: String, lastSeenMillis: Long): Long {
        val persisted = persistedFirstSeenMillis[signalId]
        val firstSeen = persisted?.coerceAtMost(lastSeenMillis) ?: lastSeenMillis
        if (persisted != firstSeen) {
            persistedFirstSeenMillis[signalId] = firstSeen
            firstSeenDirty = true
        }
        persistFirstSeenIfNeeded(System.currentTimeMillis())
        return firstSeen
    }

    private fun loadPersistedFirstSeen() {
        val rawBytes = runCatching { firstSeenFile.readFully() }
            .getOrElse { throwable ->
                if (throwable !is FileNotFoundException) {
                    Log.w(TAG, "Failed to read Crisis Link first-seen map", throwable)
                }
                return
            }
        val json = runCatching { JSONObject(rawBytes.toString(Charsets.UTF_8)) }
            .getOrElse { throwable ->
                Log.w(TAG, "Crisis Link first-seen map is corrupted. Ignoring it.", throwable)
                return
            }
        val cutoff = System.currentTimeMillis() - FIRST_SEEN_RETENTION_MS
        json.keys().forEach { key ->
            val signalId = normalizeSignalId(key) ?: return@forEach
            val firstSeenMillis = json.optLong(key, 0L)
            if (firstSeenMillis > cutoff) {
                persistedFirstSeenMillis[signalId] = firstSeenMillis
            }
        }
    }

    private fun persistFirstSeenIfNeeded(now: Long, force: Boolean = false) {
        if (!firstSeenDirty) {
            return
        }
        if (!force && now - lastFirstSeenWriteAtMillis < FIRST_SEEN_WRITE_THROTTLE_MS) {
            return
        }
        val cutoff = now - FIRST_SEEN_RETENTION_MS
        persistedFirstSeenMillis.entries.removeIf { it.value <= cutoff }
        val json = JSONObject()
        persistedFirstSeenMillis.forEach { (signalId, firstSeenMillis) ->
            json.put(signalId, firstSeenMillis)
        }
        val stream = runCatching { firstSeenFile.startWrite() }
            .getOrElse { throwable ->
                Log.w(TAG, "Unable to open Crisis Link first-seen map for write", throwable)
                return
            }
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            firstSeenFile.finishWrite(stream)
            firstSeenDirty = false
            lastFirstSeenWriteAtMillis = now
        } catch (throwable: Throwable) {
            runCatching { firstSeenFile.failWrite(stream) }
            Log.w(TAG, "Failed to persist Crisis Link first-seen map", throwable)
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                val nextDelay = runCatching {
                    syncSnapshot()
                }.onFailure { throwable ->
                    Log.w(TAG, "Crisis Link sync failed", throwable)
                }.getOrDefault(ERROR_RETRY_INTERVAL_MS)

                delay(nextDelay.coerceIn(MIN_SYNC_INTERVAL_MS, MAX_SYNC_INTERVAL_MS))
            }
        }
    }

    private suspend fun syncSnapshot(): Long {
        return syncMutex.withLock {
            if (!canStartSuspend(applicationContext)) {
                stopSelf()
                return@withLock IDLE_SYNC_INTERVAL_MS
            }
            val authUser = auth.currentUser
            val uid = when {
                authUser?.isAnonymous == false -> authUser.uid?.trim().orEmpty()
                authUser != null -> ""
                else -> LocalKeyStorage.getSavedUid(applicationContext)?.trim().orEmpty()
            }
            if (uid.isBlank()) {
                stopSelf()
                return@withLock IDLE_SYNC_INTERVAL_MS
            }

            val profile = resolveRescuerProfile(uid) ?: return@withLock IDLE_SYNC_INTERVAL_MS
            if (!isRescueRole(profile.role)) {
                return@withLock IDLE_SYNC_INTERVAL_MS
            }

            val now = System.currentTimeMillis()
            val liveLocationSyncSettings = readLiveLocationSyncSettings()
            val activeSignalsSnapshot = snapshotActiveSignals(now).take(MAX_ACTIVE_SIGNALS_PER_SYNC)
            val locationSnapshot = resolveLocationSnapshot(
                now = now,
                liveLocationSyncSettings = liveLocationSyncSettings,
                hasActiveSignals = activeSignalsSnapshot.isNotEmpty()
            )
            val payload = applyLiveLocationPolicy(
                payload = buildSyncPayload(
                    uid = uid,
                    profile = profile,
                    now = now,
                    location = locationSnapshot,
                    activeSignals = activeSignalsSnapshot
                ),
                liveLocationSyncSettings = liveLocationSyncSettings,
                now = now
            )
            normalizeQueuedPayloadsForProfile(profile, payload.rescuerDeviceId)
            val hasPendingBefore = pendingQueueSize() > 0
            val hasActiveSignals = payload.activeSignals.isNotEmpty()
            val hasValidatedInternet = hasValidatedInternetConnection()
            val syncProfile = determineSyncProfile(
                hasPendingQueue = hasPendingBefore,
                hasActiveSignals = hasActiveSignals,
                hasValidatedInternet = hasValidatedInternet
            )
            currentSyncProfile = syncProfile
            val shouldDeliverNow = hasPendingBefore || shouldDeliverPayload(
                payload = payload,
                now = now,
                syncProfile = syncProfile,
                liveLocationSyncSettings = liveLocationSyncSettings
            )

            if (!shouldDeliverNow) {
                return@withLock nextSyncDelayMillis(
                    hasActiveSignals = hasActiveSignals,
                    hasPendingQueue = hasPendingBefore,
                    authorizationBackoff = false,
                    hasValidatedInternet = hasValidatedInternet,
                    liveLocationSyncSettings = liveLocationSyncSettings
                )
            }

            if (hasValidatedInternet) {
                flushQueuedPayloads()
                when (deliverPayload(payload)) {
                    DeliveryResult.Success -> {
                        consecutiveAuthorizationFailures = 0
                        recordSuccessfulSync(payload, now)
                        writeTrackPointBestEffort(payload, now, liveLocationSyncSettings)
                    }
                    DeliveryResult.RecoverableFailure -> {
                        consecutiveAuthorizationFailures = 0
                        val pendingCount = enqueuePayload(payload)
                        Log.i(TAG, "Current payload queued after recoverable failure. pending=$pendingCount")
                    }
                    DeliveryResult.AuthorizationFailure -> {
                        var payloadForQueue = payload
                        val recoveredPayload = attemptPayloadRecoveryAfterAuthorizationFailure(uid, payload)
                        if (recoveredPayload != null) {
                            payloadForQueue = recoveredPayload
                            when (deliverPayload(recoveredPayload)) {
                                DeliveryResult.Success -> {
                                    consecutiveAuthorizationFailures = 0
                                    recordSuccessfulSync(recoveredPayload, now)
                                    writeTrackPointBestEffort(recoveredPayload, now, liveLocationSyncSettings)
                                    return@withLock nextSyncDelayMillis(
                                        hasActiveSignals = recoveredPayload.activeSignals.isNotEmpty(),
                                        hasPendingQueue = pendingQueueSize() > 0,
                                        authorizationBackoff = false,
                                        hasValidatedInternet = true,
                                        liveLocationSyncSettings = liveLocationSyncSettings
                                    )
                                }
                                DeliveryResult.RecoverableFailure -> {
                                    consecutiveAuthorizationFailures = 0
                                    val pendingCount = enqueuePayload(recoveredPayload)
                                    Log.i(
                                        TAG,
                                        "Recovered payload queued after retryable failure. pending=$pendingCount agencySlug=${recoveredPayload.agencySlug}"
                                    )
                                    return@withLock nextSyncDelayMillis(
                                        hasActiveSignals = recoveredPayload.activeSignals.isNotEmpty(),
                                        hasPendingQueue = true,
                                        authorizationBackoff = false,
                                        hasValidatedInternet = true,
                                        liveLocationSyncSettings = liveLocationSyncSettings
                                    )
                                }
                                DeliveryResult.PermanentFailure -> {
                                    consecutiveAuthorizationFailures = 0
                                    Log.w(
                                        TAG,
                                        "Recovered payload dropped due to permanent Firestore failure. agencySlug=${recoveredPayload.agencySlug}"
                                    )
                                    return@withLock nextSyncDelayMillis(
                                        hasActiveSignals = recoveredPayload.activeSignals.isNotEmpty(),
                                        hasPendingQueue = pendingQueueSize() > 0,
                                        authorizationBackoff = false,
                                        hasValidatedInternet = true,
                                        liveLocationSyncSettings = liveLocationSyncSettings
                                    )
                                }
                                DeliveryResult.AuthorizationFailure -> {
                                    // Keep handling as authorization failure below.
                                }
                            }
                        }

                        val pendingCount = enqueuePayload(payloadForQueue)
                        consecutiveAuthorizationFailures += 1
                        Log.w(
                            TAG,
                            "Crisis Link delivery authorization failure. queued=$pendingCount streak=$consecutiveAuthorizationFailures agencySlug=${payloadForQueue.agencySlug}"
                        )
                        runCatching {
                            auth.currentUser
                                ?.takeUnless { it.isAnonymous }
                                ?.getIdToken(true)
                                ?.awaitResult()
                        }.onFailure { throwable ->
                            Log.w(TAG, "Token refresh after authorization failure failed", throwable)
                        }

                        if (consecutiveAuthorizationFailures >= MAX_CONSECUTIVE_AUTH_FAILURES) {
                            Log.w(TAG, "Stopping Crisis Link service due to repeated authorization failures")
                            stopSelf()
                        }
                        return@withLock nextSyncDelayMillis(
                            hasActiveSignals = payload.activeSignals.isNotEmpty(),
                            hasPendingQueue = true,
                            authorizationBackoff = true,
                            hasValidatedInternet = true,
                            liveLocationSyncSettings = liveLocationSyncSettings
                        )
                    }
                    DeliveryResult.PermanentFailure -> {
                        consecutiveAuthorizationFailures = 0
                        Log.w(TAG, "Current payload dropped due to permanent Firestore failure")
                    }
                }
            } else {
                consecutiveAuthorizationFailures = 0
                val pendingCount = enqueuePayload(payload)
                Log.i(TAG, "No validated internet. Crisis Link payload queued locally. pending=$pendingCount")
            }

            return@withLock nextSyncDelayMillis(
                hasActiveSignals = payload.activeSignals.isNotEmpty(),
                hasPendingQueue = pendingQueueSize() > 0,
                authorizationBackoff = false,
                hasValidatedInternet = hasValidatedInternet,
                liveLocationSyncSettings = liveLocationSyncSettings
            )
        }
    }

    private fun normalizeQueuedPayloadsForProfile(
        profile: RescuerProfile,
        rescuerDeviceId: String
    ) {
        synchronized(queueLock) {
            val queue = readQueuedPayloadsLocked()
            if (queue.isEmpty()) {
                return
            }
            val normalized = queue.map { payload ->
                if (payload.uid != profile.uid) {
                    payload
                } else {
                    payload.copy(
                        role = profile.role,
                        agency = profile.agency,
                        agencySlug = profile.agencySlug,
                        operatorName = profile.operatorName,
                        username = profile.username,
                        country = profile.country,
                        rescuerDeviceId = rescuerDeviceId
                    )
                }
            }
            if (normalized != queue) {
                writeQueuedPayloadsLocked(normalized)
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestImmediateSync(IMMEDIATE_REASON_NETWORK_AVAILABLE)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    requestImmediateSync(IMMEDIATE_REASON_NETWORK_CAPABILITIES_CHANGED)
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to register network callback for Crisis Link queue flush", throwable)
        }
    }

    private fun unregisterNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = networkCallback ?: return
        runCatching {
            manager.unregisterNetworkCallback(callback)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to unregister network callback", throwable)
        }
        networkCallback = null
    }

    private fun requestImmediateSync(reason: String) {
        // Delivering a payload needs internet; RECORDING one does not. syncSnapshot() persists the
        // sighting to the store-and-forward queue when it cannot deliver, so gating the whole call on
        // connectivity meant an offline sighting waited for the regular loop tick — 45 s on BALANCED,
        // minutes on ECO or idle. A snapshot only carries signals seen in the last 60 s, so a rescuer
        // who walks past a beacon and out of range again inside that window loses the victim before
        // anything is written down. Signal-bearing reasons therefore bypass the gate too: offline the
        // call is what commits the sighting to disk.
        val shouldBypassConnectivityGate = reason == IMMEDIATE_REASON_NETWORK_AVAILABLE ||
            reason == IMMEDIATE_REASON_NETWORK_CAPABILITIES_CHANGED ||
            reason == IMMEDIATE_REASON_ACTIVE_SIGNALS_CHANGED ||
            reason == IMMEDIATE_REASON_SIGNAL_LOCATION_CHANGED
        if (!shouldBypassConnectivityGate && !hasValidatedInternetConnection()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastImmediateSyncAtMillis < immediateSyncThrottleMs(currentSyncProfile)) {
            return
        }
        lastImmediateSyncAtMillis = now
        serviceScope.launch {
            runCatching {
                syncSnapshot()
            }.onFailure { throwable ->
                Log.w(TAG, "Immediate Crisis Link sync failed. reason=$reason", throwable)
            }
        }
    }

    private fun markResponderInactiveBestEffort() {
        val authUser = auth.currentUser
        val uid = when {
            authUser?.isAnonymous == false -> authUser.uid?.trim().orEmpty()
            authUser != null -> ""
            else -> LocalKeyStorage.getSavedUid(applicationContext)?.trim().orEmpty()
        }
        if (uid.isBlank()) {
            return
        }

        val profile = cachedProfile
        val agency = profile?.agency ?: run {
            val country = LocalKeyStorage.getSavedCountry(applicationContext).first.orEmpty()
            AgencyRouter.getAgencyForCountry(country)
        }
        val agencySlug = profile?.agencySlug ?: slugify(agency)
        if (agencySlug.isBlank()) {
            return
        }

        val role = profile?.role
            ?: normalizeRole(LocalKeyStorage.getSavedRole(applicationContext))
            ?: "fieldteam"
        val operatorName = profile?.operatorName
            ?.takeIf { it.isNotBlank() }
            ?: auth.currentUser?.takeUnless { it.isAnonymous }?.displayName?.trim().orEmpty()
        val username = profile?.username.orEmpty()
        val country = profile?.country.orEmpty()

        val responderDeviceId = LocalKeyStorage.getOrCreateRescueDeviceId(applicationContext)
        val responderDoc = hashMapOf<String, Any>(
            "uid" to uid,
            "agency" to agency,
            "role" to role,
            "schemaVersion" to RESPONDER_SCHEMA_VERSION,
            "responderDeviceId" to responderDeviceId,
            "crisisLinkEnabled" to false,
            "activeSignalCount" to 0,
            "totalUniqueSignalCount" to totalUniqueSignalCount,
            "totalScanEventCount" to totalScanEventCount,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (operatorName.isNotBlank()) {
            responderDoc["name"] = operatorName
        }
        if (username.isNotBlank()) {
            responderDoc["username"] = username
        }
        if (country.isNotBlank()) {
            responderDoc["country"] = country
        }

        // Snapshot everything the write still needs into locals BEFORE launching: the coroutine
        // runs on the companion detachedWriteScope precisely because onDestroy cancels serviceScope
        // before the first suspension point (device-id registration is a server round-trip) ever
        // resumes — so it may outlive this instance and must not race with its teardown.
        val appContext = applicationContext
        val firestore = this.firestore
        detachedWriteScope.launch {
            runCatching {
                val registeredDeviceId = runCatching {
                    RescueDeviceRegistry.registerLocalDevice(
                        firestore = firestore,
                        context = appContext,
                        uid = uid
                    )
                }.getOrElse { throwable ->
                    Log.w(
                        TAG,
                        "Rescue device ownership registration failed; marking responder inactive with local device id",
                        throwable
                    )
                    responderDeviceId
                }
                val inactiveResponderDoc = if (registeredDeviceId == responderDeviceId) {
                    responderDoc
                } else {
                    HashMap(responderDoc).apply {
                        this["responderDeviceId"] = registeredDeviceId
                    }
                }
                firestore.collection(PANEL_COLLECTION)
                    .document(agencySlug)
                    .collection(RESPONDERS_COLLECTION)
                    .document(registeredDeviceId)
                    .set(inactiveResponderDoc, SetOptions.merge())
                    .awaitResult()
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to mark responder inactive on Crisis Link stop", throwable)
            }
        }
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun buildSyncPayload(
        uid: String,
        profile: RescuerProfile,
        now: Long,
        location: LocationSnapshot?,
        activeSignals: List<SignalObservation>
    ): CrisisLinkSyncPayload {
        return CrisisLinkSyncPayload(
            uid = uid,
            role = profile.role,
            agency = profile.agency,
            agencySlug = profile.agencySlug,
            operatorName = profile.operatorName,
            username = profile.username,
            country = profile.country,
            rescuerDeviceId = LocalKeyStorage.getOrCreateRescueDeviceId(applicationContext),
            totalUniqueSignalCount = totalUniqueSignalCount,
            totalScanEventCount = totalScanEventCount,
            location = location,
            activeSignals = activeSignals,
            capturedAtMillis = now
        )
    }

    private suspend fun flushQueuedPayloads() {
        val queuedPayloads = readQueuedPayloads()
        if (queuedPayloads.isEmpty()) {
            return
        }
        var deliveredCount = 0
        for (payload in queuedPayloads) {
            when (deliverPayload(payload)) {
                DeliveryResult.Success -> {
                    deliveredCount += 1
                }
                DeliveryResult.RecoverableFailure -> {
                    Log.i(
                        TAG,
                        "Queued payload delivery paused due to recoverable failure at index=$deliveredCount"
                    )
                    break
                }
                DeliveryResult.AuthorizationFailure -> {
                    Log.w(
                        TAG,
                        "Queued payload delivery paused due to authorization failure at index=$deliveredCount"
                    )
                    break
                }
                DeliveryResult.PermanentFailure -> {
                    deliveredCount += 1
                    Log.w(TAG, "Dropped permanently invalid queued Crisis Link payload")
                }
            }
        }
        if (deliveredCount > 0) {
            val remaining = dropQueuedPayloads(deliveredCount)
            Log.i(TAG, "Flushed $deliveredCount queued Crisis Link payload(s). remaining=$remaining")
        }
    }

    private suspend fun deliverPayload(payload: CrisisLinkSyncPayload): DeliveryResult {
        val registeredDeviceId = resolveSyncDeviceId(
            uid = payload.uid,
            fallbackDeviceId = payload.rescuerDeviceId
        )
        val syncPayload = if (registeredDeviceId == payload.rescuerDeviceId) {
            payload
        } else {
            payload.copy(rescuerDeviceId = registeredDeviceId)
        }

        val panelRef = firestore.collection(PANEL_COLLECTION).document(syncPayload.agencySlug)
        val batch = firestore.batch()

        val panelData = hashMapOf<String, Any>(
            "agency" to syncPayload.agency,
            "agencySlug" to syncPayload.agencySlug,
            "schemaVersion" to PANEL_SCHEMA_VERSION,
            "source" to PANEL_SOURCE,
            "activeSignals" to syncPayload.activeSignals.size,
            "totalSignalsObserved" to syncPayload.totalUniqueSignalCount,
            "updatedByDeviceId" to syncPayload.rescuerDeviceId,
            "updatedByUid" to syncPayload.uid,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(panelRef, panelData, SetOptions.merge())

        val responderData = hashMapOf<String, Any>(
            "responderDeviceId" to syncPayload.rescuerDeviceId,
            "uid" to syncPayload.uid,
            "agency" to syncPayload.agency,
            "role" to syncPayload.role,
            "schemaVersion" to RESPONDER_SCHEMA_VERSION,
            "crisisLinkEnabled" to true,
            "activeSignalCount" to syncPayload.activeSignals.size,
            "totalUniqueSignalCount" to syncPayload.totalUniqueSignalCount,
            "totalScanEventCount" to syncPayload.totalScanEventCount,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (syncPayload.operatorName.isNotBlank()) {
            responderData["name"] = syncPayload.operatorName
        }
        if (syncPayload.username.isNotBlank()) {
            responderData["username"] = syncPayload.username
        }
        if (syncPayload.country.isNotBlank()) {
            responderData["country"] = syncPayload.country
        }
        syncPayload.location?.let { snapshot ->
            responderData["gps"] = snapshot.toMap()
        }
        batch.set(
            panelRef.collection(RESPONDERS_COLLECTION).document(syncPayload.rescuerDeviceId),
            responderData,
            SetOptions.merge()
        )

        val signalsToUpsert = selectSignalsForUpsert(syncPayload.activeSignals)
        signalsToUpsert.forEach { signal ->
            val signalRef = panelRef.collection(SIGNALS_COLLECTION).document(signal.signalId)
            val signalData = hashMapOf<String, Any>(
                "signalId" to signal.signalId,
                "schemaVersion" to SIGNAL_SCHEMA_VERSION,
                "status" to "active",
                "source" to "ble",
                "firstSeenMillis" to signal.firstSeenMillis,
                "lastSeenMillis" to signal.lastSeenMillis,
                "lastSeenAddress" to signal.address,
                "lastReporterUid" to syncPayload.uid,
                "victimRole" to BlePeerIdentityUtils.ROLE_VICTIM,
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "reporterIds" to FieldValue.arrayUnion(syncPayload.rescuerDeviceId)
            )
            signal.victimLocation?.let { victimLocation ->
                signalData["gps"] = victimLocation.toMap()
                signalData["victimGps"] = victimLocation.toMap()
                signalData["locationSource"] = "victim_gps"
                signalData["estimatedVictimGps"] = FieldValue.delete()
                signalData["relativeEstimate"] = FieldValue.delete()
            } ?: run {
                signal.estimatedVictimLocation?.let { estimate ->
                    signalData["gps"] = estimate.toMap()
                    signalData["estimatedVictimGps"] = estimate.toMap()
                    signalData["locationSource"] = "rescuer_relative_estimate"
                }
                signal.relativeEstimate?.let { estimate ->
                    signalData["relativeEstimate"] = estimate.toMap()
                }
            }
            syncPayload.location?.let { reporterLocation ->
                signalData["lastReporterLocation"] = reporterLocation.toMap()
            } ?: signal.relativeEstimate?.let { estimate ->
                signalData["lastReporterLocation"] = estimate.originToMap()
            } ?: run {
                signalData["lastReporterLocation"] = FieldValue.delete()
            }
            signal.locationMetadataUpdatedAtMillis.takeIf { it > 0L }?.let { updatedAtMillis ->
                signalData["locationUpdatedAtMillis"] = updatedAtMillis
            }
            signal.victimDisplayName?.takeIf { it.isNotBlank() }?.let { displayName ->
                signalData["victimName"] = displayName
                signalData["victimDisplayName"] = displayName
            }
            signal.victimBatteryPercent?.let { batteryPercent ->
                signalData["victimBatteryPercent"] = batteryPercent
            }
            batch.set(signalRef, signalData, SetOptions.merge())

            val reporterData = hashMapOf<String, Any>(
                "responderDeviceId" to syncPayload.rescuerDeviceId,
                "uid" to syncPayload.uid,
                "agency" to syncPayload.agency,
                "lastSeenMillis" to signal.lastSeenMillis,
                "lastSeenAt" to FieldValue.serverTimestamp()
            )
            if (syncPayload.operatorName.isNotBlank()) {
                reporterData["name"] = syncPayload.operatorName
            }
            if (syncPayload.username.isNotBlank()) {
                reporterData["username"] = syncPayload.username
            }
            syncPayload.location?.let { snapshot ->
                reporterData["gps"] = snapshot.toMap()
            } ?: signal.relativeEstimate?.let { estimate ->
                reporterData["gps"] = estimate.originToMap()
            } ?: run {
                reporterData["gps"] = FieldValue.delete()
            }
            signal.victimLocation?.let { victimLocation ->
                reporterData["signalGps"] = victimLocation.toMap()
                reporterData["signalLocationSource"] = "victim_gps"
            } ?: signal.estimatedVictimLocation?.let { estimate ->
                reporterData["signalGps"] = estimate.toMap()
                reporterData["signalLocationSource"] = "rescuer_relative_estimate"
            } ?: run {
                reporterData["signalGps"] = FieldValue.delete()
                reporterData["signalLocationSource"] = FieldValue.delete()
            }
            signal.relativeEstimate?.let { estimate ->
                reporterData["relativeEstimate"] = estimate.toMap()
            } ?: run {
                reporterData["relativeEstimate"] = FieldValue.delete()
            }
            signal.victimDisplayName?.takeIf { it.isNotBlank() }?.let { displayName ->
                reporterData["signalVictimName"] = displayName
                reporterData["signalVictimDisplayName"] = displayName
            }
            signal.victimBatteryPercent?.let { batteryPercent ->
                reporterData["signalVictimBatteryPercent"] = batteryPercent
            }
            batch.set(
                signalRef.collection(REPORTERS_COLLECTION).document(syncPayload.rescuerDeviceId),
                reporterData,
                SetOptions.merge()
            )
        }

        return try {
            batch.commit().awaitResult()
            syncPayloadToSelfHostedDashboard(syncPayload, signalsToUpsert)
            DeliveryResult.Success
        } catch (throwable: Throwable) {
            classifyDeliveryFailure(throwable)
        }
    }

    private suspend fun syncPayloadToSelfHostedDashboard(
        syncPayload: CrisisLinkSyncPayload,
        signalsToUpsert: List<SignalObservation>
    ) {
        val clientEventId = "android-crisis-link-${syncPayload.rescuerDeviceId}-${syncPayload.capturedAtMillis}"
        val events = JSONArray()
        syncPayload.location?.let { location ->
            // The fix rides NESTED under "gps": /api/mobile/sync filters location payloads with
            // RESPONDER_PAYLOAD_ALLOWLIST, which passes `gps` but not top-level lat/lon — the old
            // flattened shape was silently stripped and the responder never moved on a self-hosted
            // map. (The route also lifts the flattened shape for builds already in the field.)
            val payload = JSONObject()
                .put("gps", JSONObject(location.toMap()))
                .put("uid", syncPayload.uid)
                .put("responderDeviceId", syncPayload.rescuerDeviceId)
                .put("agency", syncPayload.agency)
                .put("agencySlug", syncPayload.agencySlug)
                .put("role", syncPayload.role)
                .put("username", syncPayload.username)
                .put("operatorName", syncPayload.operatorName)
                .put("activeSignalCount", syncPayload.activeSignals.size)
                .put("totalUniqueSignalCount", syncPayload.totalUniqueSignalCount)
                .put("totalScanEventCount", syncPayload.totalScanEventCount)
            events.put(
                JSONObject()
                    .put("id", "location-${syncPayload.rescuerDeviceId}-${location.capturedAtMillis}")
                    .put("type", "location")
                    .put("occurredAt", location.capturedAtMillis)
                    .put("payload", payload)
            )
        }

        signalsToUpsert.forEach { signal ->
            val reporter = JSONObject()
                .put("responderDeviceId", syncPayload.rescuerDeviceId)
                .put("uid", syncPayload.uid)
                .put("agency", syncPayload.agency)
                .put("name", syncPayload.operatorName)
                .put("username", syncPayload.username)
                .put("lastSeenMillis", signal.lastSeenMillis)
            val signalPayload = JSONObject()
                .put("signalId", signal.signalId)
                .put("schemaVersion", SIGNAL_SCHEMA_VERSION)
                .put("status", "active")
                .put("source", "ble")
                .put("firstSeenMillis", signal.firstSeenMillis)
                .put("lastSeenMillis", signal.lastSeenMillis)
                .put("lastSeenAddress", signal.address)
                .put("lastReporterUid", syncPayload.uid)
                .put("lastReporterDeviceId", syncPayload.rescuerDeviceId)
                .put("victimRole", BlePeerIdentityUtils.ROLE_VICTIM)
                .put("reporter", reporter)
            signal.victimLocation?.let { location ->
                signalPayload
                    .put("gps", JSONObject(location.toMap()))
                    .put("victimGps", JSONObject(location.toMap()))
                    .put("locationSource", "victim_gps")
            }
            signal.estimatedVictimLocation?.let { estimate ->
                signalPayload
                    .put("gps", JSONObject(estimate.toMap()))
                    .put("estimatedVictimGps", JSONObject(estimate.toMap()))
                    .put("locationSource", "rescuer_relative_estimate")
            }
            signal.relativeEstimate?.let { estimate ->
                signalPayload.put("relativeEstimate", JSONObject(estimate.toMap()))
            }
            syncPayload.location?.let { reporterLocation ->
                signalPayload.put("lastReporterLocation", JSONObject(reporterLocation.toMap()))
            }
            signal.victimDisplayName?.takeIf { it.isNotBlank() }?.let { displayName ->
                signalPayload
                    .put("victimName", displayName)
                    .put("victimDisplayName", displayName)
            }
            signal.victimBatteryPercent?.let { batteryPercent ->
                signalPayload.put("victimBatteryPercent", batteryPercent)
            }
            events.put(
                JSONObject()
                    .put("id", "signal-${signal.signalId}-${signal.lastSeenMillis}")
                    .put("type", "signal")
                    .put("occurredAt", signal.lastSeenMillis)
                    .put("payload", signalPayload)
            )
        }

        MobileSyncClient.syncEvents(
            context = applicationContext,
            panelId = syncPayload.agencySlug,
            deviceId = syncPayload.rescuerDeviceId,
            clientEventId = clientEventId,
            events = events,
        )
    }

    private suspend fun resolveSyncDeviceId(
        uid: String,
        fallbackDeviceId: String
    ): String {
        return runCatching {
            RescueDeviceRegistry.registerLocalDevice(
                firestore = firestore,
                context = applicationContext,
                uid = uid
            )
        }.getOrElse { throwable ->
            Log.w(
                TAG,
                "Rescue device ownership registration failed; continuing telemetry sync with local device id",
                throwable
            )
            LocalKeyStorage.getOrCreateRescueDeviceId(applicationContext)
                .takeIf { it.isNotBlank() }
                ?: fallbackDeviceId
        }
    }

    private fun selectSignalsForUpsert(signals: List<SignalObservation>): List<SignalObservation> {
        val previousSignals = lastSuccessfulSyncState?.signalFingerprints ?: return signals
        if (previousSignals.isEmpty()) {
            return signals
        }
        return signals.filter { current ->
            val previous = previousSignals[current.signalId]
            previous == null ||
                current.lastSeenMillis > previous.lastSeenMillis ||
                current.firstSeenMillis != previous.firstSeenMillis ||
                !current.address.equals(previous.address, ignoreCase = true) ||
                current.victimLocation != previous.victimLocation ||
                current.estimatedVictimLocation != previous.estimatedVictimLocation ||
                current.relativeEstimate != previous.relativeEstimate ||
                current.victimDisplayName != previous.victimDisplayName ||
                current.victimBatteryPercent != previous.victimBatteryPercent ||
                current.locationMetadataUpdatedAtMillis != previous.locationMetadataUpdatedAtMillis
        }
    }

    private fun classifyDeliveryFailure(throwable: Throwable): DeliveryResult {
        val firestoreError = throwable as? FirebaseFirestoreException
        return when (firestoreError?.code) {
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> DeliveryResult.AuthorizationFailure
            FirebaseFirestoreException.Code.CANCELLED,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.INTERNAL,
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> DeliveryResult.RecoverableFailure
            else -> if (throwable is IOException) {
                DeliveryResult.RecoverableFailure
            } else {
                Log.w(TAG, "Permanent Crisis Link delivery failure", throwable)
                DeliveryResult.PermanentFailure
            }
        }
    }

    /**
     * Union of two sighting lists, keyed by signalId. The newer observation wins for position and
     * identity, but `firstSeenMillis` keeps the EARLIEST contact and any metadata the newer sighting
     * lacks is inherited from the older one — a scan-only re-sighting must never erase the victim
     * name or GPS an earlier authenticated read established.
     */
    private fun mergeObservations(
        existing: List<SignalObservation>,
        incoming: List<SignalObservation>
    ): List<SignalObservation> {
        val merged = LinkedHashMap<String, SignalObservation>(existing.size + incoming.size)
        for (observation in existing) {
            merged[observation.signalId] = observation
        }
        for (candidate in incoming) {
            val previous = merged[candidate.signalId]
            if (previous == null) {
                merged[candidate.signalId] = candidate
                continue
            }
            val fresh: SignalObservation
            val stale: SignalObservation
            if (candidate.lastSeenMillis >= previous.lastSeenMillis) {
                fresh = candidate
                stale = previous
            } else {
                fresh = previous
                stale = candidate
            }
            merged[candidate.signalId] = fresh.copy(
                firstSeenMillis = listOf(previous.firstSeenMillis, candidate.firstSeenMillis)
                    .filter { it > 0L }
                    .minOrNull() ?: fresh.firstSeenMillis,
                victimLocation = fresh.victimLocation ?: stale.victimLocation,
                estimatedVictimLocation = fresh.estimatedVictimLocation ?: stale.estimatedVictimLocation,
                relativeEstimate = fresh.relativeEstimate ?: stale.relativeEstimate,
                victimDisplayName = fresh.victimDisplayName?.takeIf { it.isNotBlank() }
                    ?: stale.victimDisplayName,
                victimBatteryPercent = fresh.victimBatteryPercent ?: stale.victimBatteryPercent,
                locationMetadataUpdatedAtMillis = maxOf(
                    previous.locationMetadataUpdatedAtMillis,
                    candidate.locationMetadataUpdatedAtMillis
                )
            )
        }
        return merged.values
            .sortedByDescending { it.lastSeenMillis }
            .take(MAX_ACTIVE_SIGNALS_PER_SYNC)
    }

    private fun enqueuePayload(payload: CrisisLinkSyncPayload): Int {
        synchronized(queueLock) {
            val queue = readQueuedPayloadsLocked().toMutableList()
            val existingIndex = queue.indexOfLast {
                it.uid == payload.uid &&
                    it.rescuerDeviceId == payload.rescuerDeviceId &&
                    it.agencySlug == payload.agencySlug
            }
            if (existingIndex >= 0) {
                // The three key fields are invariant for one signed-in rescuer on one device (and
                // normalizeQueuedPayloadsForProfile rewrites the other two to the current profile
                // before every tick), so this branch is taken on EVERY offline enqueue and the queue
                // can only ever hold one entry — MAX_PENDING_PAYLOADS is unreachable. The payload
                // carries only snapshotActiveSignals(), a 60-second window, while the offline retry
                // cadence is 8 minutes: a straight replace therefore erased every victim heard more
                // than a minute before the tick, which is the normal case for a rescuer sweeping a
                // no-coverage area. Merge the sightings instead — delivery is idempotent (doc id is
                // the signalId, written with merge), so re-sending an older sighting is safe while
                // dropping one is not.
                val existing = queue[existingIndex]
                val combined = payload.copy(
                    activeSignals = mergeObservations(existing.activeSignals, payload.activeSignals)
                )
                if (existing == combined) {
                    return queue.size
                }
                queue[existingIndex] = combined
            } else {
                queue += payload
            }
            if (queue.size > MAX_PENDING_PAYLOADS) {
                val overflow = queue.size - MAX_PENDING_PAYLOADS
                queue.subList(0, overflow).clear()
            }
            writeQueuedPayloadsLocked(queue)
            return queue.size
        }
    }

    private fun readQueuedPayloads(): List<CrisisLinkSyncPayload> {
        synchronized(queueLock) {
            return readQueuedPayloadsLocked()
        }
    }

    private fun dropQueuedPayloads(count: Int): Int {
        synchronized(queueLock) {
            if (count <= 0) {
                return readQueuedPayloadsLocked().size
            }
            val queue = readQueuedPayloadsLocked().toMutableList()
            val removeCount = count.coerceAtMost(queue.size)
            if (removeCount > 0) {
                queue.subList(0, removeCount).clear()
                writeQueuedPayloadsLocked(queue)
            }
            return queue.size
        }
    }

    private fun readQueuedPayloadsLocked(): List<CrisisLinkSyncPayload> {
        val rawBytes = runCatching { queueFile.readFully() }
            .getOrElse { throwable ->
                if (throwable !is FileNotFoundException) {
                    Log.w(TAG, "Failed to read pending Crisis Link queue", throwable)
                }
                return emptyList()
            }
        val decodedQueue = decodeQueuePayload(rawBytes) ?: run {
            Log.w(TAG, "Pending Crisis Link queue could not be decrypted. Resetting queue file.")
            writeQueuedPayloadsLocked(emptyList())
            return emptyList()
        }
        val raw = runCatching { decodedQueue.plaintext.toString(Charsets.UTF_8) }.getOrDefault("")
        if (raw.isBlank()) {
            return emptyList()
        }
        val jsonArray = runCatching { JSONArray(raw) }.getOrElse { throwable ->
            Log.w(TAG, "Pending Crisis Link queue is corrupted. Resetting queue file.", throwable)
            writeQueuedPayloadsLocked(emptyList())
            return emptyList()
        }
        val payloads = mutableListOf<CrisisLinkSyncPayload>()
        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(index) ?: continue
            parseQueuedPayload(item)?.let { payloads += it }
        }
        if (decodedQueue.isLegacyPlaintext || payloads.size < jsonArray.length()) {
            writeQueuedPayloadsLocked(payloads)
        }
        return payloads
    }

    private fun writeQueuedPayloadsLocked(payloads: List<CrisisLinkSyncPayload>) {
        val jsonArray = JSONArray()
        payloads.forEach { payload ->
            jsonArray.put(serializeQueuedPayload(payload))
        }
        val stream = runCatching { queueFile.startWrite() }
            .getOrElse { throwable ->
                Log.w(TAG, "Unable to open pending Crisis Link queue for write", throwable)
                return
            }
        try {
            val plainBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
            val encryptedBytes = encodeQueuePayload(plainBytes) ?: run {
                runCatching { queueFile.failWrite(stream) }
                Log.w(TAG, "Unable to encrypt pending Crisis Link queue for write")
                return
            }
            stream.write(encryptedBytes)
            stream.flush()
            queueFile.finishWrite(stream)
        } catch (throwable: Throwable) {
            runCatching { queueFile.failWrite(stream) }
            Log.w(TAG, "Failed to persist pending Crisis Link queue", throwable)
        }
    }

    private fun decodeQueuePayload(rawBytes: ByteArray): DecodedQueuePayload? {
        val decoded = queueCodec.decode(rawBytes) ?: return null
        return DecodedQueuePayload(
            plaintext = decoded.plaintext,
            isLegacyPlaintext = decoded.isLegacyPlaintext
        )
    }

    private fun encodeQueuePayload(plainBytes: ByteArray): ByteArray? {
        return queueCodec.encode(plainBytes)
    }

    private fun resolveQueueEncryptionKey(): ByteArray? {
        val encodedKey = runCatching {
            LocalKeyStorage.getOrCreateAesKey(applicationContext)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to resolve queue encryption key", throwable)
        }.getOrNull() ?: return null
        return runCatching {
            AesCipherHelper.decodeKeyFromBase64(encodedKey)
        }.onFailure { throwable ->
            Log.w(TAG, "Queue encryption key is invalid", throwable)
        }.getOrNull()
    }

    private fun serializeQueuedPayload(payload: CrisisLinkSyncPayload): JSONObject {
        val json = JSONObject()
            .put("uid", payload.uid)
            .put("role", payload.role)
            .put("agency", payload.agency)
            .put("agencySlug", payload.agencySlug)
            .put("operatorName", payload.operatorName)
            .put("username", payload.username)
            .put("country", payload.country)
            .put("rescuerDeviceId", payload.rescuerDeviceId)
            .put("totalUniqueSignalCount", payload.totalUniqueSignalCount)
            .put("totalScanEventCount", payload.totalScanEventCount)
            .put("capturedAtMillis", payload.capturedAtMillis)

        payload.location?.let { location ->
            json.put(
                "location",
                JSONObject().apply {
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                    put("provider", location.provider)
                    put("capturedAtMillis", location.capturedAtMillis)
                    location.accuracyMeters?.let { accuracy ->
                        put("accuracyMeters", accuracy.toDouble())
                    }
                }
            )
        }

        val signalsJson = JSONArray()
        payload.activeSignals.forEach { signal ->
            val signalJson = JSONObject()
                .put("signalId", signal.signalId)
                .put("address", signal.address)
                .put("firstSeenMillis", signal.firstSeenMillis)
                .put("lastSeenMillis", signal.lastSeenMillis)
            signal.victimLocation?.let { location ->
                signalJson.put("victimGps", location.toJson())
            }
            signal.estimatedVictimLocation?.let { location ->
                signalJson.put("estimatedVictimGps", location.toJson())
            }
            signal.relativeEstimate?.let { estimate ->
                signalJson.put("relativeEstimate", estimate.toJson())
            }
            signal.victimDisplayName?.takeIf { it.isNotBlank() }?.let { displayName ->
                signalJson.put("victimDisplayName", displayName)
            }
            signal.victimBatteryPercent?.let { batteryPercent ->
                signalJson.put("victimBatteryPercent", batteryPercent)
            }
            signal.locationMetadataUpdatedAtMillis.takeIf { it > 0L }?.let { updatedAtMillis ->
                signalJson.put("locationMetadataUpdatedAtMillis", updatedAtMillis)
            }
            signalsJson.put(signalJson)
        }
        json.put("activeSignals", signalsJson)
        return json
    }

    private fun parseQueuedPayload(json: JSONObject): CrisisLinkSyncPayload? {
        val uid = json.optString("uid").trim()
        val role = normalizeRole(json.optString("role")) ?: return null
        val agency = json.optString("agency").trim()
        val agencySlug = json.optString("agencySlug").trim()
        val operatorName = json.optString("operatorName").trim()
        val rescuerDeviceId = json.optString("rescuerDeviceId").trim()
        if (uid.isBlank() || agency.isBlank() || agencySlug.isBlank() || rescuerDeviceId.isBlank()) {
            return null
        }
        val username = json.optString("username").trim()
        val country = json.optString("country").trim()

        val signalsArray = json.optJSONArray("activeSignals") ?: JSONArray()
        val signals = mutableListOf<SignalObservation>()
        for (index in 0 until signalsArray.length()) {
            val signalJson = signalsArray.optJSONObject(index) ?: continue
            val signalId = normalizeSignalId(signalJson.optString("signalId")) ?: continue
            val address = normalizeAddress(signalJson.optString("address"))
            val firstSeenMillis = signalJson.optLong("firstSeenMillis", 0L)
            val lastSeenMillis = signalJson.optLong("lastSeenMillis", 0L)
            if (address.isBlank() || lastSeenMillis <= 0L || firstSeenMillis <= 0L) {
                continue
            }
            signals += SignalObservation(
                signalId = signalId,
                address = address,
                firstSeenMillis = firstSeenMillis,
                lastSeenMillis = lastSeenMillis,
                victimLocation = parsePeerLocationSnapshot(signalJson.optJSONObject("victimGps")),
                estimatedVictimLocation = parsePeerLocationSnapshot(
                    signalJson.optJSONObject("estimatedVictimGps")
                ),
                relativeEstimate = parseRelativeEstimate(signalJson.optJSONObject("relativeEstimate")),
                victimDisplayName = firstNonBlank(
                    signalJson.optString("victimDisplayName"),
                    signalJson.optString("victimName")
                ).trim().take(MAX_SIGNAL_DISPLAY_NAME_LENGTH).takeIf { it.isNotBlank() },
                victimBatteryPercent = signalJson.optInt(
                    "victimBatteryPercent",
                    Int.MIN_VALUE
                ).takeIf { it in 0..100 },
                locationMetadataUpdatedAtMillis = signalJson.optLong(
                    "locationMetadataUpdatedAtMillis",
                    0L
                )
            )
        }

        val locationJson = json.optJSONObject("location")
        val location = locationJson?.let { obj ->
            val latitude = obj.optDouble("lat", Double.NaN)
            val longitude = obj.optDouble("lon", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0
            ) {
                null
            } else {
                LocationSnapshot(
                    latitude = latitude,
                    longitude = longitude,
                    accuracyMeters = obj.optDouble("accuracyMeters", Double.NaN)
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?.toFloat(),
                    provider = obj.optString("provider").ifBlank { "unknown" },
                    capturedAtMillis = obj.optLong("capturedAtMillis", 0L)
                        .takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                )
            }
        }

        return CrisisLinkSyncPayload(
            uid = uid,
            role = role,
            agency = agency,
            agencySlug = agencySlug,
            operatorName = operatorName.ifBlank { "Responder ${uid.takeLast(6).uppercase(Locale.US)}" },
            username = username,
            country = country,
            rescuerDeviceId = rescuerDeviceId,
            totalUniqueSignalCount = json.optLong("totalUniqueSignalCount", 0L).coerceAtLeast(0L),
            totalScanEventCount = json.optLong("totalScanEventCount", 0L).coerceAtLeast(0L),
            location = location,
            activeSignals = signals.take(MAX_ACTIVE_SIGNALS_PER_SYNC),
            capturedAtMillis = json.optLong("capturedAtMillis", 0L)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )
    }

    private suspend fun resolveRescuerProfile(uid: String, forceRefresh: Boolean = false): RescuerProfile? {
        val now = System.currentTimeMillis()
        val cached = cachedProfile
        if (!forceRefresh && cached != null && cached.uid == uid && now - profileFetchedAtMillis < PROFILE_CACHE_TTL_MS) {
            return cached
        }

        val remoteProfile = runCatching {
            firestore.collection("users").document(uid).get().awaitResult()
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to fetch user profile for Crisis Link sync", throwable)
        }.getOrNull()

        if (remoteProfile == null) {
            return cached?.takeIf { it.uid == uid }
        }

        if (remoteProfile.getBoolean("verified") != true) {
            return null
        }

        val role = normalizeRole(
            remoteProfile.getString("role")
        ) ?: return null

        val remoteAgency = firstNonBlank(
            remoteProfile.getString("agency"),
            remoteProfile.getString("agencyName"),
            remoteProfile.getString("authority"),
            remoteProfile.getString("institution"),
            remoteProfile.getString("organization")
        )
        val remoteAgencySlug = firstNonBlank(
            remoteProfile.getString("agencySlug"),
            remoteProfile.getString("panelSlug"),
            remoteProfile.getString("panelId"),
            remoteProfile.getString("agencyPanelId")
        )
        val remoteAgencyKey = firstNonBlank(
            remoteProfile.getString("agencyKey"),
            remoteProfile.getString("panelKey")
        )
        val remoteUsername = firstNonBlank(
            remoteProfile.getString("username"),
            remoteProfile.getString("displayName")
        )
        val remoteEmail = firstNonBlank(
            remoteProfile.getString("email"),
            auth.currentUser?.takeUnless { it.isAnonymous }?.email
        )
        val authDisplayName = auth.currentUser?.takeUnless { it.isAnonymous }?.displayName?.trim().orEmpty()
        val localCountry = LocalKeyStorage.getSavedCountry(applicationContext).first.orEmpty()
        val remoteCountry = remoteProfile.getString("country")?.trim().orEmpty()
        val agency = firstNonBlank(
            remoteAgency,
            AgencyRouter.getAgencyForCountry(localCountry),
            "International"
        )
        val agencySlug = firstNonBlank(
            remoteAgencySlug,
            remoteAgencyKey,
            cached?.agencySlug,
            slugify(agency)
        )
        val operatorName = firstNonBlank(
            remoteUsername,
            authDisplayName,
            remoteEmail.substringBefore("@"),
            cached?.operatorName,
            "Responder ${uid.takeLast(6).uppercase(Locale.US)}"
        )
        val username = firstNonBlank(
            remoteUsername,
            authDisplayName,
            remoteEmail.substringBefore("@"),
            cached?.username
        )
        val country = firstNonBlank(
            remoteCountry,
            localCountry,
            cached?.country
        )

        return RescuerProfile(
            uid = uid,
            role = role,
            agency = agency,
            agencySlug = agencySlug,
            operatorName = operatorName,
            username = username,
            country = country
        ).also {
            cachedProfile = it
            profileFetchedAtMillis = now
        }
    }

    private suspend fun attemptPayloadRecoveryAfterAuthorizationFailure(
        uid: String,
        payload: CrisisLinkSyncPayload
    ): CrisisLinkSyncPayload? {
        val refreshedProfile = resolveRescuerProfile(uid, forceRefresh = true) ?: return null
        if (!isRescueRole(refreshedProfile.role)) {
            return null
        }
        if (refreshedProfile.agency == payload.agency &&
            refreshedProfile.agencySlug == payload.agencySlug &&
            refreshedProfile.role == payload.role &&
            refreshedProfile.operatorName == payload.operatorName &&
            refreshedProfile.username == payload.username &&
            refreshedProfile.country == payload.country
        ) {
            return null
        }
        Log.i(
            TAG,
            "Recovered Crisis Link profile metadata after authorization failure. oldSlug=${payload.agencySlug} newSlug=${refreshedProfile.agencySlug}"
        )
        return payload.copy(
            role = refreshedProfile.role,
            agency = refreshedProfile.agency,
            agencySlug = refreshedProfile.agencySlug,
            operatorName = refreshedProfile.operatorName,
            username = refreshedProfile.username,
            country = refreshedProfile.country
        )
    }

    private fun shouldDeliverPayload(
        payload: CrisisLinkSyncPayload,
        now: Long,
        syncProfile: SyncProfile,
        liveLocationSyncSettings: LiveLocationSyncSettings
    ): Boolean {
        val previousSnapshot = lastSuccessfulSyncState?.let { previous ->
            CrisisLinkSyncDecisionEngine.LastSuccessfulSyncState(
                activeSignalIds = previous.activeSignalIds,
                location = previous.location?.toDecisionLocationSnapshot(),
                totalUniqueSignalCount = previous.totalUniqueSignalCount,
                totalScanEventCount = previous.totalScanEventCount,
                syncedAtMillis = previous.syncedAtMillis
            )
        }
        val payloadSnapshot = CrisisLinkSyncDecisionEngine.PayloadSnapshot(
            activeSignalIds = payload.activeSignals.mapTo(linkedSetOf()) { it.signalId },
            location = payload.location?.toDecisionLocationSnapshot(),
            totalUniqueSignalCount = payload.totalUniqueSignalCount,
            totalScanEventCount = payload.totalScanEventCount
        )
        val liveLocationSettings = CrisisLinkSyncDecisionEngine.LiveLocationSettings(
            enabled = liveLocationSyncSettings.enabled,
            intervalMillis = liveLocationSyncSettings.intervalMillis
        )
        if (hasMeaningfulSignalLocationChange(payload.activeSignals)) {
            return true
        }
        return syncDecisionEngine.shouldDeliverPayload(
            previousState = previousSnapshot,
            payload = payloadSnapshot,
            now = now,
            syncProfile = syncProfile.toDecisionSyncProfile(),
            liveLocationSettings = liveLocationSettings
        )
    }

    private fun hasMeaningfulLocationChange(
        previous: LocationSnapshot?,
        current: LocationSnapshot?
    ): Boolean {
        if (previous == null && current == null) {
            return false
        }
        if (previous == null || current == null) {
            return true
        }

        val distanceMeters = distanceMeters(previous, current)
        if (distanceMeters >= LOCATION_DISTANCE_DELTA_METERS) {
            return true
        }

        val previousAccuracy = previous.accuracyMeters
        val currentAccuracy = current.accuracyMeters
        if (previousAccuracy != null && currentAccuracy != null) {
            val improvement = previousAccuracy - currentAccuracy
            if (improvement >= LOCATION_ACCURACY_IMPROVEMENT_METERS) {
                return true
            }
        }

        return false
    }

    private fun hasMeaningfulSignalLocationChange(signals: List<SignalObservation>): Boolean {
        val previousSignals = lastSuccessfulSyncState?.signalFingerprints ?: return signals.any {
            it.hasVictimMetadata()
        }
        return signals.any { current ->
            val previous = previousSignals[current.signalId]
            previous == null && current.hasVictimMetadata() ||
                previous != null && (
                    current.victimLocation != previous.victimLocation ||
                        current.estimatedVictimLocation != previous.estimatedVictimLocation ||
                        current.relativeEstimate != previous.relativeEstimate ||
                        current.victimDisplayName != previous.victimDisplayName ||
                        current.victimBatteryPercent != previous.victimBatteryPercent ||
                        current.locationMetadataUpdatedAtMillis != previous.locationMetadataUpdatedAtMillis
                    )
        }
    }

    private fun distanceMeters(previous: LocationSnapshot, current: LocationSnapshot): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
            result
        )
        return result[0]
    }

    private fun recordSuccessfulSync(payload: CrisisLinkSyncPayload, now: Long) {
        lastSuccessfulSyncState = LastSuccessfulSyncState(
            activeSignalIds = payload.activeSignals.mapTo(linkedSetOf()) { it.signalId },
            signalFingerprints = payload.activeSignals.associate { signal ->
                signal.signalId to SignalSyncFingerprint(
                    firstSeenMillis = signal.firstSeenMillis,
                    lastSeenMillis = signal.lastSeenMillis,
                    address = signal.address,
                    victimLocation = signal.victimLocation,
                    estimatedVictimLocation = signal.estimatedVictimLocation,
                    relativeEstimate = signal.relativeEstimate,
                    victimDisplayName = signal.victimDisplayName,
                    victimBatteryPercent = signal.victimBatteryPercent,
                    locationMetadataUpdatedAtMillis = signal.locationMetadataUpdatedAtMillis
                )
            },
            location = payload.location,
            totalUniqueSignalCount = payload.totalUniqueSignalCount,
            totalScanEventCount = payload.totalScanEventCount,
            syncedAtMillis = now
        )
    }

    /**
     * One breadcrumb per successful location-bearing delivery, feeding the dashboard's responder
     * timeline (`responders/{deviceId}/track`). Best-effort and create-only: losing a point is
     * acceptable, so a failed write must never fail the sync that produced it.
     */
    private suspend fun writeTrackPointBestEffort(
        payload: CrisisLinkSyncPayload,
        now: Long,
        liveLocationSyncSettings: LiveLocationSyncSettings
    ) {
        if (!liveLocationSyncSettings.enabled) {
            // Live location OFF is a privacy choice (see resolveLocationSnapshot): a fix may still
            // ride along as context for an active victim sighting, but it must never become a
            // stored, replayable movement trail. iOS only writes track points on its live-location
            // path — the toggle has to mean the same thing on both platforms.
            return
        }
        val location = payload.location ?: return
        val lastLocation = lastTrackPointLocation
        if (lastLocation != null &&
            now - lastTrackPointWrittenAtMillis < TRACK_POINT_MIN_INTERVAL_MS &&
            distanceMeters(lastLocation, location) < TRACK_POINT_MIN_DISTANCE_METERS
        ) {
            return
        }
        runCatching {
            // deliverPayload registers the device id on every delivery and may rotate it; after a
            // success LocalKeyStorage holds whichever id the responder doc was just written under.
            val responderDeviceId = LocalKeyStorage.getOrCreateRescueDeviceId(applicationContext)
                .takeIf { it.isNotBlank() }
                ?: payload.rescuerDeviceId
            val trackData = hashMapOf<String, Any>(
                "uid" to payload.uid,
                "responderDeviceId" to responderDeviceId,
                "agency" to payload.agency,
                "gps" to location.toMap(),
                "capturedAtMillis" to location.capturedAtMillis,
                "activeSignalCount" to payload.activeSignals.size,
                "recordedAt" to FieldValue.serverTimestamp(),
                "expiresAt" to Timestamp(Date(now + TRACK_POINT_TTL_MS)),
                "source" to PANEL_SOURCE
            )
            firestore.collection(PANEL_COLLECTION)
                .document(payload.agencySlug)
                .collection(RESPONDERS_COLLECTION)
                .document(responderDeviceId)
                .collection(TRACK_COLLECTION)
                .document(location.capturedAtMillis.toString())
                .set(trackData)
                .awaitResult()
            lastTrackPointLocation = location
            lastTrackPointWrittenAtMillis = now
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to write Crisis Link track point", throwable)
        }
    }

    private fun nextSyncDelayMillis(
        hasActiveSignals: Boolean,
        hasPendingQueue: Boolean,
        authorizationBackoff: Boolean,
        hasValidatedInternet: Boolean,
        liveLocationSyncSettings: LiveLocationSyncSettings
    ): Long {
        val syncProfile = determineSyncProfile(
            hasPendingQueue = hasPendingQueue,
            hasActiveSignals = hasActiveSignals,
            hasValidatedInternet = hasValidatedInternet
        )
        currentSyncProfile = syncProfile
        if (lastLoggedSyncProfile != syncProfile) {
            lastLoggedSyncProfile = syncProfile
            Log.i(
                TAG,
                "Crisis Link sync profile=$syncProfile pending=$hasPendingQueue active=$hasActiveSignals validated=$hasValidatedInternet liveLocation=${liveLocationSyncSettings.enabled} liveInterval=${liveLocationSyncSettings.intervalSeconds}s"
            )
        }
        if (hasPendingQueue && !hasValidatedInternet) {
            val now = System.currentTimeMillis()
            if (now - lastNoNetworkBackoffLogAtMillis >= NO_VALIDATED_NETWORK_PENDING_SYNC_INTERVAL_MS) {
                lastNoNetworkBackoffLogAtMillis = now
                Log.i(
                    TAG,
                    "No validated internet with pending queue. Applying backoff=${NO_VALIDATED_NETWORK_PENDING_SYNC_INTERVAL_MS}ms"
                )
            }
        }
        val decisionLiveLocationSettings = CrisisLinkSyncDecisionEngine.LiveLocationSettings(
            enabled = liveLocationSyncSettings.enabled,
            intervalMillis = liveLocationSyncSettings.intervalMillis
        )
        return syncDecisionEngine.nextSyncDelayMillis(
            hasActiveSignals = hasActiveSignals,
            hasPendingQueue = hasPendingQueue,
            authorizationBackoff = authorizationBackoff,
            hasValidatedInternet = hasValidatedInternet,
            liveLocationSettings = decisionLiveLocationSettings,
            syncProfile = syncProfile.toDecisionSyncProfile()
        )
    }

    private fun determineSyncProfile(
        hasPendingQueue: Boolean,
        hasActiveSignals: Boolean,
        hasValidatedInternet: Boolean
    ): SyncProfile {
        val batteryState = readBatteryState()
        val isMeteredNetwork = isActiveNetworkMetered()
        val decisionBatteryState = CrisisLinkSyncDecisionEngine.BatteryState(
            levelPercent = batteryState.levelPercent,
            isCharging = batteryState.isCharging,
            isPowerSaveMode = batteryState.isPowerSaveMode
        )
        return syncDecisionEngine.determineSyncProfile(
            hasPendingQueue = hasPendingQueue,
            hasActiveSignals = hasActiveSignals,
            hasValidatedInternet = hasValidatedInternet,
            isMeteredNetwork = isMeteredNetwork,
            batteryState = decisionBatteryState
        ).toServiceSyncProfile()
    }

    private fun SyncProfile.toDecisionSyncProfile(): CrisisLinkSyncDecisionEngine.SyncProfile {
        return when (this) {
            SyncProfile.REALTIME -> CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
            SyncProfile.BALANCED -> CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
            SyncProfile.ECO -> CrisisLinkSyncDecisionEngine.SyncProfile.ECO
        }
    }

    private fun CrisisLinkSyncDecisionEngine.SyncProfile.toServiceSyncProfile(): SyncProfile {
        return when (this) {
            CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME -> SyncProfile.REALTIME
            CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED -> SyncProfile.BALANCED
            CrisisLinkSyncDecisionEngine.SyncProfile.ECO -> SyncProfile.ECO
        }
    }

    private fun LocationSnapshot.toDecisionLocationSnapshot(): CrisisLinkSyncDecisionEngine.LocationSnapshot {
        return CrisisLinkSyncDecisionEngine.LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters
        )
    }

    private fun readBatteryState(): BatteryState {
        val now = System.currentTimeMillis()
        cachedBatteryState?.let { cached ->
            if (now - batteryStateFetchedAtMillis < BATTERY_STATE_CACHE_TTL_MS) {
                return cached
            }
        }

        val batteryStatus = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val status = batteryStatus?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val isPowerSaveMode = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true

        return BatteryState(
            levelPercent = levelPercent,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode
        ).also { state ->
            cachedBatteryState = state
            batteryStateFetchedAtMillis = now
        }
    }

    private fun isActiveNetworkMetered(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return runCatching { manager.isActiveNetworkMetered }.getOrDefault(true)
    }

    private fun activeSyncIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> ACTIVE_SYNC_INTERVAL_MS
            SyncProfile.BALANCED -> BALANCED_ACTIVE_SYNC_INTERVAL_MS
            SyncProfile.ECO -> ECO_ACTIVE_SYNC_INTERVAL_MS
        }
    }

    private fun idleSyncIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> IDLE_SYNC_INTERVAL_MS
            SyncProfile.BALANCED -> BALANCED_IDLE_SYNC_INTERVAL_MS
            SyncProfile.ECO -> ECO_IDLE_SYNC_INTERVAL_MS
        }
    }

    private fun activeSignalHeartbeatIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> ACTIVE_SIGNAL_HEARTBEAT_INTERVAL_MS
            SyncProfile.BALANCED -> BALANCED_ACTIVE_SIGNAL_HEARTBEAT_INTERVAL_MS
            SyncProfile.ECO -> ECO_ACTIVE_SIGNAL_HEARTBEAT_INTERVAL_MS
        }
    }

    private fun idleHeartbeatIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> IDLE_HEARTBEAT_INTERVAL_MS
            SyncProfile.BALANCED -> BALANCED_IDLE_HEARTBEAT_INTERVAL_MS
            SyncProfile.ECO -> ECO_IDLE_HEARTBEAT_INTERVAL_MS
        }
    }

    private fun immediateSyncThrottleMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> IMMEDIATE_SYNC_THROTTLE_MS
            SyncProfile.BALANCED -> BALANCED_IMMEDIATE_SYNC_THROTTLE_MS
            SyncProfile.ECO -> ECO_IMMEDIATE_SYNC_THROTTLE_MS
        }
    }

    private fun minimumDeliveryIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> REALTIME_MIN_DELIVERY_INTERVAL_MS
            SyncProfile.BALANCED -> BALANCED_MIN_DELIVERY_INTERVAL_MS
            SyncProfile.ECO -> ECO_MIN_DELIVERY_INTERVAL_MS
        }
    }

    private fun scanEventDeltaSyncThreshold(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> REALTIME_SCAN_EVENT_DELTA_SYNC_THRESHOLD
            SyncProfile.BALANCED -> BALANCED_SCAN_EVENT_DELTA_SYNC_THRESHOLD
            SyncProfile.ECO -> ECO_SCAN_EVENT_DELTA_SYNC_THRESHOLD
        }
    }

    private fun pendingQueueSize(): Int {
        synchronized(queueLock) {
            return readQueuedPayloadsLocked().size
        }
    }

    private suspend fun readLiveLocationSyncSettings(): LiveLocationSyncSettings {
        val preferences = runCatching { applicationContext.settingsDataStore.data.first() }.getOrNull()
        val enabled = preferences?.get(CRISIS_LINK_LIVE_LOCATION_ENABLED_PREF) ?: false
        val intervalSeconds = sanitizeLiveLocationIntervalSeconds(
            preferences?.get(CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS_PREF)
                ?: DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS
        )
        return LiveLocationSyncSettings(
            enabled = enabled,
            intervalSeconds = intervalSeconds
        )
    }

    private fun sanitizeLiveLocationIntervalSeconds(value: Int): Int {
        return LIVE_LOCATION_INTERVAL_OPTIONS_SECONDS.firstOrNull { it == value }
            ?: DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS
    }

    private fun applyLiveLocationPolicy(
        payload: CrisisLinkSyncPayload,
        liveLocationSyncSettings: LiveLocationSyncSettings,
        now: Long
    ): CrisisLinkSyncPayload {
        if (!liveLocationSyncSettings.enabled) {
            return payload
        }
        if (payload.location != null) {
            return payload
        }

        val fallbackLocation = lastSuccessfulSyncState?.location
        if (now - lastLiveLocationUnavailableLogAtMillis >= LIVE_LOCATION_UNAVAILABLE_LOG_INTERVAL_MS) {
            lastLiveLocationUnavailableLogAtMillis = now
            val fineGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val gpsProviderEnabled = runCatching {
                locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            val networkProviderEnabled = runCatching {
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)

            Log.w(
                TAG,
                "Live location enabled but no location snapshot. fallback=${fallbackLocation != null} fine=$fineGranted coarse=$coarseGranted background=$backgroundGranted gpsProvider=$gpsProviderEnabled networkProvider=$networkProviderEnabled"
            )
        }

        return fallbackLocation?.let { payload.copy(location = it) } ?: payload
    }

    private fun snapshotActiveSignals(now: Long): List<SignalObservation> {
        val cutoff = now - ACTIVE_SIGNAL_WINDOW_MS
        return observedSignals.values
            .filter { it.lastSeenMillis >= cutoff }
            .map(::mergeSignalLocationMetadata)
            .sortedByDescending { it.lastSeenMillis }
    }

    private fun mergeSignalLocationMetadata(signal: SignalObservation): SignalObservation {
        val metadata = BlePeerIdentityUtils.SignalLocationRegistry.snapshotForSignalId(signal.signalId)
        return signal.copy(
            victimLocation = metadata?.victimLocation,
            estimatedVictimLocation = metadata?.estimatedVictimLocation,
            relativeEstimate = metadata?.relativeEstimate,
            victimDisplayName = metadata?.victimDisplayName,
            victimBatteryPercent = metadata?.victimBatteryPercent,
            locationMetadataUpdatedAtMillis = metadata?.updatedAtMillis ?: 0L
        )
    }

    private suspend fun resolveLocationSnapshot(
        now: Long,
        liveLocationSyncSettings: LiveLocationSyncSettings,
        hasActiveSignals: Boolean
    ): LocationSnapshot? {
        if (!liveLocationSyncSettings.enabled) {
            // Live location OFF is a privacy choice: the rescuer's position rides along only as
            // context for an active victim sighting; idle heartbeats carry no location at all.
            return if (hasActiveSignals) readBestLocationSnapshot(now) else null
        }
        val bestLastKnown = readBestLocationSnapshot(now)
        val maxLastKnownAgeMillis = liveLocationSyncSettings.intervalMillis
            .coerceAtLeast(LIVE_LOCATION_MIN_LAST_KNOWN_MAX_AGE_MS)
        val shouldRequestFreshFix = bestLastKnown == null ||
            (now - bestLastKnown.capturedAtMillis) > maxLastKnownAgeMillis
        if (!shouldRequestFreshFix) {
            return bestLastKnown
        }

        val freshLocation = requestFreshLocationSnapshot(now)
        if (freshLocation != null) {
            val ageMillis = (now - freshLocation.capturedAtMillis).coerceAtLeast(0L)
            Log.i(
                TAG,
                "Live location fresh fix provider=${freshLocation.provider} ageMs=$ageMillis accuracy=${freshLocation.accuracyMeters ?: -1f}"
            )
            return freshLocation
        }

        bestLastKnown?.let { fallback ->
            val ageMillis = (now - fallback.capturedAtMillis).coerceAtLeast(0L)
            Log.i(
                TAG,
                "Live location fresh fix unavailable; using lastKnown provider=${fallback.provider} ageMs=$ageMillis"
            )
        }
        return bestLastKnown
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocationSnapshot(now: Long): LocationSnapshot? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = buildList {
            val gpsEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            val networkEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
            if (gpsEnabled) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (networkEnabled) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            return null
        }

        var bestFix: Location? = null
        for (provider in providers) {
            val timeoutMs = if (provider == LocationManager.GPS_PROVIDER) {
                LIVE_LOCATION_GPS_FIX_TIMEOUT_MS
            } else {
                LIVE_LOCATION_NETWORK_FIX_TIMEOUT_MS
            }
            val fixedLocation = requestSingleLocationFix(
                locationManager = locationManager,
                provider = provider,
                timeoutMs = timeoutMs
            )
            if (fixedLocation == null || !isPlausibleLocation(fixedLocation)) {
                continue
            }

            val isBetterThanCurrent = bestFix == null ||
                locationScore(now, fixedLocation) < locationScore(now, bestFix)
            if (isBetterThanCurrent) {
                bestFix = fixedLocation
            }
            val fixTime = fixedLocation.time.takeIf { it > 0L } ?: now
            if ((now - fixTime).coerceAtLeast(0L) <= LIVE_LOCATION_FRESH_FIX_MAX_AGE_MS) {
                return toLocationSnapshot(fixedLocation, now)
            }
        }

        return bestFix?.let { location ->
            toLocationSnapshot(location, now)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocationFix(
        locationManager: LocationManager,
        provider: String,
        timeoutMs: Long
    ): Location? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellationSignal = CancellationSignal()
                    continuation.invokeOnCancellation {
                        cancellationSignal.cancel()
                    }
                    runCatching {
                        locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            ContextCompat.getMainExecutor(this@CrisisLinkForegroundService)
                        ) { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }
                    }.onFailure {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            } else {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching {
                                locationManager.removeUpdates(this)
                            }
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }

                        override fun onProviderDisabled(provider: String) {
                            runCatching {
                                locationManager.removeUpdates(this)
                            }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: android.os.Bundle?
                        ) = Unit
                    }
                    continuation.invokeOnCancellation {
                        runCatching {
                            locationManager.removeUpdates(listener)
                        }
                    }
                    runCatching {
                        locationManager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }.onFailure {
                        runCatching {
                            locationManager.removeUpdates(listener)
                        }
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBestLocationSnapshot(now: Long): LocationSnapshot? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { isPlausibleLocation(it) }

        val best = candidates.minByOrNull { locationScore(now, it) } ?: return null
        return toLocationSnapshot(best, now)
    }

    private fun toLocationSnapshot(location: Location, now: Long): LocationSnapshot {
        return LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { it.isFinite() && it > 0f },
            provider = location.provider ?: "unknown",
            capturedAtMillis = location.time.takeIf { it > 0L } ?: now
        )
    }

    private fun hasPreciseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPlausibleLocation(location: Location): Boolean {
        return location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0
    }

    private fun locationScore(now: Long, location: Location): Double {
        val locationTime = location.time.takeIf { it > 0L } ?: now
        val ageMillis = (now - locationTime).coerceAtLeast(0L).toDouble()
        val accuracyPenalty = location.accuracy
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 250.0
        return ageMillis + (accuracyPenalty * 120.0)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.crisis_link_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.crisis_link_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(activeSignalCount: Int): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.crisis_link_notification_title))
            .setContentText(
                getString(
                    R.string.crisis_link_notification_text,
                    activeSignalCount
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationIfNeeded(activeSignalCount: Int, now: Long, force: Boolean = false) {
        val countChanged = activeSignalCount != lastNotifiedActiveSignalCount
        val refreshWindowElapsed = now - lastNotificationUpdateAtMillis >= NOTIFICATION_REFRESH_INTERVAL_MS
        if (!force && !countChanged && !refreshWindowElapsed) {
            return
        }
        lastNotifiedActiveSignalCount = activeSignalCount
        lastNotificationUpdateAtMillis = now
        updateNotification(activeSignalCount)
    }

    private fun updateNotification(activeSignalCount: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(activeSignalCount))
    }

    private fun normalizeSignalId(value: String?): String? {
        val trimmed = value?.trim()?.lowercase(Locale.US).orEmpty()
        if (!trimmed.startsWith("cc-")) return null
        val hex = trimmed.removePrefix("cc-")
        if (hex.length != SIGNAL_HEX_LENGTH) return null
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return "cc-$hex"
    }

    private fun normalizeAddress(value: String): String {
        return value.trim().uppercase(Locale.US)
    }

    private fun normalizeRole(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return when (normalized) {
            "field_team", "field-team", "ft" -> "fieldteam"
            else -> normalized
        }
    }

    private fun isRescueRole(role: String?): Boolean {
        return role in RESCUE_ROLES
    }

    private fun slugify(value: String): String {
        val lowered = value.trim().lowercase(Locale.US)
        val builder = StringBuilder(lowered.length)
        var pendingDash = false
        lowered.forEach { ch ->
            when {
                ch.isLetterOrDigit() -> {
                    if (pendingDash && builder.isNotEmpty()) {
                        builder.append('-')
                    }
                    builder.append(ch)
                    pendingDash = false
                }

                builder.isNotEmpty() -> pendingDash = true
            }
        }
        val result = builder.toString().trim('-')
        return if (result.isBlank()) "international" else result
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.orEmpty() ?: ""
    }

    private suspend fun <T> Task<T>.awaitResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
            addOnCanceledListener {
                continuation.cancel()
            }
        }
    }

    private data class SignalObservation(
        val signalId: String,
        val address: String,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val victimLocation: BlePeerIdentityUtils.PeerLocationSnapshot? = null,
        val estimatedVictimLocation: BlePeerIdentityUtils.PeerLocationSnapshot? = null,
        val relativeEstimate: BlePeerIdentityUtils.RelativeVictimEstimate? = null,
        val victimDisplayName: String? = null,
        val victimBatteryPercent: Int? = null,
        val locationMetadataUpdatedAtMillis: Long = 0L
    ) {
        fun hasVictimMetadata(): Boolean {
            return victimLocation != null ||
                estimatedVictimLocation != null ||
                relativeEstimate != null ||
                !victimDisplayName.isNullOrBlank() ||
                victimBatteryPercent != null
        }
    }

    private data class RescuerProfile(
        val uid: String,
        val role: String,
        val agency: String,
        val agencySlug: String,
        val operatorName: String,
        val username: String,
        val country: String
    )

    private data class LocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val provider: String,
        val capturedAtMillis: Long
    ) {
        fun toMap(): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "lat" to latitude,
                "lon" to longitude,
                "provider" to provider,
                "capturedAtMillis" to capturedAtMillis
            )
            accuracyMeters?.let { value ->
                map["accuracyMeters"] = value
            }
            return map
        }
    }

    private fun BlePeerIdentityUtils.PeerLocationSnapshot.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "lat" to latitude,
            "lon" to longitude,
            "capturedAtMillis" to capturedAtMillis,
            "source" to source
        )
        accuracyMeters?.let { value ->
            map["accuracyMeters"] = value
        }
        return map
    }

    private fun BlePeerIdentityUtils.PeerLocationSnapshot.toJson(): JSONObject {
        return JSONObject().apply {
            put("lat", latitude)
            put("lon", longitude)
            put("capturedAtMillis", capturedAtMillis)
            put("source", source)
            accuracyMeters?.let { value ->
                put("accuracyMeters", value.toDouble())
            }
        }
    }

    private fun parsePeerLocationSnapshot(
        json: JSONObject?
    ): BlePeerIdentityUtils.PeerLocationSnapshot? {
        json ?: return null
        val latitude = json.optDouble("lat", Double.NaN)
        val longitude = json.optDouble("lon", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0
        ) {
            return null
        }
        return BlePeerIdentityUtils.PeerLocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = json.optDouble("accuracyMeters", Double.NaN)
                .takeIf { it.isFinite() && it > 0.0 }
                ?.toFloat(),
            capturedAtMillis = json.optLong("capturedAtMillis", 0L)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            source = json.optString("source", "unknown").ifBlank { "unknown" }
        )
    }

    private fun BlePeerIdentityUtils.RelativeVictimEstimate.toMap(): Map<String, Any> {
        val originGps = mutableMapOf<String, Any>(
            "lat" to originLatitude,
            "lon" to originLongitude,
            "capturedAtMillis" to originCapturedAtMillis
        )
        originAccuracyMeters?.let { accuracy ->
            originGps["accuracyMeters"] = accuracy
        }
        val map = mutableMapOf<String, Any>(
            "bearingDegrees" to bearingDegrees,
            "distanceMeters" to distanceMeters,
            "headingSource" to headingSource,
            "originGps" to originGps,
            "sampledAtMillis" to sampledAtMillis
        )
        confidence?.let { value ->
            map["confidence"] = value
        }
        rssi?.let { value ->
            map["rssi"] = value
        }
        return map
    }

    private fun BlePeerIdentityUtils.RelativeVictimEstimate.originToMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "lat" to originLatitude,
            "lon" to originLongitude,
            "capturedAtMillis" to originCapturedAtMillis,
            "source" to "rescuer_origin"
        )
        originAccuracyMeters?.let { accuracy ->
            map["accuracyMeters"] = accuracy
        }
        return map
    }

    private fun BlePeerIdentityUtils.RelativeVictimEstimate.toJson(): JSONObject {
        return JSONObject().apply {
            put("bearingDegrees", bearingDegrees)
            put("distanceMeters", distanceMeters)
            put("headingSource", headingSource)
            put("sampledAtMillis", sampledAtMillis)
            put(
                "originGps",
                JSONObject().apply {
                    put("lat", originLatitude)
                    put("lon", originLongitude)
                    put("capturedAtMillis", originCapturedAtMillis)
                    originAccuracyMeters?.let { accuracy ->
                        put("accuracyMeters", accuracy.toDouble())
                    }
                }
            )
            confidence?.let { value ->
                put("confidence", value.toDouble())
            }
            rssi?.let { value ->
                put("rssi", value)
            }
        }
    }

    private fun parseRelativeEstimate(
        json: JSONObject?
    ): BlePeerIdentityUtils.RelativeVictimEstimate? {
        json ?: return null
        val bearingDegrees = json.optDouble("bearingDegrees", Double.NaN)
        val distanceMeters = json.optDouble("distanceMeters", Double.NaN)
        val originGps = json.optJSONObject("originGps") ?: return null
        val originLatitude = originGps.optDouble("lat", Double.NaN)
        val originLongitude = originGps.optDouble("lon", Double.NaN)
        if (!bearingDegrees.isFinite() || !distanceMeters.isFinite() || distanceMeters <= 0.0 ||
            !originLatitude.isFinite() || !originLongitude.isFinite() ||
            originLatitude !in -90.0..90.0 || originLongitude !in -180.0..180.0
        ) {
            return null
        }
        return BlePeerIdentityUtils.RelativeVictimEstimate(
            bearingDegrees = bearingDegrees,
            distanceMeters = distanceMeters,
            confidence = json.optDouble("confidence", Double.NaN)
                .takeIf { it.isFinite() }
                ?.toFloat(),
            originLatitude = originLatitude,
            originLongitude = originLongitude,
            originAccuracyMeters = originGps.optDouble("accuracyMeters", Double.NaN)
                .takeIf { it.isFinite() && it > 0.0 }
                ?.toFloat(),
            originCapturedAtMillis = originGps.optLong("capturedAtMillis", 0L)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            headingSource = json.optString("headingSource", "unknown").ifBlank { "unknown" },
            rssi = json.optInt("rssi", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            sampledAtMillis = json.optLong("sampledAtMillis", 0L)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )
    }

    private data class CrisisLinkSyncPayload(
        val uid: String,
        val role: String,
        val agency: String,
        val agencySlug: String,
        val operatorName: String,
        val username: String,
        val country: String,
        val rescuerDeviceId: String,
        val totalUniqueSignalCount: Long,
        val totalScanEventCount: Long,
        val location: LocationSnapshot?,
        val activeSignals: List<SignalObservation>,
        val capturedAtMillis: Long
    )

    private data class SignalSyncFingerprint(
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val address: String,
        val victimLocation: BlePeerIdentityUtils.PeerLocationSnapshot?,
        val estimatedVictimLocation: BlePeerIdentityUtils.PeerLocationSnapshot?,
        val relativeEstimate: BlePeerIdentityUtils.RelativeVictimEstimate?,
        val victimDisplayName: String?,
        val victimBatteryPercent: Int?,
        val locationMetadataUpdatedAtMillis: Long
    )

    private data class BatteryState(
        val levelPercent: Int?,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean
    )

    private data class LastSuccessfulSyncState(
        val activeSignalIds: Set<String>,
        val signalFingerprints: Map<String, SignalSyncFingerprint>,
        val location: LocationSnapshot?,
        val totalUniqueSignalCount: Long,
        val totalScanEventCount: Long,
        val syncedAtMillis: Long
    )

    private data class LiveLocationSyncSettings(
        val enabled: Boolean,
        val intervalSeconds: Int
    ) {
        val intervalMillis: Long
            get() = intervalSeconds * 1_000L
    }

    private enum class SyncProfile {
        REALTIME,
        BALANCED,
        ECO
    }

    private enum class DeliveryResult {
        Success,
        RecoverableFailure,
        AuthorizationFailure,
        PermanentFailure
    }

    private data class DecodedQueuePayload(
        val plaintext: ByteArray,
        val isLegacyPlaintext: Boolean
    )

    companion object {
        private const val TAG = "CrisisLinkService"
        private const val NOTIFICATION_CHANNEL_ID = "crisis_link_channel"
        private const val NOTIFICATION_ID = 2013
        private const val ACTIVE_SYNC_INTERVAL_MS = 15_000L
        private const val IDLE_SYNC_INTERVAL_MS = 60_000L
        private const val ERROR_RETRY_INTERVAL_MS = 30_000L
        private const val AUTH_FAILURE_RETRY_INTERVAL_MS = 120_000L
        private const val MIN_SYNC_INTERVAL_MS = 10_000L
        private const val MAX_SYNC_INTERVAL_MS = 10 * 60_000L
        private const val PROFILE_CACHE_TTL_MS = 10 * 60_000L
        private const val ACTIVE_SIGNAL_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val IDLE_HEARTBEAT_INTERVAL_MS = 3 * 60_000L
        private const val IMMEDIATE_SYNC_THROTTLE_MS = 5_000L
        private const val BALANCED_ACTIVE_SYNC_INTERVAL_MS = 45_000L
        private const val BALANCED_IDLE_SYNC_INTERVAL_MS = 2 * 60_000L
        private const val BALANCED_ACTIVE_SIGNAL_HEARTBEAT_INTERVAL_MS = 90_000L
        private const val BALANCED_IDLE_HEARTBEAT_INTERVAL_MS = 5 * 60_000L
        private const val BALANCED_IMMEDIATE_SYNC_THROTTLE_MS = 20_000L
        private const val ECO_ACTIVE_SYNC_INTERVAL_MS = 2 * 60_000L
        private const val ECO_IDLE_SYNC_INTERVAL_MS = 5 * 60_000L
        private const val ECO_ACTIVE_SIGNAL_HEARTBEAT_INTERVAL_MS = 3 * 60_000L
        private const val ECO_IDLE_HEARTBEAT_INTERVAL_MS = 10 * 60_000L
        private const val ECO_IMMEDIATE_SYNC_THROTTLE_MS = 45_000L
        private const val REALTIME_MIN_DELIVERY_INTERVAL_MS = 15_000L
        private const val BALANCED_MIN_DELIVERY_INTERVAL_MS = 90_000L
        private const val ECO_MIN_DELIVERY_INTERVAL_MS = 4 * 60_000L
        private const val REALTIME_SCAN_EVENT_DELTA_SYNC_THRESHOLD = 25L
        private const val BALANCED_SCAN_EVENT_DELTA_SYNC_THRESHOLD = 120L
        private const val ECO_SCAN_EVENT_DELTA_SYNC_THRESHOLD = 300L
        private const val LOW_BATTERY_LEVEL_PERCENT = 20
        private const val LIVE_LOCATION_UNAVAILABLE_LOG_INTERVAL_MS = 2 * 60_000L
        private const val LIVE_LOCATION_MIN_LAST_KNOWN_MAX_AGE_MS = 20_000L
        private const val LIVE_LOCATION_FRESH_FIX_MAX_AGE_MS = 20_000L
        private const val LIVE_LOCATION_NETWORK_FIX_TIMEOUT_MS = 4_000L
        private const val LIVE_LOCATION_GPS_FIX_TIMEOUT_MS = 7_000L
        private const val BATTERY_STATE_CACHE_TTL_MS = 30_000L
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 5_000L
        private const val NO_VALIDATED_NETWORK_PENDING_SYNC_INTERVAL_MS = 8 * 60_000L
        private const val LOCATION_DISTANCE_DELTA_METERS = 25f
        private const val LOCATION_ACCURACY_IMPROVEMENT_METERS = 20f
        private const val MAX_CONSECUTIVE_AUTH_FAILURES = 5
        private const val ACTIVE_SIGNAL_WINDOW_MS = 60_000L
        private const val STALE_SIGNAL_RETENTION_MS = 10 * 60_000L
        private const val MAX_ACTIVE_SIGNALS_PER_SYNC = 96
        private const val MAX_SIGNAL_DISPLAY_NAME_LENGTH = 96
        private const val MAX_PENDING_PAYLOADS = 96
        private const val PENDING_QUEUE_FILE_NAME = "crisis_link_pending_queue.json"
        private const val FIRST_SEEN_FILE_NAME = "crisis_link_first_seen.json"
        private const val FIRST_SEEN_RETENTION_MS = 48 * 60 * 60 * 1000L
        private const val FIRST_SEEN_WRITE_THROTTLE_MS = 30_000L
        private const val TRACK_POINT_MIN_INTERVAL_MS = 60_000L
        private const val TRACK_POINT_MIN_DISTANCE_METERS = 25f
        private const val TRACK_POINT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val IMMEDIATE_REASON_ACTIVE_SIGNALS_CHANGED = "active_signals_changed"
        private const val IMMEDIATE_REASON_SIGNAL_LOCATION_CHANGED = "signal_location_changed"
        private const val IMMEDIATE_REASON_NETWORK_AVAILABLE = "network_available"
        private const val IMMEDIATE_REASON_NETWORK_CAPABILITIES_CHANGED = "network_capabilities_changed"
        private const val SIGNAL_HEX_LENGTH = 24
        private const val PANEL_COLLECTION = "agencyPanels"
        private const val RESPONDERS_COLLECTION = "responders"
        private const val SIGNALS_COLLECTION = "signals"
        private const val REPORTERS_COLLECTION = "reporters"
        private const val TRACK_COLLECTION = "track"
        private const val PANEL_SOURCE = "android-crisislink"
        private const val PANEL_SCHEMA_VERSION = 3
        private const val RESPONDER_SCHEMA_VERSION = 1
        private const val SIGNAL_SCHEMA_VERSION = 3
        private const val DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS = 60
        private val LIVE_LOCATION_INTERVAL_OPTIONS_SECONDS = listOf(30, 60, 90, 300)
        private val CRISIS_LINK_LIVE_LOCATION_ENABLED_PREF = booleanPreferencesKey(
            "rescue_crisis_link_live_location_enabled"
        )
        private val CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS_PREF = intPreferencesKey(
            "rescue_crisis_link_live_location_interval_seconds"
        )
        private val RESCUE_ROLES = setOf("admin", "fieldteam")

        fun start(context: Context) {
            val appContext = context.applicationContext
            refreshStartEligibilityAsync(appContext)
            if (cachedStartEligibility == false) {
                stop(appContext)
                return
            }
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, CrisisLinkForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, CrisisLinkForegroundService::class.java)
            )
        }

        fun notifySignalLocationUpdated(context: Context) {
            val instance = activeInstance
            if (instance != null) {
                instance.requestImmediateSync(IMMEDIATE_REASON_SIGNAL_LOCATION_CHANGED)
            } else {
                start(context.applicationContext)
            }
        }

        fun canStart(context: Context): Boolean {
            val appContext = context.applicationContext
            refreshStartEligibilityAsync(appContext)
            return cachedStartEligibility ?: false
        }

        suspend fun canStartSuspend(context: Context): Boolean {
            val appContext = context.applicationContext
            val canStart = runCatching {
                SecurityRepository(appContext).hasUsableStoredCertificate(allowExpired = true)
            }.getOrDefault(false)
            cachedStartEligibility = canStart
            return canStart
        }

        private fun refreshStartEligibilityAsync(context: Context) {
            eligibilityScope.launch {
                canStartSuspend(context)
            }
        }

        private val eligibilityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Outlives any single service instance: onDestroy cancels serviceScope before a launched
        // write's first suspension point resumes, so best-effort writes that must survive the
        // teardown (marking the responder inactive) run here instead.
        private val detachedWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile
        private var cachedStartEligibility: Boolean? = null
        @Volatile
        private var activeInstance: CrisisLinkForegroundService? = null
    }
}
