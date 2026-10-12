package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGoLiveTest {
    private val now = 1_000_000L

    private fun route(catchup: Boolean = false, fromLive: Boolean = false, stop: Long? = null, local: Boolean = false, localBehind: Boolean = false,
        paused: Boolean = false, behind: Boolean = false, cushion: Long = 0L) =
        LiveGoLive.route(catchup, fromLive, stop, now, local, localBehind, paused, behind, cushion)

    @Test fun liveAtTheEdgeOffersNothing() {
        assertEquals(GoLiveRoute.NONE, route())
        assertEquals(GoLiveRoute.NONE, route(behind = false, cushion = 20_000))
        assertEquals(GoLiveRoute.NONE, route(behind = true, cushion = 0))
        assertEquals(GoLiveRoute.NONE, route(local = true))
    }

    @Test fun safetyCushionBehindLiveSkipsAhead() {
        assertEquals(GoLiveRoute.CUSHION, route(behind = true, cushion = 20_000))
        assertEquals(GoLiveRoute.CUSHION, route(behind = true, cushion = 30_000))
    }

    @Test fun localTimeshiftBehindOrPausedReturnsToTheRingEdge() {
        assertEquals(GoLiveRoute.LOCAL, route(local = true, localBehind = true))
        assertEquals(GoLiveRoute.LOCAL, route(local = true, paused = true, behind = true, cushion = 20_000))
    }

    @Test fun catchupNearLiveRetunesTheChannel() {
        assertEquals(GoLiveRoute.RETUNE, route(catchup = true, fromLive = true, stop = now - 60_000))
        assertEquals(GoLiveRoute.RETUNE, route(catchup = true, stop = now + 60_000))
        assertEquals(GoLiveRoute.RETUNE, route(catchup = true))
        assertEquals(GoLiveRoute.NONE, route(catchup = true, stop = now - 1))
        assertEquals(GoLiveRoute.NONE, route(catchup = true, stop = now - 3_600_000, paused = true))
    }

    @Test fun hintAndPillFollowTheSameSteadyReading() {
        val gauge = LiveBehindGauge()
        val shown = (0L until 60L).map { second ->
            gauge.playlist(second * 1_000, 6_000 - second * 1_000 % 6_000, 6_000)
            route(behind = gauge.shown, cushion = 20_000)
        }
        assertTrue(shown.all { it == GoLiveRoute.NONE })
        for (second in 60L until 90L) gauge.playlist(second * 1_000, 40_000 - second * 1_000 % 6_000, 6_000)
        assertEquals(GoLiveRoute.CUSHION, route(behind = gauge.shown, cushion = 20_000))
    }

    @Test fun pausedLiveRetunes() {
        assertEquals(GoLiveRoute.RETUNE, route(paused = true))
        assertEquals(GoLiveRoute.RETUNE, route(paused = true, behind = false, cushion = 20_000))
    }
}
