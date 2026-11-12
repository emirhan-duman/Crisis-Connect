package com.auralis.crisisconnect.service

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object SecureRfcommText {
    fun seal(key: ByteArray?, uuid: String, text: String): String? = runCatching {
        require(key != null && (key.size == 16 || key.size == 32))
        require(uuid.matches(Regex("[A-Za-z0-9:~_-]{1,128}")))
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        "SEC_MSG|$uuid|${Base64.getEncoder().encodeToString(iv)}|${Base64.getEncoder().encodeToString(encrypted)}\n"
    }.getOrNull()
}
