package com.auralis.crisisconnect.messaging.call.sfu

/**
 * Feature gate for the SFU (Cloudflare Realtime + MLS-E2EE) authority-call path that replaces the legacy
 * P2P [com.auralis.crisisconnect.messaging.call.InternetCallManager] for authority (hierarchy + agency)
 * calls — the transport the web dashboard now uses. Off by default while Faz B (media) and Faz C (MLS)
 * are built; flip on to route authority calls through SFU once they land. Citizen P2P calls are never
 * affected by this flag.
 */
object SfuCallConfig {
    /**
     * When true, authority calls place/receive `roomId`-based SFU invites instead of P2P SDP offers.
     *
     * ⚠️ UNTESTED / DEV-ONLY while Faz B is being brought up. Turning this on:
     *  - REPLACES the working P2P authority-call path — a bug here breaks authority calls entirely.
     *  - Only enables **Android↔Android** audio (both plaintext). Android↔web audio still won't work
     *    until Faz C (MLS-E2EE) lands, because the web enforces MLS and can't decrypt our plain frames.
     * MUST stay `false` in any release until Faz C is done AND this has been device-tested.
     */
    const val ENABLED = true
}
