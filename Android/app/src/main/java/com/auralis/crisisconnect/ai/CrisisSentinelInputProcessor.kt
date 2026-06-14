package com.auralis.crisisconnect.ai

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object CrisisSentinelInputProcessor {
    private const val MAX_EXTRACTED_CHARS = 12_000
    private const val MAX_FILE_BYTES = 64 * 1024

    suspend fun extractTextFromImage(context: Context, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val image = InputImage.fromFilePath(context.applicationContext, uri)
            val result = TextRecognition
                .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .awaitInputResult()
            result.text.trim().take(MAX_EXTRACTED_CHARS)
        }

    suspend fun extractTextFromFile(context: Context, uri: Uri): String =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { input ->
                input.readBytesLimited(MAX_FILE_BYTES + 1)
            } ?: throw IOException("Could not open selected file.")
            bytes.decodeToString()
                .replace("\u0000", "")
                .trim()
                .take(MAX_EXTRACTED_CHARS)
        }

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                output.write(buffer, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) break
        }
        return output.toByteArray()
    }
}

private suspend fun <T> Task<T>.awaitInputResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val result = task.result
                if (result != null) {
                    continuation.resume(result)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("Input processor returned a null result.")
                    )
                }
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Input processor task failed.")
                )
            }
        }
    }
