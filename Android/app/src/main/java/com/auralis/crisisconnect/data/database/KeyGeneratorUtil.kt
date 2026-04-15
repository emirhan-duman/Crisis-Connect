package com.auralis.crisisconnect.data.database

import android.util.Base64
import javax.crypto.KeyGenerator

object KeyGeneratorUtil {
    fun generateAesKeyBase64(): String {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()
        return Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP)
    }
}