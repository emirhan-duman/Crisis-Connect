package com.auralis.crisisconnect.messaging.call.sfu

/**
 * Feature gate for the SFU (Cloudflare Realtime + MLS-E2EE) authority-call path that replaces the legacy
 * P2P [com.auralis.crisisconnect.messaging.call.InternetCallManager] for authority (hierarchy + agency)
 * calls — the transport the web dashboard now uses. Authority media is permitted only when the native
 * MLS handshake and WebRTC frame-crypto bridges are both available. Citizen P2P calls are never
 * affected by this flag.
 */
object SfuCallConfig {
    /**
     * When true, authority calls place/receive `roomId`-based SFU invites instead of P2P SDP offers.
     *
     * This deliberately fails closed: it replaces the legacy authority P2P path, requires SFU-v2,
     * and refuses to join if MLS or per-frame encryption is unavailable. Audio, camera video and
     * screen-share senders/receivers all use the mandatory frame-crypto bridge.
     */
    const val ENABLED = true
}
