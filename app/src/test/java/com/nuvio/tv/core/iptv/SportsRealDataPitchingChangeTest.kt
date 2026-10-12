package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataPitchingChangeTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012g.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-12T02:06:40Z")

    private fun until(root: JSONObject, text: String, extra: Int = 0): JSONObject {
        val plays = root.getJSONArray("plays")
        val end = (0 until plays.length()).first { plays.getJSONObject(it).optString("text") == text } + extra
        return JSONObject(root.toString()).put("plays", JSONArray((0..end).map { plays.getJSONObject(it) }))
    }

    @Test fun baseballReliefBetweenInningsNamesTheNewPitcher() {
        val game = EspnScoreboard.parse(sample("espn-mlb-scoreboard"), league("mlb"), now).single()
        assertEquals("Top 6th", game.detail)
        assertEquals(SportsDetail.Baseball(6, InningHalf.TOP, 0, 0, 1, false, false, false), game.sportDetail)
        assertNull(game.situation)
        assertEquals(SportsBugText("LAD 1–2 MIL", "▲6", "0 outs · 0–1"), SportsFixtureText.bug(game))
        assertEquals(FixtureLine("2", listOf("0", "0", "0", "1", "1")), game.homeLine)
        assertEquals(FixtureLine("1", listOf("0", "1", "0", "0", "0", "0")), game.awayLine)
        val summary = EspnSummary.parse(JSONObject(sample("espn-mlb-summary")), "baseball")
        assertEquals(SummaryCount(0, 1, 0, false, false, false, 6, false, "Abner Uribe", "Will Smith"), summary.count)
        assertEquals(listOf("Abner Uribe pitches to Will Smith", "Uribe relieved Hall", "Top of the 6th inning"), summary.lastPlays.map { it.text })
        assertEquals(SummaryCount(0, 1, 0, false, false, false, 6, false, "Abner Uribe", "Will Smith"), SportsCounts.live(game, summary.count))
        assertEquals(listOf(Triple(FixtureSide.AWAY, 2, 0 to 1), Triple(FixtureSide.HOME, 4, 1 to 1), Triple(FixtureSide.HOME, 5, 2 to 1)),
            summary.moments.map { Triple(it.side, it.period, it.homeScore to it.awayScore) })
        assertTrue(summary.moments.all { it.kind == MomentKind.RUN && it.wallclockMillis != null })
        assertEquals(millis("2026-10-12T01:53:01Z"), summary.moments.last().wallclockMillis)
    }

    @Test fun baseballReliefDuringAnAtBat() {
        val root = JSONObject(sample("espn-mlb-summary"))
        assertEquals(listOf("DL Hall pitches to Freddie Freeman", "Hall relieved Misiorowski", "Betts walked."),
            EspnSummary.parse(until(root, "DL Hall pitches to Freddie Freeman", 1), "baseball").lastPlays.map { it.text })
        assertEquals(listOf("Freeman grounded into double play, second to shortstop to first, Betts out at second.", "Hall relieved Misiorowski", "Betts walked."),
            EspnSummary.parse(until(root, "Freeman grounded into double play, second to shortstop to first, Betts out at second."), "baseball").lastPlays.map { it.text })
    }

    @Test fun nflSecondHalfStartsWithEveryTimeoutBack() {
        val game = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).single()
        assertEquals(FixtureSituation("1st & 10 · ATL 47", FixtureSide.HOME, "M.Penix pass short right to K.Pitts to ATL 47 for 13 yards (K.Hamilton) [N.Madubuike].",
            77, false, 3, 3, "3 plays, 12 yards, 1:37"), game.situation)
        assertEquals(SportsBugText("BAL 9–14 ATL", "Q3 · 12:39", "1st & 10 · ATL 47"), SportsFixtureText.bug(game))
        assertEquals(listOf("7", "7", "0"), game.homeLine!!.periods)
        assertEquals(listOf("3", "6", "0"), game.awayLine!!.periods)
        assertEquals(3, SportsFixtureText.timeoutSlots(game, 3))
        val summary = EspnSummary.parse(JSONObject(sample("espn-nfl-summary")), "american-football")
        assertEquals(3 to "13:23", summary.period to summary.clock)
        val kick = summary.moments.last()
        assertEquals(listOf(MomentKind.POINTS, FixtureSide.AWAY, 2, "0:00", 14, 9), listOf(kick.kind, kick.side, kick.period, kick.clock, kick.homeScore, kick.awayScore))
        assertEquals(SportsMarker(millis("2026-10-12T01:47:26Z"), true), SportsMarkers.estimate(kick, summary.sport, game.startMillis))
        assertEquals(SummaryPlay("M.Penix pass short right to K.Pitts to ATL 47 for 13 yards (K.Hamilton) [N.Madubuike].", 3, "13:23"), summary.lastPlays.first())
        assertEquals(listOf("M.Penix sacked at ATL 34 for -1 yards (T.Simpson).", "PENALTY on ATL-J.Matthews, False Start, 5 yards, enforced at ATL 40 - No Play."),
            summary.lastPlays.drop(1).map { it.text })
    }

    @Test fun livescoreExtraTimeAndHockeyBreak() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(11, scores.size)
        fun merged(id: String, sport: String, start: String, league: String) = SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away",
            FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, now).single()
        val extra = merged("2608531", "soccer", "2026-10-12T00:00:00Z", "epl")
        assertEquals(FixtureStatus.LIVE, extra.status)
        assertEquals(3 to "94'", extra.period to extra.clock)
        assertEquals("ET · 94'", SportsFixtureText.periodClock(extra))
        assertEquals(SportsBugText("Home 3–3 Away", "94'"), SportsFixtureText.bug(extra))
        val brk = merged("2598764", "ice-hockey", "2026-10-12T00:00:00Z", "nhl")
        assertEquals(FixtureStatus.LIVE, brk.status)
        assertEquals("2–2", brk.score)
        assertNull(brk.clock)
        assertEquals("BT", SportsFixtureText.periodClock(brk))
        assertEquals("HT", SportsFixtureText.bug(merged("2497283", "soccer", "2026-10-12T01:10:00Z", "epl")).state)
        assertEquals("1H · 45+5'", SportsFixtureText.periodClock(merged("2487550", "soccer", "2026-10-12T01:15:00Z", "epl")))
        assertEquals("2H · 76'", SportsFixtureText.periodClock(merged("2398128", "soccer", "2026-10-12T00:30:00Z", "epl")))
        val night = merged("2475451", "american-football", "2026-10-12T00:20:00Z", "nfl")
        assertEquals("Q3 · 13:23", SportsFixtureText.periodClock(night))
        assertEquals("14–9", night.score)
        val nuggets = merged("2601640", "basketball", "2026-10-12T01:00:00Z", "nba")
        assertEquals("Q2 · 6'", SportsFixtureText.periodClock(nuggets))
        assertEquals("39–52", nuggets.score)
        assertEquals("Q4 · 7'", SportsFixtureText.periodClock(merged("2616750", "basketball", "2026-10-12T00:00:00Z", "nba")))
        assertEquals(FixtureStatus.FINAL, merged("2616749", "basketball", "2026-10-12T00:00:00Z", "nba").status)
        assertEquals(FixtureStatus.SCHEDULED, merged("2616724", "baseball", "2026-10-12T21:00:00Z", "mlb").status)
        val tie = merged("2475446", "american-football", "2026-10-11T17:00:00Z", "nfl")
        assertEquals(FixtureStatus.FINAL, tie.status)
        assertEquals("27–27", tie.score)
    }
}
