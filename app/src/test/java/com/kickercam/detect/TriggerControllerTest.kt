package com.kickercam.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerControllerTest {

    private fun controller(
        hits: Int = 2,
        cooldownMs: Long = 3_000,
        armDelayMs: Long = 0,
    ) = TriggerController().apply {
        minConsecutiveHits = hits
        this.cooldownMs = cooldownMs
        this.armDelayMs = armDelayMs
    }

    @Test
    fun `never fires while disarmed`() {
        val c = controller()
        repeat(10) { assertFalse(c.submit(hit = true, nowMs = it * 100L)) }
    }

    @Test
    fun `needs the configured run of consecutive hits`() {
        val c = controller(hits = 3)
        c.arm(nowMs = 0)

        assertFalse(c.submit(hit = true, nowMs = 10))
        assertFalse(c.submit(hit = true, nowMs = 20))
        assertTrue(c.submit(hit = true, nowMs = 30))
    }

    @Test
    fun `a miss breaks the run`() {
        val c = controller(hits = 3)
        c.arm(nowMs = 0)

        c.submit(hit = true, nowMs = 10)
        c.submit(hit = true, nowMs = 20)
        assertFalse(c.submit(hit = false, nowMs = 30))
        assertFalse(c.submit(hit = true, nowMs = 40))
        assertFalse(c.submit(hit = true, nowMs = 50))
        assertTrue(c.submit(hit = true, nowMs = 60))
    }

    @Test
    fun `holds the cooldown so one pass makes one clip`() {
        val c = controller(hits = 1, cooldownMs = 3_000)
        c.arm(nowMs = 0)

        assertTrue(c.submit(hit = true, nowMs = 1_000))
        assertFalse(c.submit(hit = true, nowMs = 1_100))
        assertFalse(c.submit(hit = true, nowMs = 3_999))
        assertTrue(c.submit(hit = true, nowMs = 4_000))
    }

    @Test
    fun `arming delay keeps the trigger quiet while you walk out of frame`() {
        val c = controller(hits = 1, cooldownMs = 0, armDelayMs = 5_000)
        c.arm(nowMs = 1_000)

        assertEquals(5_000L, c.armDelayRemainingMs(nowMs = 1_000))
        assertFalse(c.submit(hit = true, nowMs = 2_000))
        assertEquals(4_000L, c.armDelayRemainingMs(nowMs = 2_000))
        assertFalse(c.submit(hit = true, nowMs = 5_999))
        assertTrue(c.submit(hit = true, nowMs = 6_000))
        assertEquals(0L, c.armDelayRemainingMs(nowMs = 6_000))
    }

    @Test
    fun `re-arming clears the cooldown from the previous session`() {
        val c = controller(hits = 1, cooldownMs = 10_000)
        c.arm(nowMs = 0)
        assertTrue(c.submit(hit = true, nowMs = 100))
        assertFalse(c.submit(hit = true, nowMs = 200))

        c.disarm()
        c.arm(nowMs = 300)
        assertEquals(0L, c.cooldownRemainingMs(nowMs = 300))
        assertTrue(c.submit(hit = true, nowMs = 400))
    }

    @Test
    fun `disarm stops firing immediately`() {
        val c = controller(hits = 1, cooldownMs = 0)
        c.arm(nowMs = 0)
        assertTrue(c.submit(hit = true, nowMs = 10))
        c.disarm()
        assertFalse(c.isArmed)
        assertFalse(c.submit(hit = true, nowMs = 20))
    }
}
