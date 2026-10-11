package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataHalfTimeTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012c.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T22:01:54Z")

    @Test fun nflHalfTimeThirdQuarterAndATieAfterOvertime() {
        val games = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).associateBy { it.id }
        assertEquals(4, games.size)
        val half = games.getValue("401872991")
        assertEquals(FixtureStatus.LIVE, half.status)
        assertEquals(2, half.period)
        assertEquals("Halftime", SportsFixtureText.periodClock(half))
        assertEquals(SportsBugText("DET 24–7 ARI", "Halftime", null), SportsFixtureText.bug(half))
        assertEquals(FixtureSituation(null, null, "END QUARTER 2", 4, false, null, null, null), half.situation)
        val spent = games.getValue("401872992").situation!!
        assertNull(spent.homeTimeouts)
        assertNull(spent.awayTimeouts)
        assertEquals(22, spent.homeWinPercent)
        assertEquals(3, SportsFixtureText.timeoutSlots(games.getValue("401872992"), spent.homeTimeouts))
        val third = games.getValue("401872989")
        assertEquals("Q3 · 5:32", SportsFixtureText.periodClock(third))
        assertEquals(FixtureSituation("1st & 10 · DEN 35", FixtureSide.AWAY, "C.Dicker kicks 65 yards from LAC 35 to end zone, Touchback to the DEN 35.", 46, false,
            3, 3, null), third.situation)
        assertEquals(SportsBugText("DEN 10–10 LAC", "Q3 · 5:32", "1st & 10 · DEN 35"), SportsFixtureText.bug(third))
        val tie = games.getValue("401872988")
        assertEquals(FixtureStatus.FINAL, tie.status)
        assertEquals("27–27", tie.score)
        assertNull(tie.situation)
        assertNull(tie.period)
        assertEquals(SportsBugText("NYG 27–27 WSH", "Final/OT", null), SportsFixtureText.bug(tie))
        assertEquals(listOf("6", "6", "7", "8", "0"), tie.homeLine!!.periods)
        assertEquals(listOf("3", "0", "10", "14", "0"), tie.awayLine!!.periods)
        assertEquals("OT", SportsFixtureText.periodLabel(tie, 5))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS_TIE, listOf(1, 3, 1)), SportsRecords.parse(tie.sport, tie.home!!.record))
        assertEquals(SportsRecord(RecordShape.WIN_LOSS_TIE, listOf(3, 1, 1)), SportsRecords.parse(tie.sport, tie.away!!.record))
        assertFalse(SportsFixtureSections.close(tie))
        val entry = SportsCacheEntry(games.values.toList(), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun nflSummaryAfterHalfTime() {
        val summary = EspnSummary.parse(JSONObject(sample("espn-nfl-summary")), "american-football")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals(3, summary.period)
        assertEquals("5:32", summary.clock)
        assertEquals(listOf("0", "3", "7"), summary.homeLine!!.periods)
        assertEquals(listOf("7", "3", "0"), summary.awayLine!!.periods)
        assertEquals(listOf(MomentKind.TOUCHDOWN, MomentKind.POINTS, MomentKind.POINTS, MomentKind.TOUCHDOWN), summary.moments.map { it.kind })
        assertEquals(FixtureSide.HOME to "Hayden Rucci 7 Yd pass from Justin Herbert (Cameron Dicker Kick)", summary.moments.last().side to summary.moments.last().text)
        assertEquals(millis("2026-10-11T21:56:00Z"), summary.moments.last().wallclockMillis)
        assertTrue(SportsMarkers.all(summary, millis("2026-10-11T20:05:00Z")).all { it.second.exact })
        assertEquals(listOf(SummaryPlay("C.Dicker kicks 65 yards from LAC 35 to end zone, Touchback to the DEN 35.", 3, "5:32"),
            SummaryPlay("Official Timeout at 05:32.", 3, "5:32"), SummaryPlay("Hayden Rucci 7 Yd pass from Justin Herbert (Cameron Dicker Kick)", 3, "5:32")), summary.lastPlays)
        assertEquals(.4559f, summary.winProbability.last(), .0001f)
    }

    @Test fun nhlEndOfTheFirstPeriod() {
        val game = EspnScoreboard.parse(sample("espn-nhl-scoreboard"), league("nhl"), now).single()
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("End of 1st", SportsFixtureText.periodClock(game))
        assertEquals(SportsBugText("SEA 2–2 WSH", "End of 1st", null), SportsFixtureText.bug(game))
        assertEquals(FixtureSituation(lastPlay = "End of 1st Period"), game.situation)
        val summary = EspnSummary.parse(JSONObject(sample("espn-nhl-summary")), "ice-hockey")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals("End of 1st", summary.detail)
        assertEquals(listOf(FixtureSide.AWAY, FixtureSide.HOME, FixtureSide.HOME, FixtureSide.AWAY), summary.moments.map { it.side })
        assertEquals(2 to 2, summary.moments.last().homeScore to summary.moments.last().awayScore)
        assertTrue(SportsMarkers.all(summary, millis("2026-10-11T21:00:00Z")).all { it.second.exact })
        assertNull(summary.strength)
        assertEquals(listOf(SummaryGoalie(FixtureSide.HOME, "Logan Thompson", 9, 11, ".818"), SummaryGoalie(FixtureSide.AWAY, "Joey Daccord", 6, 8, ".750")),
            summary.goalies.sortedBy { it.side })
        assertEquals("End of 1st Period", summary.lastPlays.first().text)
    }

    @Test fun livescoreOvertimeFinalHalfTimeAndPenalties() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(11, scores.size)
        val base = SportsFixture("2475446", "nfl", "american-football", "Washington Commanders v New York Giants", FixtureTeam("Washington Commanders"),
            FixtureTeam("New York Giants"), millis("2026-10-11T17:00:00Z"), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)
        fun merged(score: SportsDbLiveScore, fixture: SportsFixture = base, at: Long = now) = SportsDbLive.merge(listOf(fixture), mapOf(fixture.id to score), at).single()
        val tie = merged(scores.getValue("2475446"))
        assertEquals(FixtureStatus.FINAL, tie.status)
        assertEquals("27–27", tie.score)
        assertEquals("Final/OT", tie.detail)
        assertNull(tie.clock)
        assertEquals(SportsBugText("New York Giants 27–27 Washington Commanders", "Final/OT", null), SportsFixtureText.bug(tie))
        val early = merged(scores.getValue("2475446"), at = millis("2026-10-11T20:20:00Z"))
        assertEquals(FixtureStatus.FINAL, early.status)
        assertNull(early.period)
        assertEquals(FixtureStatus.FINAL, merged(scores.getValue("2475446").copy(progress = "Final/SO"), base.copy(sport = "ice-hockey")).status)
        assertNull(merged(scores.getValue("2475439"), base.copy(id = "2475439", startMillis = millis("2026-10-11T13:30:00Z"))).detail)
        assertTrue(SportsDbEvents.finished(SportsGuide.normalise("Final/2OT")))
        assertFalse(SportsDbEvents.finished(SportsGuide.normalise("Final Four")))
        val day = """{"events":[{"idEvent":"2475446","idLeague":"4391","strLeague":"NFL","strHomeTeam":"Washington Commanders","strAwayTeam":"New York Giants",
            "intHomeScore":"27","intAwayScore":"27","strStatus":"Final/OT","strTimestamp":"2026-10-11T17:00:00"}]}"""
        assertEquals(FixtureStatus.FINAL, SportsDbEvents.parse(day, league("nfl"), millis("2026-10-11T20:50:00Z")).single().status)
        val half = merged(scores.getValue("2475449"), base.copy(id = "2475449", startMillis = millis("2026-10-11T20:25:00Z")))
        assertEquals(FixtureStatus.LIVE, half.status)
        assertEquals("Halftime", SportsFixtureText.periodClock(half))
        assertEquals("7–24", half.score)
        val third = merged(scores.getValue("2475447"), base.copy(id = "2475447", startMillis = millis("2026-10-11T20:05:00Z")))
        assertEquals("Q3 · 5:32", SportsFixtureText.periodClock(third))
        val cup = SportsFixture("2615724", "sdb-4500", "soccer", "Independiente Santa Fe v Fortaleza FC", FixtureTeam("Independiente Santa Fe"), FixtureTeam("Fortaleza FC"),
            millis("2026-10-11T19:00:00Z"), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)
        val shootout = scores.getValue("2615724")
        assertEquals("P", shootout.status)
        assertNull(shootout.progress)
        assertEquals(cup, merged(shootout, cup))
        val fresh = merged(shootout.copy(updatedMillis = now - 60_000), cup)
        assertEquals(FixtureStatus.LIVE, fresh.status)
        assertEquals("2–2", fresh.score)
        assertEquals(SportsBugText("Independiente Santa Fe 2–2 Fortaleza FC", "P", null), SportsFixtureText.bug(fresh))
        val junior = base.copy(id = "2517720", league = "college-hockey", sport = "ice-hockey", startMillis = millis("2026-10-10T22:00:00Z"))
        assertEquals(junior, merged(scores.getValue("2517720"), junior))
    }

    @Test fun lookuptvCountriesAllResolve() {
        val channels = SportsTv.parse(sample("tsdb-lookuptv"))
        assertEquals(31, channels.size)
        assertEquals(listOf("Sky Sports NFL", "Sky Sports Football", "Sky Sports+"), SportsTv.names(channels, "GB"))
        assertEquals(listOf("DAZN Turkey"), SportsTv.names(channels, "TR"))
        assertEquals(listOf("Nova Sport 2 CZ", "DAZN Czechia"), SportsTv.names(channels, "CZ"))
        val codes = listOf("IS", "US", "GB", "SI", "FR", "HR", "QA", "ES", "CZ", "CA", "DE", "NZ", "AU", "BR", "GR", "IT", "PL", "PT", "RS", "TR")
        assertTrue(codes.all { code -> SportsTv.names(channels, code).all { name -> channels.first { it.name == name }.country.let { SportsGuide.normalise(it!!) in SportsTv.countryNames(code) } } })
        assertEquals(channels.map { it.country }.distinct().size, codes.size)
    }
}
