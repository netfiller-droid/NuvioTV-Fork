package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataPlayoffTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012e.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-12T01:26:32Z")

    private fun withPlays(root: JSONObject, count: Int): JSONObject {
        val plays = root.getJSONArray("plays")
        return JSONObject(root.toString()).put("plays", JSONArray((0 until count).map { plays.getJSONObject(it) }))
    }

    @Test fun baseballFullCountLeavesThePitchOutOfTheLastPlay() {
        val game = EspnScoreboard.parse(sample("espn-mlb-scoreboard"), league("mlb"), now).single()
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("Top 4th", game.detail)
        assertEquals(SportsDetail.Baseball(4, InningHalf.TOP, 2, 3, 2, false, true, false), game.sportDetail)
        assertEquals(SportsBugText("LAD 1–0 MIL", "▲4", "2 outs · 3–2"), SportsFixtureText.bug(game))
        assertNull(game.situation)
        assertEquals(FixtureLine("0", listOf("0", "0", "0")), game.homeLine)
        assertEquals(FixtureLine("1", listOf("0", "1", "0", "0")), game.awayLine)
        val swing = JSONObject(sample("espn-mlb-scoreboard"))
        swing.getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0).getJSONObject("situation").getJSONObject("lastPlay")
            .put("summaryType", "N").put("text", "Tucker walked.")
        assertEquals(FixtureSituation(lastPlay = "Tucker walked."), EspnScoreboard.parse(swing.toString(), league("mlb"), now).single().situation)
        val entry = SportsCacheEntry(listOf(game), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun baseballBalkIsListedOnce() {
        val root = JSONObject(sample("espn-mlb-summary"))
        val summary = EspnSummary.parse(root, "baseball")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals("Top 4th", summary.detail)
        assertEquals(SummaryCount(2, 2, 2, false, true, false, 4, false, "Jacob Misiorowski", "Kyle Tucker"), summary.count)
        assertEquals(listOf("T. Hernández to second on a balk.", "Pages flied out to right.", "Muncy struck out looking."), summary.lastPlays.map { it.text })
        assertEquals(4, summary.lastPlays.first().period)
        val run = summary.moments.single()
        assertEquals(MomentKind.RUN, run.kind)
        assertEquals(FixtureSide.AWAY, run.side)
        assertEquals(0 to 1, run.homeScore to run.awayScore)
        assertEquals(false, run.bottom)
        assertEquals(millis("2026-10-12T00:40:06Z"), run.wallclockMillis)
        val plays = root.getJSONArray("plays")
        val balk = (0 until plays.length()).first { plays.getJSONObject(it).getJSONObject("type").optString("type") == "balk" }
        assertEquals(listOf("T. Hernández to second on a balk.", "Pages flied out to right.", "Muncy struck out looking."),
            EspnSummary.parse(withPlays(root, balk + 1), "baseball").lastPlays.map { it.text })
    }

    @Test fun nflDefensiveTouchdownAndSecondQuarter() {
        val game = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).single()
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("Q2 · 4:34", SportsFixtureText.periodClock(game))
        assertEquals(FixtureSituation("3rd & 6 · BAL 42", FixtureSide.HOME, "Br.Robinson left tackle to BLT 42 for no gain (N.Madubuike).", 65, false, 3, 3,
            "5 plays, 36 yards, 2:47"), game.situation)
        assertEquals(SportsBugText("BAL 6–7 ATL", "Q2 · 4:34", "3rd & 6 · BAL 42"), SportsFixtureText.bug(game))
        assertEquals(listOf("7", "0"), game.homeLine!!.periods)
        assertEquals(listOf("3", "3"), game.awayLine!!.periods)
        val summary = EspnSummary.parse(JSONObject(sample("espn-nfl-summary")), "american-football")
        assertEquals(2 to "4:34", summary.period to summary.clock)
        assertEquals(listOf(MomentKind.POINTS, MomentKind.TOUCHDOWN, MomentKind.POINTS), summary.moments.map { it.kind })
        assertEquals(listOf(FixtureSide.AWAY, FixtureSide.HOME, FixtureSide.AWAY), summary.moments.map { it.side })
        val defence = summary.moments[1]
        assertEquals("Jessie Bates III 36 Yd Fumble Return (Nick Folk Kick)", defence.text)
        assertEquals(7 to 3, defence.homeScore to defence.awayScore)
        assertEquals(millis("2026-10-12T00:40:57Z"), defence.wallclockMillis)
        assertEquals("Br.Robinson left tackle to BLT 42 for no gain (N.Madubuike).", summary.lastPlays.first().text)
        assertEquals("M.Penix pass deep right to D.London ran ob at BLT 46 for 25 yards (K.Hamilton).", summary.lastPlays.last().text)
    }

    @Test fun nhlPowerPlayEndsOnTime() {
        val game = EspnScoreboard.parse(sample("espn-nhl-scoreboard"), league("nhl"), now).single()
        assertEquals(SportsBugText("CAR 3–1 PHI", "P3 · 6:59", null), SportsFixtureText.bug(game))
        assertEquals(FixtureSituation(lastPlay = "Mark Jankowski faceoff won against Trevor Zegras"), game.situation)
        val root = JSONObject(sample("espn-nhl-summary"))
        val summary = EspnSummary.parse(root, "ice-hockey")
        assertEquals(3 to "6:59", summary.period to summary.clock)
        assertNull(summary.strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), EspnSummary.parse(withPlays(root, 5), "ice-hockey").strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), EspnSummary.parse(withPlays(root, 10), "ice-hockey").strength)
        assertNull(EspnSummary.parse(withPlays(root, 11), "ice-hockey").strength)
        assertEquals(1 to 3, summary.moments.last().homeScore to summary.moments.last().awayScore)
        assertTrue(SportsMarkers.all(summary, millis("2026-10-11T23:00:00Z")).all { it.second.exact })
        val woll = SummaryGoalie(FixtureSide.HOME, "Joseph Woll", 24, 27, ".889")
        val kochetkov = SummaryGoalie(FixtureSide.AWAY, "Pyotr Kochetkov", 17, 18, ".944")
        assertEquals(listOf(woll, kochetkov), summary.goalies.sortedBy { it.side })
        assertNull(SportsGoalies.emptyNet(summary.goalies))
    }

    @Test fun livescoreSundayNightAndLateHockey() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(12, scores.size)
        fun merged(id: String, sport: String, start: String, league: String) = SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away",
            FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, now).single()
        val night = merged("2475451", "american-football", "2026-10-12T00:20:00Z", "nfl")
        assertEquals("Q2 · 4:34", SportsFixtureText.periodClock(night))
        assertEquals("7–6", night.score)
        val tie = merged("2475446", "american-football", "2026-10-11T17:00:00Z", "nfl")
        assertEquals(FixtureStatus.FINAL, tie.status)
        assertEquals("Final/OT", tie.detail)
        assertEquals(FixtureStatus.SCHEDULED, merged("2616724", "baseball", "2026-10-12T21:00:00Z", "mlb").status)
        assertEquals(FixtureStatus.SCHEDULED, merged("2400494", "baseball", "2026-10-12T09:30:00Z", "mlb").status)
        assertEquals("Q1 · 5'", SportsFixtureText.periodClock(merged("2601640", "basketball", "2026-10-12T01:00:00Z", "nba")))
        assertEquals(FixtureStatus.FINAL, merged("2601638", "basketball", "2026-10-11T22:30:00Z", "nba").status)
        val flyers = merged("2591570", "ice-hockey", "2026-10-11T23:00:00Z", "nhl")
        assertEquals("P3 · 13'", SportsFixtureText.periodClock(flyers))
        assertEquals("1–3", flyers.score)
        assertEquals(FixtureStatus.FINAL, merged("2591569", "ice-hockey", "2026-10-11T22:00:00Z", "nhl").status)
        val afterOvertime = merged("2526163", "ice-hockey", "2026-10-11T22:00:00Z", "nhl")
        assertEquals(FixtureStatus.FINAL, afterOvertime.status)
        assertEquals("2–2", afterOvertime.score)
        val lastMinute = merged("2600768", "ice-hockey", "2026-10-11T23:00:00Z", "nhl")
        assertEquals(FixtureStatus.LIVE, lastMinute.status)
        assertEquals("P3 · 20'", SportsFixtureText.periodClock(lastMinute))
        assertEquals("P2 · 8'", SportsFixtureText.periodClock(merged("2598764", "ice-hockey", "2026-10-12T00:00:00Z", "nhl")))
    }

    @Test fun lookuptvSundayNightCountriesAllResolve() {
        val channels = SportsTv.parse(sample("tsdb-lookuptv"))
        assertEquals(38, channels.size)
        assertEquals(listOf("ESPN Australia", "Kayo Sports"), SportsTv.names(channels, "AU"))
        assertEquals(listOf("Sky Sports NFL", "Sky Sports Main Event"), SportsTv.names(channels, "GB"))
        assertEquals(listOf("NFL Sunday Ticket", "NBC (WCAU Philadelphia only)", "Universo", "WBAL TV", "WVTM 13", "WWBT"), SportsTv.names(channels, "US"))
        assertEquals(SportsTv.MAX_CHANNELS, SportsTv.names(channels, "CA").size)
        val codes = listOf("AU", "BA", "BR", "CA", "FR", "DE", "GR", "NZ", "QA", "RS", "SI", "ZA", "ES", "NL", "TR", "GB", "US")
        assertTrue(codes.all { code -> SportsTv.names(channels, code).all { name -> channels.first { it.name == name }.country.let { SportsGuide.normalise(it!!) in SportsTv.countryNames(code) } } })
        assertEquals(channels.map { it.country }.distinct().size, codes.size)
    }
}
