package com.auralis.crisisconnect.screens.Chat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_RFCOMM
import com.auralis.crisisconnect.data.REMOTE_PLATFORM_UNKNOWN
import com.auralis.crisisconnect.service.RfcommForegroundService.CallDirection
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Scripted chat timeline used by the debug "Screenshot Demo Mode" toggle.
 *
 * Everything lives in-memory except for a tiny placeholder audio file that
 * backs the voice-message bubble — without a file on disk the
 * [ChatScreenTimelineUi] voice bubble falls back to an "Unavailable" row,
 * which looks broken in screenshots. The placeholder is written to the
 * cache dir only and gets no-op'd on subsequent calls.
 *
 * Toggling the flag off restores the real session cleanly (no DB writes,
 * the placeholder file is harmless clutter in cache).
 */
internal object ChatScreenshotDemoScenario {

    private const val TAG = "ChatDemoScenario"

    internal const val DEMO_CONTACT_NAME: String = "Mert Demir"

    /**
     * Stable, non-BLE, non-mesh session code for the demo contact. Chosen
     * so [com.auralis.crisisconnect.navigation.buildConversationRoute]
     * routes to the standard `chat/` destination, where
     * [ChatScreenViewModel.initialize] picks up the demo override.
     */
    internal const val DEMO_SESSION_CODE: String = "demo-screenshot-session"

    private const val DEMO_ADDRESS: String = "00:11:22:33:44:55"
    private const val DEMO_VOICE_DURATION_MS: Long = 8_000L
    private const val DEMO_VOICE_FILENAME: String = "demo_voice_alex.m4a"

    /** Timeline payload returned to the ViewModel for in-memory injection. */
    internal data class DemoTimeline(
        val messages: List<ChatMessage>,
        val callEvents: List<CallEvent>
    )

    /**
     * Build the fake contact that gets injected into the Messages list.
     * Inert fields: unusable address, no AES key, default transport.
     */
    fun buildDemoContact(): Contact = Contact(
        name = DEMO_CONTACT_NAME,
        aesKey = "",
        sessionCode = DEMO_SESSION_CODE,
        verified = true,
        verifiedIdentityKey = "",
        verifiedAt = null,
        address = DEMO_ADDRESS,
        remoteSessionCode = DEMO_SESSION_CODE,
        preferredTransport = PREFERRED_TRANSPORT_RFCOMM,
        remotePlatform = REMOTE_PLATFORM_UNKNOWN,
        bleShareId = "",
        lastKnownBleAddress = "",
        remoteDeviceId = ""
    )

    /**
     * Build the scripted Japanese scenario anchored to today's local
     * 20:56 / 20:58 / 21:00 so the bubble timestamps match what the user
     * expects to see in the screenshot.
     *
     * If today's 21:00 hasn't happened yet (e.g. running in the morning),
     * the anchor shifts to yesterday so the timeline is strictly in the
     * past relative to now.
     */
    fun buildTimeline(context: Context, sessionCode: String): DemoTimeline {
        val voicePath = writePlaceholderAudioFile(context)

        // Anchor relative to "now" so the conversation reads as a fresh
        // emergency that just happened. Spread mirrors the spec the user
        // wanted: missed call 8 min ago, replies cascading up to the
        // current minute on the latest outgoing message.
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val tCall = now - 8 * minute
        val tIncomingText = now - 5 * minute
        val tIncomingVoice = now - 4 * minute
        val tOutgoing1 = now - 3 * minute
        val tOutgoing2 = now

        val messages = listOf(
            textIncoming(
                sessionCode = sessionCode,
                uuid = "demo-msg-01",
                text = "İyi misin? Şebeke gitti.",
                timestampMillis = tIncomingText
            ),
            audioIncoming(
                sessionCode = sessionCode,
                uuid = "demo-msg-02",
                audioPath = voicePath,
                durationMillis = DEMO_VOICE_DURATION_MS,
                timestampMillis = tIncomingVoice
            ),
            textOutgoing(
                sessionCode = sessionCode,
                uuid = "demo-msg-03",
                text = "Binadan çıktım. Sen?",
                timestampMillis = tOutgoing1,
                deliveryStatus = MessageDeliveryStatus.READ
            ),
            textOutgoing(
                sessionCode = sessionCode,
                uuid = "demo-msg-04",
                text = "Annemle toplanma noktasına gidiyoruz.",
                timestampMillis = tOutgoing2,
                deliveryStatus = MessageDeliveryStatus.READ
            )
        )

        val callEvents = listOf(
            CallEvent(
                id = "demo-call-01",
                sessionCode = sessionCode,
                timestampMillis = tCall,
                direction = CallDirection.INCOMING,
                result = CallResult.MISSED,
                durationMillis = null
            )
        )

        return DemoTimeline(messages = messages, callEvents = callEvents)
    }

