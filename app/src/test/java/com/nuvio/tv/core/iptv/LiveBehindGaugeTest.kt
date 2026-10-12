package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LiveBehindGaugeTest {
    private val segment = 6_000L

    private fun saw(second: Long, base: Long = 0L, paused: Long = 0L): Long = base + paused + segment - (second * 1_000 % segment)

    @Test fun playlistSawtoothStaysSteadyAndHidden() {
        val gauge = LiveBehindGauge()
        val readings = (0L until 120L).map { second -> gauge.playlist(second * 1_000, saw(second), segment)!! }
        assertFalse(gauge.shown)
        assertTrue(readings.drop(10).all { it <= 1_000 })
        val raised = LiveBehindGauge()
        (0L until 120L).forEach { second -> raised.playlist(second * 1_000, saw(second, base = 2_500), segment); assertFalse(raised.shown) }
        assertEquals(3_500L, raised.millis)
    }

    @Test fun firstReadingAtASawPeakDoesNotFlashTheHint() {
        val gauge = LiveBehindGauge()
        val shown = (0L until 30L).map { second -> gauge.playlist(second * 1_000, saw(second), segment); gauge.shown }
        assertEquals(6_000L, saw(0))
        assertTrue(shown.none { it })
    }

    @Test fun fallingBehindAfterAPauseShowsAndStaysShown() {
        val gauge = LiveBehindGauge()
        for (second in 0L until 30L) gauge.playlist(second * 1_000, saw(second), segment)
        for (second in 30L until 60L) gauge.playlist(second * 1_000, saw(second, paused = (second - 30) * 1_000), segment)
        assertTrue(gauge.shown)
        val after = (60L until 120L).map { second -> gauge.playlist(second * 1_000, saw(second, paused = 30_000), segment); gauge.shown to gauge.millis!! }
        assertTrue(after.all { it.first })
        assertTrue(after.drop(10).all { it.second in 24_000L..31_000L })
    }

    @Test fun returningToLiveHidesAtOnce() {
        val gauge = LiveBehindGauge()
        for (second in 0L until 60L) gauge.playlist(second * 1_000, saw(second, base = 30_000), segment)
        assertTrue(gauge.shown)
        gauge.playlist(60_000, 400, segment)
        assertFalse(gauge.shown)
        assertEquals(400L, gauge.millis)
        gauge.reset()
        assertNull(gauge.millis)
        assertTrue((61L until 120L).none { second -> gauge.playlist(second * 1_000, saw(second), segment); gauge.shown })
    }

    @Test fun hysteresisShowsAtFiveAndHidesBelowThree() {
        val gauge = LiveBehindGauge()
        gauge.steady(0, 4_900); assertFalse(gauge.shown)
        gauge.steady(1_000, 5_000); assertTrue(gauge.shown)
        gauge.steady(2_000, 3_000); assertTrue(gauge.shown)
        gauge.steady(3_000, 2_999); assertFalse(gauge.shown)
        gauge.steady(4_000, 4_500); assertFalse(gauge.shown)
        gauge.steady(5_000, 5_200); assertTrue(gauge.shown)
        gauge.steady(6_000, null); assertFalse(gauge.shown)
        assertNull(gauge.millis)
    }

    @Test fun transportStreamReadingPassesThroughUnchanged() {
        val gauge = LiveBehindGauge()
        val clock = LiveBehindClock()
        clock.ready(10_000, 0)
        assertEquals(0L, gauge.steady(10_000, clock.behindMs(10_000, 0)))
        assertEquals(7_000L, gauge.steady(20_000, clock.behindMs(20_000, 3_000)))
        assertTrue(gauge.shown)
        assertEquals(0L, gauge.steady(21_000, -50))
    }

    @Test fun programDateTimeMeasuresFromTheJoinAndIgnoresTargetJitter() {
        val gauge = LiveBehindGauge()
        assertNull(gauge.clock(0, 23_500, 18_000, ready = false))
        assertEquals(0L, gauge.clock(1_000, 23_500, 18_000, ready = true))
        val jitter = longArrayOf(0, 2_500, 1_000, 3_000, 500)
        for (second in 2L until 60L) {
            assertEquals(0L, gauge.clock(second * 1_000, 23_500, 18_000 + jitter[(second % jitter.size).toInt()], ready = true))
            assertFalse(gauge.shown)
        }
        for (second in 60L until 100L) gauge.clock(second * 1_000, 23_500 + (second - 59) * 1_000, 19_000, ready = true)
        assertEquals(40_000L, gauge.millis)
        assertTrue(gauge.shown)
        gauge.clock(100_000, 22_000, 18_000, ready = true)
        assertFalse(gauge.shown)
        assertEquals(0L, gauge.millis)
    }

    @Test fun programDateTimeAheadOfTheTargetCountsFromTheTarget() {
        val gauge = LiveBehindGauge()
        gauge.clock(0, 12_000, 18_000, ready = true)
        assertEquals(0L, gauge.clock(10_000, 18_000, 18_000, ready = true))
        assertFalse(gauge.shown)
        assertEquals(6_000L, gauge.clock(20_000, 24_000, 18_000, ready = true))
        assertTrue(gauge.shown)
    }

    @Test fun switchingToTheWallClockStartsAFreshReading() {
        val gauge = LiveBehindGauge()
        for (second in 0L until 20L) gauge.playlist(second * 1_000, saw(second, base = 20_000), segment)
        assertTrue(gauge.shown)
        assertEquals(0L, gauge.clock(20_000, 40_000, 18_000, ready = true))
        assertFalse(gauge.shown)
    }

    @Test fun windowFollowsTheSegmentLength() {
        assertEquals(9_000L, LiveBehindGauge.span(null))
        assertEquals(3_000L, LiveBehindGauge.span(1_000))
        assertEquals(15_000L, LiveBehindGauge.span(10_000))
        assertEquals(15_000L, LiveBehindGauge.span(60_000))
        assertEquals(9_000L, LiveBehindGauge.span(0))
    }

    @Test fun frequentReadingsShareASlot() {
        val gauge = LiveBehindGauge()
        for (step in 0L until 400L) gauge.playlist(step * 100, 8_000, segment)
        assertEquals(8_000L, gauge.millis)
        gauge.playlist(40_000, 9_000, segment)
        assertEquals(8_000L, gauge.millis)
        assertEquals(9_000L, gauge.playlist(60_000, 9_000, segment))
    }
}
