package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataOvertimeTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012b.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T20:42:40Z")

    @Test fun nflOvertimeEndOfQuarterAndRedZone() {
        val games = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).associateBy { it.id }
        assertEquals(5, games.size)
        val overtime = games.getValue("401872988")
        assertEquals(FixtureStatus.LIVE, overtime.status)
        assertEquals(5, overtime.period)
        assertEquals("OT · 1:50", SportsFixtureText.periodClock(overtime))
        assertEquals(SportsBugText("NYG 27–27 WSH", "OT · 1:50", "2nd & 4 · WSH 45"), SportsFixtureText.bug(overtime))
        assertEquals(listOf("6", "6", "7", "8", "0"), overtime.homeLine!!.periods)
        assertEquals(listOf("3", "0", "10", "14", "0"), overtime.awayLine!!.periods)
        assertEquals("OT", SportsFixtureText.periodLabel(overtime, 5))
        assertEquals(FixtureSituation("2nd & 4 · WSH 45", FixtureSide.HOME, "J.Daniels pass short right to A.Williams to WAS 45 for 6 yards (A.Phillips).", 64, false,
            0, 0, "2 plays, 22 yards, 0:05"), overtime.situation)
        assertEquals(2, SportsFixtureText.timeoutSlots(overtime, 0))
        assertEquals(1, SportsFixtureText.timeoutSlots(overtime.copy(league = "college-football"), 1))
        assertEquals(3, SportsFixtureText.timeoutSlots(overtime, 3))
        val quarter = games.getValue("401872989")
        assertEquals(FixtureStatus.LIVE, quarter.status)
        assertEquals(SportsBugText("DEN 7–0 LAC", "End of 1st", "4th & 17 · LAC 41"), SportsFixtureText.bug(quarter))
        assertEquals("END QUARTER 1", quarter.situation!!.lastPlay)
        assertEquals(3, SportsFixtureText.timeoutSlots(quarter, 3))
        val afterScore = games.getValue("401872991").situation!!
        assertEquals(FixtureSituation(null, null, "Official Timeout at 05:06.", 49, false, 2, 3, null), afterScore)
        assertEquals(SportsBugText("DET 0–7 ARI", "Q1 · 5:06", null), SportsFixtureText.bug(games.getValue("401872991")))
        val redZone = games.getValue("401872992").situation!!
        assertTrue(redZone.redZone)
        assertEquals("2nd & 5 · SF 8", redZone.downDistance)
        assertEquals(FixtureSide.HOME, redZone.possession)
        assertEquals("3 plays, 16 yards, 2:05", redZone.drive)
        assertTrue(redZone.lastPlay!!.startsWith("S.Darnold pass short middle"))
        assertTrue(redZone.lastPlay!!.endsWith("enforced at SF 12 - No Play."))
        assertEquals(FixtureStatus.FINAL, games.getValue("401872990").status)
        val entry = SportsCacheEntry(games.values.toList(), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun nflSummaryInOvertime() {
        val summary = EspnSummary.parse(JSONObject(sample("espn-nfl-summary")), "american-football")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals(5, summary.period)
        assertEquals("1:50", summary.clock)
        assertEquals("1:50 - OT", summary.detail)
        assertEquals(listOf("6", "6", "7", "8", "0"), summary.homeLine!!.periods)
        assertEquals(listOf("3", "0", "10", "14", "0"), summary.awayLine!!.periods)
        assertEquals(10, summary.moments.size)
        assertEquals(27 to 27, summary.moments.last().homeScore to summary.moments.last().awayScore)
        assertEquals(4, summary.moments.last().period)
        val markers = SportsMarkers.all(summary, millis("2026-10-11T17:00:00Z"))
        assertEquals(10, markers.size)
        assertTrue(markers.all { it.second.exact })
        val play = summary.lastPlays.first()
        assertEquals(SummaryPlay("J.Daniels pass short left to T.McLaurin ran ob at WAS 39 for 16 yards.", 5, "1:55"), play)
        assertTrue(summary.lastPlays.drop(1).all { it.period == 5 })
        assertEquals(.6373f, summary.winProbability.last(), .0001f)
    }

    @Test fun livescoreNflOvertimeOutlastsTheGameLength() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(4, scores.size)
        val base = SportsFixture("2475446", "nfl", "american-football", "Washington Commanders v New York Giants", FixtureTeam("Washington Commanders"),
            FixtureTeam("New York Giants"), millis("2026-10-11T17:00:00Z"), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)
        fun merged(score: SportsDbLiveScore, fixture: SportsFixture = base, at: Long = now) = SportsDbLive.merge(listOf(fixture), mapOf(fixture.id to score), at).single()
        val overtime = merged(scores.getValue("2475446"))
        assertEquals(FixtureStatus.LIVE, overtime.status)
        assertEquals(5, overtime.period)
        assertEquals("OT · 1:55", SportsFixtureText.periodClock(overtime))
        assertEquals("27–27", overtime.score)
        assertEquals(FixtureStatus.LIVE, merged(scores.getValue("2475446").copy(progress = "End of 4th")).status)
        assertEquals(base, merged(scores.getValue("2475446").copy(progress = null)))
        assertEquals(base, merged(scores.getValue("2475446").copy(updatedMillis = now - SportsDbLive.STUCK_MILLIS - 60_000)))
        val quarter = merged(scores.getValue("2475447"), base.copy(id = "2475447", startMillis = millis("2026-10-11T20:05:00Z")))
        assertEquals("End of 1st", SportsFixtureText.periodClock(quarter))
        val first = merged(scores.getValue("2475449"), base.copy(id = "2475449", startMillis = millis("2026-10-11T20:25:00Z")))
        assertEquals("Q1 · 5:06", SportsFixtureText.periodClock(first))
        assertEquals(listOf("american-football"), SportsDbLive.wanted(listOf(base), now))
        assertEquals(emptyList<String>(), SportsDbLive.wanted(listOf(base), millis("2026-10-11T21:31:00Z")))
        assertEquals(emptyList<String>(), SportsDbLive.wanted(listOf(base.copy(status = FixtureStatus.FINAL)), now))
        val final = merged(scores.getValue("2475439"), base.copy(id = "2475439", startMillis = millis("2026-10-11T13:30:00Z")))
        assertEquals(FixtureStatus.FINAL, final.status)
        assertEquals("19–17", final.score)
    }

    @Test fun secondYellowArrivesAsAYellowThenARed() {
        val game = EspnScoreboard.parse(sample("espn-epl-scoreboard"), league("epl"), now).single()
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals("0–3", game.score)
        val cards = game.events.filter { it.player == "R. Gravenberch" }
        assertEquals(listOf(FixtureEvent(FixtureEventKind.YELLOW, FixtureSide.HOME, "47'", null, "R. Gravenberch"),
            FixtureEvent(FixtureEventKind.RED, FixtureSide.HOME, "62'", null, "R. Gravenberch")), cards)
        assertEquals(1, SportsEvents.reds(game, FixtureSide.HOME))
        assertEquals(0, SportsEvents.reds(game, FixtureSide.AWAY))
        assertEquals(3, game.events.count { it.kind == FixtureEventKind.GOAL && it.side == FixtureSide.AWAY })
    }
}