    /**
     * Preview text shown on the Messages list row for the demo contact.
     * We want the most recent outgoing line ("無事だよ…") to match the
     * scripted timeline's last entry.
     */
    fun buildDemoLatestMessage(context: Context, nowMillis: Long): ChatMessage {
        val timeline = buildTimeline(context, DEMO_SESSION_CODE)
        return timeline.messages.last()
    }

    // ---- helpers ----

    /**
     * Ensure there is a tiny placeholder file at a stable cache path so
     * [ChatScreenComposerUi]'s `audioUri` check (which requires
     * `file.exists()`) passes. ExoPlayer will fail to prepare the file,
     * but the bubble UI renders with [DEMO_VOICE_DURATION_MS] via
     * `initialDurationMillis`.
     *
     * We also pre-seed the [AudioWaveformRepository] cache so the bubble
     * shows a realistic speech-like waveform instead of the flat
     * placeholder bars. The repository reads
     * `cacheDir/audio_waveforms/<sha1(uri)>` first and only falls back to
     * MediaCodec decoding (which would fail on our placeholder file) if
     * the cache is missing or empty.
     */
    private fun writePlaceholderAudioFile(context: Context): String {
        val dir = File(context.cacheDir, "demo_voice").apply { mkdirs() }
        val file = File(dir, DEMO_VOICE_FILENAME)
        if (!file.exists() || file.length() == 0L) {
            runCatching {
                // One byte is enough — we just need the `File.exists()` check
                // in `AudioMessageContent` to return true. Playback will fail
                // silently, which is fine for screenshots.
                file.writeBytes(byteArrayOf(0))
            }.onFailure { error ->
                Log.w(TAG, "Failed to write demo voice placeholder", error)
            }
        }
        seedDemoWaveformCache(context, file)
        return file.absolutePath
    }

