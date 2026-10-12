package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataEmptyNetTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012d.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T23:40:31Z")

    @Test fun nflLastMinuteAfterATurnoverOnDowns() {
        val games = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).associateBy { it.id }
        assertEquals(2, games.size)
        val late = games.getValue("401872992")
        assertEquals(FixtureStatus.LIVE, late.status)
        assertEquals("Q4 · 0:47", SportsFixtureText.periodClock(late))
        assertEquals(FixtureSituation("1st & 10 · SF 41", FixtureSide.AWAY, "S.Darnold pass short left to C.Kupp pushed ob at SF 41 for 9 yards (M.Mustapha).", 0, false,
            1, 2, null), late.situation)
        assertEquals(SportsBugText("SF 32–29 SEA", "Q4 · 0:47", "1st & 10 · SF 41"), SportsFixtureText.bug(late))
        assertEquals(3, SportsFixtureText.timeoutSlots(late, late.situation!!.homeTimeouts))
        assertTrue(SportsFixtureSections.close(late))
        val final = games.getValue("401872989")
        assertEquals(FixtureStatus.FINAL, final.status)
        assertNull(final.situation)
        assertEquals(SportsBugText("DEN 23–17 LAC", "Final", null), SportsFixtureText.bug(final))
        val entry = SportsCacheEntry(games.values.toList(), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun nhlEmptyNetLateInTheThird() {
        val games = EspnScoreboard.parse(sample("espn-nhl-scoreboard"), league("nhl"), now).associateBy { it.id }
        assertTrue(games.values.all { it.status == FixtureStatus.LIVE })
        assertEquals(SportsBugText("SEA 5–3 WSH", "P3 · 1:35", null), SportsFixtureText.bug(games.getValue("401892482")))
        assertEquals(FixtureSituation(lastPlay = "Goalie Stopped"), games.getValue("401892482").situation)
        assertEquals("End of 2nd", SportsFixtureText.periodClock(games.getValue("401892483")))
        assertEquals("P1 · 5:57", SportsFixtureText.periodClock(games.getValue("401892484")))
        val root = JSONObject(sample("espn-nhl-summary"))
        val summary = EspnSummary.parse(root, "ice-hockey")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals(3 to "1:38", summary.period to summary.clock)
        assertNull(summary.strength)
        assertEquals(8, summary.moments.count { it.kind == MomentKind.GOAL })
        assertEquals(3 to 5, summary.moments.last().homeScore to summary.moments.last().awayScore)
        assertTrue(SportsMarkers.all(summary, millis("2026-10-11T21:00:00Z")).all { it.second.exact })
        val thompson = SummaryGoalie(FixtureSide.HOME, "Logan Thompson", 22, 27, ".815", pulled = true)
        val daccord = SummaryGoalie(FixtureSide.AWAY, "Joey Daccord", 30, 33, ".909")
        assertEquals(listOf(thompson, daccord), summary.goalies.sortedBy { it.side })
        assertEquals(listOf(daccord), SportsGoalies.inNet(summary.goalies, true))
        fun goalies(change: (JSONObject) -> Unit) = EspnSummary.parse(JSONObject(root.toString()).also(change), "ice-hockey").goalies
        fun ice(json: JSONObject, team: String) = (0 until json.getJSONArray("onIce").length()).map { json.getJSONArray("onIce").getJSONObject(it) }
            .first { it.getString("teamId") == team }.getJSONArray("entries")
        val back = goalies { ice(it, "23").put(JSONObject().put("athleteid", "4272888")) }
        assertTrue(back.none { it.pulled })
        assertEquals(2, SportsGoalies.inNet(back, true).size)
        assertTrue(goalies { json -> ice(json, "124292").let { entries -> entries.remove((0 until entries.length()).first { entries.getJSONObject(it).getString("athleteid") == "3942073" }) } }
            .none { it.pulled })
        assertTrue(goalies { json -> ice(json, "23").let { entries -> repeat(2) { entries.remove(0) } } }.none { it.pulled })
        assertTrue(goalies { it.remove("onIce") }.none { it.pulled })
        assertTrue(goalies { json -> json.getJSONObject("header").getJSONArray("competitions").getJSONObject(0).getJSONObject("status").getJSONObject("type")
            .put("name", "STATUS_END_PERIOD") }.none { it.pulled })
    }

    @Test fun nbaSecondQuarterLive() {
        val games = EspnScoreboard.parse(sample("espn-nba-scoreboard"), league("nba"), now).associateBy { it.id }
        val cavs = games.getValue("401898401")
        assertEquals(FixtureStatus.LIVE, cavs.status)
        assertEquals(SportsBugText("ORL 41–51 CLE", "Q2 · 3:15", null), SportsFixtureText.bug(cavs))
        assertEquals(FixtureSituation(lastPlay = "Jarrett Allen enters the game for Meleek Thomas", homeWinPercent = 82), cavs.situation)
        assertEquals(listOf("32", "19"), cavs.homeLine!!.periods)
        assertFalse(SportsFixtureSections.close(cavs))
        val hornets = games.getValue("401901821")
        assertEquals(FixtureSituation(lastPlay = "Bucks Full timeout", homeWinPercent = 66), hornets.situation)
        assertTrue(SportsFixtureSections.close(hornets))
    }

    @Test fun nascarRaceFinishedWithoutAWinnerFlag() {
        val race = EspnScoreboard.parse(sample("espn-nascar-scoreboard"), league("nascar-cup"), now).single()
        assertEquals(FixtureStatus.FINAL, race.status)
        val detail = race.sportDetail as SportsDetail.Sessions
        assertEquals(FixtureStatus.FINAL, detail.current!!.state)
        assertNull(detail.current!!.lap)
        assertEquals(SportsBugText("NASCAR Cup Series at Charlotte", "Race · finished", "1 C. Briscoe · 2 C. Bell · 3 R. Blaney"), SportsFixtureText.bug(race))
    }

    @Test fun livescoreLastMinuteBreaksAndStaleOvertime() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(10, scores.size)
        fun merged(id: String, sport: String, start: String, league: String = "nfl") = SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away",
            FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, now).single()
        val late = merged("2475450", "american-football", "2026-10-11T20:25:00Z")
        assertEquals(FixtureStatus.LIVE, late.status)
        assertEquals("Q4 · 0:47", SportsFixtureText.periodClock(late))
        assertEquals("29–32", late.score)
        assertEquals(FixtureStatus.FINAL, merged("2475447", "american-football", "2026-10-11T20:05:00Z").status)
        assertEquals("Q2 · 9'", SportsFixtureText.periodClock(merged("2601638", "basketball", "2026-10-11T22:30:00Z", "nba")))
        val third = merged("2591568", "ice-hockey", "2026-10-11T21:00:00Z", "nhl")
        assertEquals("P3 · 19'", SportsFixtureText.periodClock(third))
        assertEquals("3–5", third.score)
        val interval = merged("2591569", "ice-hockey", "2026-10-11T22:00:00Z", "nhl")
        assertEquals(FixtureStatus.LIVE, interval.status)
        assertEquals("BT", SportsFixtureText.periodClock(interval))
        assertEquals(FixtureStatus.FINAL, merged("2600767", "ice-hockey", "2026-10-11T20:00:00Z", "nhl").status)
        assertEquals(FixtureStatus.SCHEDULED, merged("2526140", "ice-hockey", "2026-10-10T01:05:00Z", "nhl").status)
    }

    @Test fun lookuptvCountriesAllResolve() {
        val channels = SportsTv.parse(sample("tsdb-lookuptv"))
        assertEquals(20, channels.size)
        assertEquals(listOf("ESPN Australia"), SportsTv.names(channels, "AU"))
        assertEquals(listOf("Sky Sports Football", "Sky Sports Main Event", "Sky Sports NFL"), SportsTv.names(channels, "GB"))
        assertEquals(listOf("DAZN Greece", "ERT Sports 2 GR"), SportsTv.names(channels, "GR"))
        val codes = listOf("US", "AU", "IS", "SI", "CA", "GB", "BG", "BR", "HR", "CZ", "GR", "NZ", "PL", "RS", "TR")
        assertTrue(codes.all { code -> SportsTv.names(channels, code).all { name -> channels.first { it.name == name }.country.let { SportsGuide.normalise(it!!) in SportsTv.countryNames(code) } } })
        assertEquals(channels.map { it.country }.distinct().size, codes.size)
    }
}
