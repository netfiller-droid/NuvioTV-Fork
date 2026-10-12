package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataFreeThrowTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012f.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-12T01:43:31Z")

    private fun withPlays(root: JSONObject, count: Int): JSONObject {
        val plays = root.getJSONArray("plays")
        return JSONObject(root.toString()).put("plays", JSONArray((0 until count).map { plays.getJSONObject(it) }))
    }

    @Test fun nbaFreeThrowWithoutAPlayerReadsAsASentence() {
        val game = EspnScoreboard.parse(sample("espn-nba-scoreboard"), league("nba"), now).single()
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(FixtureSituation(lastPlay = "Misses free throw 2 of 2", homeWinPercent = 53), game.situation)
        assertEquals(SportsBugText("CHI 25–26 DEN", "Q1 · 55.4", null), SportsFixtureText.bug(game))
        assertEquals(listOf("26"), game.homeLine!!.periods)
        val entry = SportsCacheEntry(listOf(game), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
        val raw = JSONObject(sample("espn-nba-scoreboard"))
        raw.getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0).getJSONObject("situation").getJSONObject("lastPlay")
            .put("type", JSONObject().put("text", "Jumpball")).put("text", "vs. ")
        assertEquals("Jumpball", EspnScoreboard.parse(raw.toString(), league("nba"), now).single().situation!!.lastPlay)
    }

    @Test fun nbaSummaryPlaysWithoutAPlayer() {
        val root = JSONObject(sample("espn-nba-summary"))
        val summary = EspnSummary.parse(root, "basketball")
        assertEquals(1 to "55.4", summary.period to summary.clock)
        assertEquals(listOf("Isaac Okoro loose ball foul", "Nuggets offensive team rebound", "Tyus Jones misses 25-foot three point jumper"),
            summary.lastPlays.map { it.text })
        assertEquals(listOf("Jump Ball"), EspnSummary.parse(withPlays(root, 1), "basketball").lastPlays.map { it.text })
        assertEquals(listOf("Delay of game violation", "Tre Jones enters the game for Josh Giddey", "Nikola Jokic bad pass turnover"),
            EspnSummary.parse(withPlays(root, 4), "basketball").lastPlays.map { it.text })
        assertEquals(SummaryPlay("Delay of game violation", 1, "7:07"), EspnSummary.parse(withPlays(root, 4), "basketball").lastPlays.first())
    }

    @Test fun nflTimeoutCalledByTheDefenceKeepsTheDrive() {
        val game = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).single()
        assertEquals(FixtureSituation("2nd & 10 · ATL 26", FixtureSide.AWAY, "Timeout #1 by ATL at 00:12.", 74, false, 2, 2, "8 plays, 54 yards, 2:12"), game.situation)
        assertEquals(SportsBugText("BAL 6–14 ATL", "Q2 · 0:12", "2nd & 10 · ATL 26"), SportsFixtureText.bug(game))
        assertEquals(listOf("7", "7"), game.homeLine!!.periods)
        assertEquals(listOf("3", "3"), game.awayLine!!.periods)
    }

    @Test fun baseballNewAtBatIsTheLastPlay() {
        val game = EspnScoreboard.parse(sample("espn-mlb-scoreboard"), league("mlb"), now).single()
        assertEquals("Top 5th", game.detail)
        assertEquals(SportsDetail.Baseball(5, InningHalf.TOP, 1, 0, 0, true, false, false), game.sportDetail)
        assertEquals(FixtureSituation(lastPlay = "Jacob Misiorowski pitches to Freddie Freeman"), game.situation)
        assertEquals(SportsBugText("LAD 1–1 MIL", "▲5", "1 out · 0–0"), SportsFixtureText.bug(game))
        assertEquals(FixtureLine("1", listOf("0", "0", "0", "1")), game.homeLine)
        assertEquals(FixtureLine("1", listOf("0", "1", "0", "0", "0")), game.awayLine)
    }

    @Test fun livescoreLateSecondQuarterAndHockeyFinals() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(6, scores.size)
        fun merged(id: String, sport: String, start: String, league: String) = SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away",
            FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, now).single()
        val night = merged("2475451", "american-football", "2026-10-12T00:20:00Z", "nfl")
        assertEquals("Q2 · 0:12", SportsFixtureText.periodClock(night))
        assertEquals("14–6", night.score)
        val nuggets = merged("2601640", "basketball", "2026-10-12T01:00:00Z", "nba")
        assertEquals("Q1 · 11'", SportsFixtureText.periodClock(nuggets))
        assertEquals("25–25", nuggets.score)
        listOf("2591570", "2600768").forEach { id ->
            val final = merged(id, "ice-hockey", "2026-10-11T23:00:00Z", "nhl")
            assertEquals(FixtureStatus.FINAL, final.status)
            assertEquals("1–4", final.score)
            assertNull(final.clock)
        }
        assertEquals("P2 · 16'", SportsFixtureText.periodClock(merged("2598764", "ice-hockey", "2026-10-12T00:00:00Z", "nhl")))
        assertEquals(FixtureStatus.SCHEDULED, merged("2518194", "ice-hockey", "2026-10-11T14:30:00Z", "nhl").status)
    }
}
