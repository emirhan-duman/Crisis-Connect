package com.auralis.crisisconnect.domain.offline

/**
 * Abstraction for providing style urls with graceful fallback semantics.
 */
interface StyleUrlProvider {
    /** Returns the preferred online style url. */
    fun primary(): String

    /** Returns a local fallback style url in case the primary fails. */
    fun fallback(): String

    /**
     * Returns the primary style if available, otherwise falls back to a bundled one.
     */
    fun primaryOrFallback(): String = primary().ifBlank { fallback() }
}
