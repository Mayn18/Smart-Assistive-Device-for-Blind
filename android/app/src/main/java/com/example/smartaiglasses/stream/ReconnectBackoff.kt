package com.example.smartaiglasses.stream

/**
 * Exponential backoff for re-opening the button-event connection: 1 s, 2 s, 4 s, 8 s, then
 * capped. Short first delays make a Wi-Fi blip recover almost unnoticed; the cap stops a
 * powered-off device from being hammered (and the phone battery from being drained).
 *
 * Pure Kotlin with no Android or OkHttp dependency so it can be unit-tested on the JVM.
 * Not thread-safe; callers synchronise.
 */
class ReconnectBackoff(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 8_000L
) {
    init {
        require(initialDelayMs > 0) { "initialDelayMs must be positive" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
    }

    private var nextDelayMs = initialDelayMs

    /** Delay to wait before the next attempt; each call doubles the following one up to the cap. */
    fun nextDelayMs(): Long {
        val current = nextDelayMs
        nextDelayMs = if (current > maxDelayMs / 2) maxDelayMs else current * 2
        return current
    }

    /** Call after a connection succeeds so the next outage starts from the short delay again. */
    fun reset() {
        nextDelayMs = initialDelayMs
    }
}
