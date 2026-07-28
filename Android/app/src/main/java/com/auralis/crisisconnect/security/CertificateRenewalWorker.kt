package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Background renewal of the device-bound rescue role certificate.
 *
 * Certificates live for 72h (backend CERTIFICATE_TTL_MS); this worker runs periodically with a
 * network constraint and silently re-provisions once less than [RENEWAL_LEAD_MILLIS] (24h) of
 * validity remains — so an authorized device that sees the internet at least once every couple of
 * days never lets its certificate lapse, even if the app is only ever in the background.
 *
 * Deliberately quiet: no banners or notifications. Guards make it a cheap no-op for everyone
 * else — having a stored certificate is the natural role gate (only admin/fieldteam devices ever
 * obtain one), so normal users never reach the network.
 */
class CertificateRenewalWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.isAnonymous) {
            return Result.success()
        }

        val repository = SecurityRepository(applicationContext)
        // allowExpired so a just-lapsed certificate is still renewed rather than stranded.
        val storedBytes = repository.getStoredCertificate(allowExpired = true)
            ?: return Result.success()
        val certificate = RoleCertificate.fromStorageBytes(storedBytes)
            ?: return Result.success()

        val remainingMillis = certificate.expiresAtMillis - System.currentTimeMillis()
        if (remainingMillis > RENEWAL_LEAD_MILLIS) {
            Log.d(TAG, "Certificate healthy for ${remainingMillis / 3_600_000}h; nothing to renew")
            return Result.success()
        }

        val deviceId = LocalKeyStorage.getOrCreateRescueDeviceId(applicationContext)
        return runCatching {
            repository.provisionAndStoreCertificate(deviceId)
        }.fold(
            onSuccess = {
                Log.i(TAG, "Background-renewed rescue certificate (role=${it.role})")
                Result.success()
            },
            onFailure = { error ->
                // Transient (offline mid-run, backend hiccup): retry with backoff; the next
                // periodic run is a further safety net. The 72h offline grace in
                // SecurityRepository keeps rescue mode working meanwhile.
                Log.w(TAG, "Background certificate renewal failed; will retry", error)
                if (runAttemptCount < MAX_RETRIES_PER_RUN) Result.retry() else Result.success()
            }
        )
    }

    companion object {
        private const val TAG = "CertRenewalWorker"
        private const val UNIQUE_WORK_NAME = "rescue_certificate_renewal"
        private const val RENEWAL_LEAD_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_RETRIES_PER_RUN = 3

        /**
         * Registers the periodic renewal check (every 6h, network required). KEEP policy:
         * re-registering on every app start is a no-op. 6h cadence gives 3-4 renewal
         * opportunities inside the 24h lead window while staying battery-trivial — the
         * no-renewal path is a local read with no network use.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CertificateRenewalWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
