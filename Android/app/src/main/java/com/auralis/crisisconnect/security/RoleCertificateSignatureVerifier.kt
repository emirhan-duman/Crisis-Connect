package com.auralis.crisisconnect.security

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies backend-issued role certificates with the trusted master public key.
 */
object RoleCertificateSignatureVerifier {

    fun verify(roleCertificate: RoleCertificate, publicKeyBase64: String): Boolean {
        if (!roleCertificate.hasValidShape()) {
            return false
        }
        return runCatching {
            Crypto.verifyCertificate(
                masterPublicKey = masterPublicKey,
                devicePublicKey = roleCertificate.signingPayload(publicKeyBase64),
                certificateBytes = roleCertificate.signatureBytes(),
            )
        }.getOrDefault(false)
    }

    private val masterPublicKey: PublicKey by lazy {
        val decoded = Base64.decode(
            MASTER_PUBLIC_KEY_BASE64.trim().replace("\n", ""),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        val spec = X509EncodedKeySpec(decoded)
        KeyFactory.getInstance("EC").generatePublic(spec)
    }

    // Master ECDSA public key used to verify role certificates issued by backend.
    private const val MASTER_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnwait56qp0FAWjpL7EpGev3aYCO4DKI1Rswibgvqx438LesLoghU/hIDmmJPjKiZfDBUc1Xf0hMgZLKcJoQnEw=="
}