    /**
     * Pre-populate the waveform cache slot that
     * [com.auralis.crisisconnect.audio.AudioWaveformRepository] will look up
     * for this file's Uri, so the demo bubble paints a realistic envelope
     * even though the underlying audio bytes are bogus.
     *
     * The generator uses a fixed seed so the waveform is stable across
     * launches (screenshots look identical if taken twice). Bar count must
     * match `AudioWaveformRepository.DEFAULT_BAR_COUNT` (150); if that
     * changes, the repository will resample gracefully but a direct match
     * avoids the interpolation.
     */
    private fun seedDemoWaveformCache(context: Context, audioFile: File) {
        val uri = Uri.fromFile(audioFile)
        val cacheKey = sha1Hex(uri.toString())
        val waveformCacheDir = File(context.cacheDir, "audio_waveforms").apply { mkdirs() }
        val cacheFile = File(waveformCacheDir, cacheKey)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return // already seeded for this install
        }
        val bars = generateRealisticWaveform(barCount = 150, seed = 0xCC_51_1E_5FL)
        runCatching {
            cacheFile.writeText(bars.joinToString(","))
        }.onFailure { error ->
            Log.w(TAG, "Failed to seed demo waveform cache", error)
        }
    }

    /**
     * Generate a deterministic speech-like envelope:
     *  - short fade-in / fade-out at the edges,
     *  - a low-frequency envelope that rises and falls over the duration,
     *  - syllable-scale modulation at a faster rate,
     *  - small seeded jitter so adjacent bars aren't identical.
     *
     * Output range is 0f..1f, suitable to drop straight into the
     * repository cache format.
     */
    private fun generateRealisticWaveform(barCount: Int, seed: Long): List<Float> {
        val rnd = Random(seed)
        val raw = FloatArray(barCount)
        for (i in 0 until barCount) {
            val t = i.toFloat() / (barCount - 1).toFloat() // 0..1

            // Edge fade: ramp in the first 6% and out over the last 10%.
            val fadeIn = (t / 0.06f).coerceIn(0f, 1f)
            val fadeOut = ((1f - t) / 0.10f).coerceIn(0f, 1f)
            val edgeEnvelope = (fadeIn * fadeOut).coerceIn(0f, 1f)

            // Slow breathing envelope — one broad hump across the clip,
            // slightly biased toward the middle-front (typical for someone
            // starting softly and getting clearer).
            val slow = 0.55f + 0.35f * sin((t * 0.85f + 0.1f) * PI).toFloat()

            // Syllable-rate modulation — a few peaks per second.
            val syllable = 0.45f + 0.35f * sin(t * 11f * PI).toFloat() +
                0.18f * sin(t * 23f * PI + 0.7f).toFloat()

            // Small per-bar jitter to break up the smooth curves.
            val jitter = (rnd.nextFloat() - 0.5f) * 0.22f

            val combined = edgeEnvelope * (slow * 0.55f + syllable * 0.55f + jitter + 0.05f)
            raw[i] = combined.coerceIn(0f, 1f)
        }

        // Normalize so the loudest bar hits 0.95 (leaving a touch of
        // headroom). If for some reason every bar is zero, return a tiny
        // constant so `hasMeaningfulAmplitude()` still passes.
        val max = raw.maxOrNull() ?: 0f
        return if (max <= 0f) {
            List(barCount) { 0.2f }
        } else {
            val scale = 0.95f / max
            raw.map { (it * scale).coerceIn(0f, 1f) }
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun textIncoming(
        sessionCode: String,
        uuid: String,
        text: String,
        timestampMillis: Long
    ): ChatMessage = ChatMessage(
        id = uuid.hashCode().toLong(),
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = text,
        messageType = MessageType.TEXT,
        isLocal = false,
        isRead = true,
        timestampMillis = timestampMillis,
        originalTimestampMillis = timestampMillis,
        deliveryStatus = null,
        senderDisplayName = DEMO_CONTACT_NAME
    )

    private fun textOutgoing(
        sessionCode: String,
        uuid: String,
        text: String,
        timestampMillis: Long,
        deliveryStatus: MessageDeliveryStatus
    ): ChatMessage = ChatMessage(
        id = uuid.hashCode().toLong(),
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = text,
        messageType = MessageType.TEXT,
        isLocal = true,
        isRead = true,
        timestampMillis = timestampMillis,
        originalTimestampMillis = timestampMillis,
        deliveryStatus = deliveryStatus
    )

    private fun audioIncoming(
        sessionCode: String,
        uuid: String,
        audioPath: String,
        durationMillis: Long,
        timestampMillis: Long
    ): ChatMessage = ChatMessage(
        id = uuid.hashCode().toLong(),
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = "",
        messageType = MessageType.AUDIO,
        audioFilePath = audioPath,
        audioDurationMillis = durationMillis,
        isLocal = false,
        isRead = true,
        timestampMillis = timestampMillis,
        originalTimestampMillis = timestampMillis,
        deliveryStatus = null,
        senderDisplayName = DEMO_CONTACT_NAME
    )
}
