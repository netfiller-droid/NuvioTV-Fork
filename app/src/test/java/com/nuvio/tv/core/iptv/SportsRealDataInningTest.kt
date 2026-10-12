package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataInningTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012h.json")!!.bufferedReader().use { it.readText() }
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-12T11:03:00Z")
    private val scores by lazy { SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId } }

    private fun merged(id: String, sport: String, start: String, league: String = "mlb") = SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away",
        FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, now).single()

    @Test fun livescoreBaseballInningReadsAsTheInning() {
        assertEquals(11, scores.size)
        val kbo = merged("2400494", "baseball", "2026-10-12T09:30:00Z")
        assertEquals(FixtureStatus.LIVE, kbo.status)
        assertEquals("3–5", kbo.score)
        assertEquals(4 to null, kbo.period to kbo.clock)
        assertEquals("Inning 4", kbo.detail)
        assertEquals("Inning 4", SportsFixtureText.periodClock(kbo))
        assertEquals(SportsBugText("Away 5–3 Home", "Inning 4"), SportsFixtureText.bug(kbo))
        assertEquals("Inning 5", SportsFixtureText.periodClock(merged("2603517", "baseball", "2026-10-12T09:30:00Z")))
        val noScore = merged("2617436", "baseball", "2026-10-12T10:35:00Z")
        assertEquals(FixtureStatus.LIVE, noScore.status)
        assertNull(noScore.score)
        assertEquals(SportsBugText("Away @ Home", "Inning 2"), SportsFixtureText.bug(noScore))
        assertEquals(FixtureStatus.SCHEDULED, merged("2616724", "baseball", "2026-10-12T21:00:00Z").status)
        val hockey = merged("2506702", "ice-hockey", "2026-10-12T09:00:00Z", "nhl")
        assertEquals("P3 · 19'", SportsFixtureText.periodClock(hockey))
        assertEquals("HT", SportsFixtureText.periodClock(merged("2583447", "basketball", "2026-10-12T10:00:00Z", "nba")))
        assertEquals("HT", SportsFixtureText.bug(merged("2617442", "soccer", "2026-10-12T10:00:00Z", "epl")).state)
        assertEquals("1H · 10'", SportsFixtureText.periodClock(merged("2503971", "soccer", "2026-10-12T10:00:00Z", "epl")))
        assertEquals(FixtureStatus.SCHEDULED, merged("2518194", "ice-hockey", "2026-10-11T14:30:00Z", "nhl").status)
        val night = merged("2475451", "american-football", "2026-10-12T00:20:00Z", "nfl")
        assertEquals(FixtureStatus.FINAL, night.status)
        assertEquals("23–30", night.score)
        assertEquals("Final/OT", night.detail)
    }

    @Test fun eventsDayBaseballInning() {
        val league = SportsDbLeagues.league(SportsDbLeague("4830", "Korean KBO League", "Baseball"))
        val rows = JSONObject(sample("tsdb-livescore-all")).getJSONArray("livescore")
        val games = SportsDbEvents.parse(JSONObject().put("events", rows).toString(), league, now).associateBy { it.id }
        assertEquals(setOf("2400494", "2400530", "2603517"), games.keys)
        val game = games.getValue("2400530")
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(5 to null, game.period to game.clock)
        assertEquals(SportsBugText("KT Wiz 3–1 Samsung Lions", "Inning 5"), SportsFixtureText.bug(game))
        val hockey = SportsDbEvents.parse(JSONObject().put("events", rows).toString(), SportsDbLeagues.league(SportsDbLeague("4920", "Russian KHL", "Ice Hockey")), now).single()
        assertEquals(3 to "19'", hockey.period to hockey.clock)
        assertEquals("P3", hockey.detail)
    }

    @Test fun nflOvertimeWinOnTheScoreboard() {
        val game = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), SportsLeagues.byId("nfl")!!, now).single()
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals("23–30", game.score)
        assertNull(game.period)
        assertNull(game.situation)
        assertEquals(SportsBugText("BAL 30–23 ATL", "Final/OT", null), SportsFixtureText.bug(game))
        assertEquals(listOf("7", "7", "3", "6", "0"), game.homeLine!!.periods)
        assertEquals(listOf("3", "6", "6", "8", "7"), game.awayLine!!.periods)
        assertEquals("OT", SportsFixtureText.periodLabel(game, 5))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS, listOf(4, 1)), SportsRecords.parse(game.sport, game.away!!.record))
        val entry = SportsCacheEntry(listOf(game), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }
}
