package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataMondayTest {
    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-20261012.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T19:46:00Z")

    @After fun clear() { SportsLeagues.custom = emptyList() }

    @Test fun liveNflGamesCarryTheDownTimeoutsAndDrive() {
        val games = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now).associateBy { it.id }
        assertEquals(10, games.size)
        assertEquals(8, games.values.count { it.status == FixtureStatus.LIVE })
        assertTrue(games.values.filter { it.status == FixtureStatus.LIVE }.all { it.period == 4 && it.situation != null })
        val packers = games.getValue("401872990")
        assertEquals(FixtureSituation("3rd & 14 · CHI 37", FixtureSide.HOME, "K.Johnson left end to CHI 37 for -2 yards (A.Booker).", 100, false, 1, 3,
            "4 plays, 20 yards, 2:00"), packers.situation)
        assertEquals(SportsBugText("CHI 7–31 GB", "Q4 · 6:22", "3rd & 14 · CHI 37"), SportsFixtureText.bug(packers))
        assertEquals(listOf("0", "7", "0", "0"), packers.awayLine!!.periods)
        val jets = games.getValue("401872983").situation!!
        assertEquals(FixtureSide.HOME, jets.possession)
        assertEquals("1st & 10 · NYJ 45", jets.downDistance)
        assertNull(jets.drive)
        assertEquals(50, jets.homeWinPercent)
        val bengals = games.getValue("401872982").situation!!
        assertEquals(FixtureSide.AWAY, bengals.possession)
        assertTrue(bengals.lastPlay!!.startsWith("J.Burrow sacked at CIN 15"))
        assertTrue(bengals.lastPlay!!.endsWith("enforced at CIN 22 - No Play."))
        val titans = games.getValue("401872984").situation!!
        assertEquals("T.Spears up the middle to TEN 44 for 3 yards (S.Rankins).", titans.lastPlay)
        assertEquals("8 plays, 36 yards, 3:04", titans.drive)
        val saints = games.getValue("401872987").situation!!
        assertEquals(FixtureSide.AWAY, saints.possession)
        assertNull(saints.drive)
        assertTrue(saints.lastPlay!!.startsWith("T.Shough pass deep left"))
        val patriots = games.getValue("401872986")
        assertEquals(FixtureSituation(null, FixtureSide.AWAY, "Extra Point Good (Matt Gay Kick)", null, false, 2, 3, null), patriots.situation)
        assertEquals(SportsBugText("LV 17–38 NE", "Q4 · 10:49", null), SportsFixtureText.bug(patriots))
        assertEquals(FixtureStatus.FINAL, games.getValue("401872981").status)
        assertNull(games.getValue("401872981").situation)
        assertEquals(FixtureStatus.SCHEDULED, games.getValue("401872989").status)
        val entry = SportsCacheEntry(games.values.toList(), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
        assertEquals(160.0, SportsMarkers.minutes("american-football", 4, "6:22")!!, 0.1)
    }

    @Test fun formationTagsLeaveThePlay() {
        assertEquals("J.Burrow sacked", sportsPlay("(Shotgun) J.Burrow sacked"))
        assertEquals("T.Shough pass", sportsPlay("(No Huddle, Shotgun) T.Shough pass"))
        assertEquals("T.Smack punts", sportsPlay("(Punt formation) T.Smack punts"))
        assertEquals("(Matt Gay Kick)", sportsPlay("(Matt Gay Kick)"))
        assertEquals("#10 J.Hoover pass", sportsPlay("No Huddle-Shotgun #10 J.Hoover pass"))
        assertEquals("#20 D.Phillips Jr. rush", sportsPlay("Shotgun #20 D.Phillips Jr. rush"))
        assertEquals("Shotgun formation change", sportsPlay("Shotgun formation change"))
        assertEquals("(13:02) J.Love pass", sportsPlay("(13:02) J.Love pass"))
    }

    @Test fun liveNflSummaryUsesTheRealPlayTimes() {
        val summary = EspnSummary.parse(JSONObject(sample("espn-nfl-summary")), "american-football")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals(4, summary.period)
        assertEquals("6:22", summary.clock)
        assertEquals(listOf("7", "7", "9", "8"), summary.homeLine!!.periods)
        assertEquals("31", summary.homeLine!!.score)
        assertEquals(listOf(MomentKind.TOUCHDOWN, MomentKind.TOUCHDOWN, MomentKind.TOUCHDOWN, MomentKind.TOUCHDOWN, MomentKind.POINTS, MomentKind.TOUCHDOWN),
            summary.moments.map { it.kind })
        assertEquals(FixtureSide.AWAY, summary.moments[2].side)
        assertEquals(millis("2026-10-11T17:15:01Z"), summary.moments.first().wallclockMillis)
        val markers = SportsMarkers.all(summary, millis("2026-10-11T17:00:00Z"))
        assertEquals(6, markers.size)
        assertTrue(markers.all { it.second.exact })
        assertEquals(31 to 7, summary.moments.last().homeScore to summary.moments.last().awayScore)
        assertEquals(listOf("1st downs", "Total yards", "Passing", "Rushing", "Turnovers", "3rd down", "Penalties", "Possession"), summary.stats.map { it.label })
        assertEquals(SummaryStat("Turnovers", "0", "4"), summary.stats[4])
        assertEquals(SummaryLeader(FixtureSide.HOME, "Passing Yards", "Jordan Love", "359 YDS"), summary.leaders.first())
        assertEquals("K.Johnson left end to CHI 37 for -2 yards (A.Booker).", summary.lastPlays.first().text)
        assertEquals("6:22", summary.lastPlays.first().clock)
        assertEquals(.999f, summary.winProbability.last(), .001f)
    }

    @Test fun nascarInProgressUsesTheLiveRunningOrder() {
        val race = EspnScoreboard.parse(sample("espn-nascar-scoreboard"), league("nascar-cup"), now).single()
        assertEquals(FixtureStatus.LIVE, race.status)
        val session = (race.sportDetail as SportsDetail.Sessions).current!!
        assertEquals("Race", session.name)
        assertEquals(41, session.lap)
        assertEquals(listOf("K. Larson", "A. Cindric", "T. Reddick"), session.top)
        assertNull(race.venue)
        assertEquals(SportsBugText("NASCAR Cup Series at Charlotte", "Race · Lap 41", "1 K. Larson · 2 A. Cindric · 3 T. Reddick"), SportsFixtureText.bug(race))
    }

    @Test fun finishedGrandPrixShowsTheResultInsteadOfTheCircuit() {
        val event = EspnScoreboard.parse(sample("espn-f1-scoreboard"), league("f1"), now).single()
        assertEquals(FixtureStatus.FINAL, event.status)
        val detail = event.sportDetail as SportsDetail.Sessions
        val race = detail.current!!
        assertEquals("Race", race.name)
        assertNull(race.lap)
        assertEquals(listOf("M. Verstappen", "C. Leclerc", "K. Antonelli"), race.top)
        assertEquals(SportsBugText("Singapore Airlines Singapore Grand Prix", "Race · finished", "1 M. Verstappen · 2 C. Leclerc · 3 K. Antonelli"),
            SportsFixtureText.bug(event))
        assertNull(SportsOverlayText.nextSession(detail))
    }

    @Test fun livescoreNflRowsAndFinishedHockey() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(9, scores.size)
        val leagues = listOf(SportsDbLeague("5440", "Basketball League", "Basketball"), SportsDbLeague("5441", "Hockey League", "Ice Hockey"))
            .map(SportsDbLeagues::league)
        SportsLeagues.custom = leagues
        fun merged(id: String, start: String, sport: String, at: Long = now) = SportsDbLive.merge(listOf(SportsFixture(id,
            if (sport == "american-football") "nfl" else leagues.first { it.sport == sport }.id, sport, "Home v Away", FixtureTeam("Home"), FixtureTeam("Away"),
            millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, at).single()
        val titans = merged("2475440", "2026-10-11T17:00:00Z", "american-football")
        assertEquals(FixtureStatus.LIVE, titans.status)
        assertEquals(4, titans.period)
        assertEquals("3:08", titans.clock)
        assertEquals("Q4 · 3:08", SportsFixtureText.periodClock(titans))
        assertEquals("3–26", titans.score)
        assertEquals(SportsBugText("Away 26–3 Home", "Q4 · 3:08", null), SportsFixtureText.bug(titans))
        val early = merged("2475439", "2026-10-11T13:30:00Z", "american-football", millis("2026-10-11T16:30:00Z"))
        assertEquals(FixtureStatus.FINAL, early.status)
        assertEquals("19–17", early.score)
        val overtime = SportsDbLive.merge(listOf(titans.copy(status = FixtureStatus.SCHEDULED, period = null, clock = null)),
            mapOf("2475440" to scores.getValue("2475440").copy(progress = "6:12 - OT")), now).single()
        assertEquals(5, overtime.period)
        assertEquals("OT · 6:12", SportsFixtureText.periodClock(overtime))
        val words = SportsDbLive.merge(listOf(titans.copy(status = FixtureStatus.SCHEDULED, period = null, clock = null)),
            mapOf("2475440" to scores.getValue("2475440").copy(progress = "End of 3rd")), now).single()
        assertEquals("End of 3rd", SportsFixtureText.periodClock(words))
        val penalties = merged("2518549", "2026-10-11T16:30:00Z", "ice-hockey")
        assertEquals(FixtureStatus.FINAL, penalties.status)
        assertEquals("3–4", penalties.score)
        assertEquals(FixtureStatus.FINAL, merged("2480680", "2026-10-11T16:30:00Z", "ice-hockey").status)
        assertEquals(FixtureStatus.SCHEDULED, merged("2518194", "2026-10-11T14:30:00Z", "ice-hockey").status)
        val stuckEarlier = merged("2518194", "2026-10-11T14:30:00Z", "ice-hockey", millis("2026-10-11T17:00:00Z"))
        assertEquals(FixtureStatus.LIVE, stuckEarlier.status)
        assertEquals("P3 · 20'", SportsFixtureText.periodClock(stuckEarlier))
        assertEquals("P3 · 19'", SportsFixtureText.periodClock(merged("2526155", "2026-10-11T17:05:00Z", "ice-hockey")))
        val half = merged("2584832", "2026-10-11T18:45:00Z", "basketball")
        assertEquals("HT", SportsFixtureText.periodClock(half))
        assertEquals("P1 · 19'", SportsFixtureText.periodClock(merged("2559228", "2026-10-11T19:00:00Z", "ice-hockey")))
    }
}
