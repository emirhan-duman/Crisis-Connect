package com.auralis.crisisconnect.service

import com.auralis.crisisconnect.service.voice.VoipConfig
import com.auralis.opuscodec.Opus
import org.json.JSONObject

internal class RfcommCallProtocolDelegate {
    fun sanitizeCfg(
        sampleRate: Int,
        frameMs: Int,
        framesPerPacket: Int,
        bitrate: Int,
        encrypted: Boolean,
        hasKey: Boolean
    ): Pair<Profile, AudioCfg>? {
        val profile = if (bitrate < VoipConfig.OPUS_BITRATE_BPS) Profile.NB else Profile.WB
        val sr = sampleRate.takeIf { it == VoipConfig.SAMPLE_RATE_HZ } ?: VoipConfig.SAMPLE_RATE_HZ
        val fps = framesPerPacket.coerceIn(1, 2)
        val frame = frameMs.takeIf { it == VoipConfig.FRAME_DURATION_MS } ?: VoipConfig.FRAME_DURATION_MS
        val br = when (profile) {
            Profile.WB -> bitrate.coerceIn(16_000, VoipConfig.OPUS_BITRATE_BPS)
            Profile.NB -> bitrate.coerceIn(12_000, 16_000)
        }
        val cfg = AudioCfg(
            sr = sr,
            ch = 1,
            frm = frame,
            fps = fps,
            br = br,
            encrypted = encrypted && hasKey
        )
        return if (cfg.frameSamples > 0) profile to cfg else null
    }

    fun resolveOpusApplication(): Int = 2048

    fun setEncoderBitrate(opus: Opus, bitrate: Int) {
        runCatching { opus.encoderSetBitrate(bitrate) }
    }

    fun enableOpusControls(opus: Opus, bitrate: Int) {
        val booleanClass: Class<*> = Boolean::class.javaPrimitiveType ?: Boolean::class.java
        val intClass: Class<*> = Int::class.javaPrimitiveType ?: Int::class.java
        tryInvokeOpus(opus, "encoderSetSignal", intClass, 3_001)
        tryInvokeOpus(opus, "encoderSetDtx", booleanClass, true)
        tryInvokeOpus(opus, "encoderSetInbandFec", booleanClass, true)
        tryInvokeOpus(opus, "encoderSetPacketLossPerc", intClass, VoipConfig.OPUS_PACKET_LOSS_PERC)
        tryInvokeOpus(opus, "encoderSetVbrConstraint", booleanClass, true)
        tryInvokeOpus(opus, "encoderSetBitrate", intClass, bitrate)
    }

    fun opusSampleRate(sampleRate: Int): Int? = when (sampleRate) {
        8_000, 12_000, 16_000, 24_000, 48_000 -> sampleRate
        else -> null
    }

    fun opusChannels(channels: Int): Int? = when (channels) {
        1, 2 -> channels
        else -> null
    }

    fun opusFrameSize(samples: Int): Int? = when (samples) {
        120, 160, 240, 320, 480, 640, 960, 1280, 1920, 2560, 2880 -> samples
        else -> null
    }

    fun buildOffer(callId: String, cfg: AudioCfg): JSONObject = JSONObject().apply {
        put("type", "call:offer")
        put("id", callId)
        put("sampleRate", cfg.sr)
        put("channels", cfg.ch)
        put("frameMs", cfg.frm)
        put("bitrate", cfg.br)
        put("framesPerPacket", cfg.fps)
        put("encrypted", cfg.encrypted)
    }

    fun buildRing(callId: String): JSONObject = JSONObject().apply {
        put("type", "call:ring")
        put("id", callId)
    }

    fun buildAccept(callId: String): JSONObject = JSONObject().apply {
        put("type", "call:accept")
        put("id", callId)
    }

    fun buildReject(callId: String, reason: String): JSONObject = JSONObject().apply {
        put("type", "call:reject")
        put("id", callId)
        put("reason", reason)
    }

    fun buildEnd(callId: String, reason: String): JSONObject = JSONObject().apply {
        put("type", "call:end")
        put("id", callId)
        put("reason", reason)
    }

    fun sendCallCfg(
        callId: String,
        cfg: AudioCfg,
        sendJson: (JSONObject) -> Boolean
    ): Boolean {
        val json = JSONObject().apply {
            put("type", "call:cfg")
            put("id", callId)
            put("sr", cfg.sr)
            put("ch", cfg.ch)
            put("frm", cfg.frm)
            put("fps", cfg.fps)
            put("br", cfg.br)
            put("enc", cfg.encrypted)
        }
        return sendJson(json)
    }

    fun sendCallCfgAck(callId: String, ok: Boolean, sendJson: (JSONObject) -> Boolean): Boolean {
        val json = JSONObject().apply {
            put("type", "call:cfg_ack")
            put("id", callId)
            put("ok", ok)
        }
        return sendJson(json)
    }

    fun sendCallClockSyncRequest(
        callId: String,
        probeId: String,
        monotonicSendMs: Long,
        sendJson: (JSONObject) -> Boolean
    ): Boolean {
        val json = JSONObject().apply {
            put("type", "call:sync_req")
            put("id", callId)
            put("probeId", probeId)
            put("monoSendMs", monotonicSendMs)
        }
        return sendJson(json)
    }

    fun sendCallClockSyncResponse(
        callId: String,
        probeId: String,
        requesterMonotonicSendMs: Long,
        responderMonotonicReceiveMs: Long,
        responderMonotonicSendMs: Long,
        sendJson: (JSONObject) -> Boolean
    ): Boolean {
        val json = JSONObject().apply {
            put("type", "call:sync_resp")
            put("id", callId)
            put("probeId", probeId)
            put("reqMonoSendMs", requesterMonotonicSendMs)
            put("reqRecvMonoMs", responderMonotonicReceiveMs)
            put("respSendMonoMs", responderMonotonicSendMs)
        }
        return sendJson(json)
    }

    private fun tryInvokeOpus(opus: Opus, name: String, argType: Class<*>, value: Any) {
        runCatching {
            val method = opus::class.java.getMethod(name, argType)
            method.invoke(opus, value)
        }
    }
}
