package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataRaceNightTest {
    private fun sample(name: String, capture: String = "20261011e"): String =
        javaClass.getResourceAsStream("/sports/$name-real-$capture.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T12:46:00Z")

    @After fun clear() { SportsLeagues.custom = emptyList() }

    @Test fun raceInProgressShowsTheLapAndRunningOrder() {
        val event = EspnScoreboard.parse(sample("espn-f1-scoreboard"), league("f1"), now).single()
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals(millis("2026-10-11T12:00:00Z"), event.startMillis)
        assertEquals("In Progress", event.detail)
        val detail = event.sportDetail as SportsDetail.Sessions
        val race = detail.current!!
        assertEquals("Race", race.name)
        assertEquals(FixtureStatus.LIVE, race.state)
        assertEquals(26, race.lap)
        assertEquals(listOf("M. Verstappen", "L. Norris", "O. Piastri"), race.top)
        assertEquals(listOf("M. Verstappen", "C. Leclerc", "L. Hamilton"), detail.sessions.first { it.name == "Qualifying" }.top)
        assertTrue(detail.sessions.filter { it !== race }.all { it.state == FixtureStatus.FINAL && it.lap == null })
        assertNull(SportsOverlayText.nextSession(detail))
        assertEquals(SportsBugText("Singapore Airlines Singapore Grand Prix", "Race · Lap 26", "1 M. Verstappen · 2 L. Norris · 3 O. Piastri"), SportsFixtureText.bug(event))
        assertEquals(detail, SportsDetails.decode(SportsDetails.encode(detail)))
        assertTrue(SportsRefresh.active(listOf(event), now))
        val before = EspnScoreboard.parse(sample("espn-f1-scoreboard", "20261011d"), league("f1"), millis("2026-10-11T08:36:00Z")).single()
        val waiting = (before.sportDetail as SportsDetail.Sessions).current!!
        assertNull(waiting.lap)
        assertTrue(waiting.top.isEmpty())
    }

    @Test fun liveTennisInTheThirdSetAndWithAStraySituation() {
        val matches = EspnScoreboard.parse(sample("espn-wta-scoreboard"), league("wta"), now).associateBy { it.id }
        assertEquals(4, matches.size)
        assertTrue(matches.values.all { it.status == FixtureStatus.LIVE })
        val decider = matches.getValue("185251")
        assertEquals(3, decider.period)
        assertEquals(listOf(TennisSet(7, 5, winner = FixtureSide.HOME), TennisSet(2, 6, winner = FixtureSide.AWAY), TennisSet(5, 2)),
            (decider.sportDetail as SportsDetail.Tennis).sets)
        assertEquals("1–1", decider.score)
        assertEquals(SportsBugText("AKSU 7 2 5 / STEFANINI 5 6 2", "3rd Set", null), SportsFixtureText.bug(decider))
        val served = matches.getValue("186406")
        assertNull(served.situation)
        assertEquals(FixtureSide.AWAY, (served.sportDetail as SportsDetail.Tennis).server)
        assertEquals(SportsBugText("SERBAN 2 1 / LI 6 0", "2nd Set", "LI serving"), SportsFixtureText.bug(served))
        val opening = matches.getValue("186443")
        assertEquals(listOf(TennisSet(0, 0)), (opening.sportDetail as SportsDetail.Tennis).sets)
        assertEquals(SportsBugText("CAKAREVIC 0 / TSYGOUROVA 0", "1st Set", null), SportsFixtureText.bug(opening))
        assertEquals("Zheng Qinwen v Mirra Andreeva", matches.getValue("184356").title)
    }

    @Test fun lineUpsArriveBeforeKickOff() {
        val summary = EspnSummary.parse(JSONObject(sample("espn-epl-summary")), "soccer")
        assertEquals(FixtureStatus.SCHEDULED, summary.status)
        assertEquals(listOf(FixtureSide.HOME, FixtureSide.AWAY), summary.rosters.map { it.side })
        assertTrue(summary.rosters.all { it.starters.size == 11 && it.subs.size == 9 && it.formation == "3-4-2-1" })
        assertEquals("Walter Benítez", summary.rosters[0].starters[0].name)
        assertEquals("G", summary.rosters[0].starters[0].position)
        assertTrue(summary.moments.isEmpty())
    }

    @Test fun livescoreRowsWithoutAStatusStopCountingOnceTheGameShouldBeOver() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(12, scores.size)
        val leagues = listOf(SportsDbLeague("5440", "Basketball League", "Basketball"), SportsDbLeague("5441", "Hockey League", "Ice Hockey"),
            SportsDbLeague("5442", "Football League", "Soccer")).map(SportsDbLeagues::league)
        SportsLeagues.custom = leagues
        fun merged(id: String, start: String, sport: String, at: Long = now) = SportsDbLive.merge(listOf(SportsFixture(id, leagues.first { it.sport == sport }.id, sport, "Home v Away",
            FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, at).single()
        val unknown = merged("2612962", "2026-10-11T10:00:00Z", "basketball")
        assertEquals(FixtureStatus.SCHEDULED, unknown.status)
        assertNull(unknown.score)
        val early = merged("2612962", "2026-10-11T10:00:00Z", "basketball", millis("2026-10-11T11:00:00Z"))
        assertEquals(FixtureStatus.LIVE, early.status)
        assertEquals("37–45", early.score)
        val interval = merged("2523160", "2026-10-11T12:00:00Z", "ice-hockey")
        assertEquals(FixtureStatus.LIVE, interval.status)
        assertEquals("BT", interval.detail)
        assertEquals("2–0", interval.score)
        assertNull(interval.period)
        assertEquals(FixtureStatus.SCHEDULED, merged("2526140", "2026-10-10T01:05:00Z", "ice-hockey").status)
        val overtime = merged("2526140", "2026-10-10T01:05:00Z", "ice-hockey", millis("2026-10-10T04:10:00Z"))
        assertEquals(FixtureStatus.LIVE, overtime.status)
        assertEquals(4, overtime.period)
        assertEquals("OT · 4'", SportsFixtureText.periodClock(overtime))
        assertEquals(FixtureStatus.SCHEDULED, merged("2517720", "2026-10-10T22:00:00Z", "ice-hockey").status)
        val third = merged("2524548", "2026-10-11T11:00:00Z", "ice-hockey")
        assertEquals("P3 · 9'", SportsFixtureText.periodClock(third))
        assertEquals(FixtureStatus.SCHEDULED, merged("2584726", "2026-10-11T15:00:00Z", "basketball").status)
        val quarter = merged("2564058", "2026-10-11T10:30:00Z", "basketball")
        assertEquals(4, quarter.period)
        assertEquals("10'", quarter.clock)
        assertEquals("96–82", quarter.score)
        val half = merged("2598068", "2026-10-11T11:45:00Z", "soccer")
        assertEquals("HT", SportsFixtureText.bug(half).state)
        val stoppage = merged("2481848", "2026-10-11T12:00:00Z", "soccer")
        assertEquals(1, stoppage.period)
        assertEquals("45+1'", stoppage.clock)
        val acb = merged("2571746", "2026-10-11T10:00:00Z", "basketball")
        assertEquals(FixtureStatus.FINAL, acb.status)
        assertEquals("91–89", acb.score)
        assertNull(acb.clock)
        assertEquals("115–132", merged("2548859", "2026-10-11T10:00:00Z", "basketball").score)
        assertEquals("87'", merged("2594989", "2026-10-11T11:00:00Z", "soccer").clock)
    }
}
