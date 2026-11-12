package com.auralis.crisisconnect.wear.independent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class IndependentJournalFile(context: Context, namespace: String = "independent-watch-journal-v1") : IndependentJournalStorage {
    init { require(namespace.matches(Regex("[a-zA-Z0-9-]{1,96}"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.enc"))
    private val alias = "crisis.watch.journal.v1.$namespace"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Watch journal key is unavailable; do not reset uncertain operations" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized override fun read(): List<IndependentOperation> {
        val clear = readData() ?: return emptyList()
        val rows = JSONArray(clear.toString(Charsets.UTF_8))
        require(rows.length() <= 512)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            IndependentOperation(row.getString("id"), row.getString("action"), row.getString("subjectID"), row.getString("payload"), row.getString("state"))
        }
    }
    @Synchronized fun readData(): ByteArray? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { stream ->
            require(stream.channel.size() <= 16_000_000)
            stream.readBytes()
        }
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        cipher.updateAAD(alias.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    @Synchronized override fun write(operations: List<IndependentOperation>) {
        val rows = JSONArray()
        operations.forEach { rows.put(JSONObject().put("id", it.id).put("action", it.action).put("subjectID", it.subjectID).put("payload", it.payload).put("state", it.state)) }
        writeData(rows.toString().toByteArray())
    }
    @Synchronized fun writeData(clear: ByteArray) {
        require(clear.size <= 15_000_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()))
        cipher.updateAAD(alias.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(clear)
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
