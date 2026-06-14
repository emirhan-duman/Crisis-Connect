package com.auralis.crisisconnect.network

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * Shared OkHttpClient factory for any direct HTTP calls outside the Firebase
 * SDK. Pinning is enforced via [CertificatePinner] in addition to the global
 * Network Security Config pin-sets (defined in `res/xml/network_security_config.xml`).
 *
 * Pinning policy:
 *  - Firebase / *.googleapis.com hosts are NOT pinned. See the comment block
 *    at the top of `network_security_config.xml` for the rationale.
 *  - Custom backend hosts MUST be pinned here AND in NSC. Two layers catch
 *    mistakes: NSC also catches calls made via WebViews / third-party SDKs.
 *  - Always ship a primary + offline backup pin. Rotate the backup whenever
 *    a new primary pin is added.
 *
 * To extract a SHA-256 SPKI pin from a live host:
 * ```
 * echo | openssl s_client -servername api.example.com -connect api.example.com:443 2>/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary \
 *   | openssl enc -base64
 * ```
 *
 * Then add the resulting `sha256/...` string to the [pinnerBuilder] below.
 */
object PinnedOkHttpClient {

    /**
     * Builds the shared CertificatePinner. No custom hosts are pinned yet
     * because the app currently goes through Firebase + MapLibre public
     * endpoints only. When a custom host is introduced, add entries like:
     *
     * ```
     * .add("api.crisisconnect.example.com",
     *      "sha256/REPLACE_WITH_PRIMARY_LEAF_SPKI",
     *      "sha256/REPLACE_WITH_OFFLINE_BACKUP_SPKI")
     * ```
     */
    private fun pinnerBuilder(): CertificatePinner.Builder = CertificatePinner.Builder()

    /**
     * Returns a fresh OkHttpClient with the project's pinning policy applied.
     * Callers may further customize (timeouts, interceptors) on top.
     */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(pinnerBuilder().build())
        .build()
}
