package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsDbStuckRowsTest {
    private val minute = 60_000L
    private val start = Instant.parse("2026-10-11T14:30:00Z").toEpochMilli()
    private val nhl = SportsLeagues.byId("nhl")!!
    private val nfl = SportsLeagues.byId("nfl")!!

    private fun row(status: String, score: Boolean = true, league: SportsLeague = nhl): String {
        val scores = if (score) ",\"intHomeScore\":\"3\",\"intAwayScore\":\"2\"" else ""
        return """{"events":[{"idEvent":"2290001","idLeague":"${league.sportsDbId}","strHomeTeam":"Eisbaren Berlin","strAwayTeam":"Adler Mannheim",
            "strTimestamp":"2026-10-11T14:30:00","strStatus":"$status","strProgress":"20"$scores}]}"""
    }

    private fun parse(json: String, at: Long, league: SportsLeague = nhl) = SportsDbEvents.parse(json, league, at).single()

    @Test fun anInPlayRowLongPastItsGameIsNoLongerLive() {
        assertEquals(FixtureStatus.LIVE, parse(row("P3"), start + 316 * minute).status)
        val stuck = parse(row("P3"), start + 10 * 60 * minute)
        assertEquals(FixtureStatus.FINAL, stuck.status)
        assertEquals("3–2", stuck.score)
        assertNull(stuck.detail)
        assertNull(stuck.clock)
        assertFalse(SportsRefresh.active(listOf(stuck), start + 10 * 60 * minute))
        assertEquals(FixtureStatus.SCHEDULED, parse(row("P3", score = false), start + 10 * 60 * minute).status)
        assertEquals(FixtureStatus.FINAL, parse(row("FT"), start + 10 * 60 * minute).status)
    }

    @Test fun nflOvertimeStillCountsAsLive() {
        val overtime = parse(row("OT", league = nfl), start + 222 * minute, nfl)
        assertEquals(FixtureStatus.LIVE, overtime.status)
        val score = SportsDbLiveScore("2290001", "", "1:55 - OT", "27", "27", start + 222 * minute)
        val merged = SportsDbLive.merge(listOf(overtime), mapOf("2290001" to score), start + 222 * minute).single()
        assertEquals(FixtureStatus.LIVE, merged.status)
        assertEquals("OT · 1:55", SportsFixtureText.periodClock(merged))
    }

    @Test fun aLiveRowWhoseLiveScoreStoppedIsSettled() {
        val now = start + 316 * minute
        val base = parse(row("P3"), now)
        val stale = SportsDbLiveScore("2290001", "P3", "20", "3", "2", start + 100 * minute)
        val settled = SportsDbLive.merge(listOf(base), mapOf("2290001" to stale), now).single()
        assertEquals(FixtureStatus.FINAL, settled.status)
        assertEquals("3–2", settled.score)
        assertNull(settled.period)
        val early = parse(row("P1"), start + 30 * minute)
        assertEquals(early, SportsDbLive.merge(listOf(early), mapOf("2290001" to stale.copy(updatedMillis = start - 4 * 60 * minute)), start + 30 * minute).single())
        val fresh = SportsDbLive.merge(listOf(base), mapOf("2290001" to stale.copy(progress = "18", updatedMillis = now)), now).single()
        assertEquals(FixtureStatus.LIVE, fresh.status)
    }
}
