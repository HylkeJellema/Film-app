package com.kickercam.detect

import android.os.SystemClock

/**
 * Turns a stream of per-frame verdicts into clip triggers.
 *
 * Requires a run of consecutive hits so a single noisy frame cannot fire, then holds a cooldown so
 * one pass over the kicker produces one clip instead of a dozen.
 */
class TriggerController {

    private var consecutiveHits = 0
    private var lastTriggerAtMs = 0L
    private var armedAtMs = 0L
    private var armed = false

    var minConsecutiveHits: Int = 2
    var cooldownMs: Long = 3_000L
    var armDelayMs: Long = 5_000L

    val isArmed: Boolean get() = armed

    /** Milliseconds still to wait before the trigger goes live, 0 once armed. */
    fun armDelayRemainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        if (!armed) 0L else (armedAtMs + armDelayMs - nowMs).coerceAtLeast(0L)

    fun cooldownRemainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        if (lastTriggerAtMs == 0L) 0L else (lastTriggerAtMs + cooldownMs - nowMs).coerceAtLeast(0L)

    fun arm(nowMs: Long = SystemClock.elapsedRealtime()) {
        armed = true
        armedAtMs = nowMs
        consecutiveHits = 0
        lastTriggerAtMs = 0L
    }

    fun disarm() {
        armed = false
        consecutiveHits = 0
    }

    /** Returns true when this frame should start (or extend) a clip. */
    fun submit(hit: Boolean, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!armed) return false

        if (!hit) {
            consecutiveHits = 0
            return false
        }

        consecutiveHits++
        if (consecutiveHits < minConsecutiveHits) return false
        if (armDelayRemainingMs(nowMs) > 0L) return false
        if (cooldownRemainingMs(nowMs) > 0L) return false

        lastTriggerAtMs = nowMs
        consecutiveHits = 0
        return true
    }
}
