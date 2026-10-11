package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataSundayEveningTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name-real-20261011d.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T08:36:00Z")
    private val cricket = SportsLeague("cricket-test", "Cricket", "cricket", "cricket/8676", null)

    @Test fun liveTennisKeepsGamesInTheOpenSetAndTheServer() {
        val matches = EspnScoreboard.parse(sample("espn-wta-scoreboard"), league("wta"), now)
        assertTrue(matches.take(3).all { it.status == FixtureStatus.LIVE })
        val final = matches.first { it.id == "185297" }
        assertEquals("Rebecca Sramkova v Kyoka Okamura", final.title)
        assertEquals("1–0", final.score)
        assertEquals(2, final.period)
        assertEquals("2nd Set", final.detail)
        val detail = final.sportDetail as SportsDetail.Tennis
        assertEquals("Final", detail.round)
        assertEquals("Center Court", detail.court)
        assertEquals(listOf(TennisSet(6, 3, winner = FixtureSide.HOME), TennisSet(0, 0)), detail.sets)
        assertEquals(FixtureSide.AWAY, detail.server)
        assertFalse(detail.servingForSet)
        assertEquals(listOf("6", "0"), final.homeLine?.periods)
        assertEquals(SportsBugText("SRAMKOVA 6 0 / OKAMURA 3 0", "2nd Set", "OKAMURA serving"), SportsFixtureText.bug(final))
        val first = matches.first { it.id == "186427" }
        val opening = first.sportDetail as SportsDetail.Tennis
        assertEquals(listOf(TennisSet(4, 1)), opening.sets)
        assertNull(opening.server)
        assertEquals("0–0", first.score)
        assertEquals(SportsBugText("GARCIA CID 4 / HESSE 1", "1st Set", null), SportsFixtureText.bug(first))
        val behind = matches.first { it.id == "186421" }.sportDetail as SportsDetail.Tennis
        assertEquals(FixtureSide.AWAY, behind.sets.first().winner)
        assertEquals(FixtureSide.HOME, behind.server)
    }

    @Test fun liveDoublesShowTheLosingTiebreakOnly() {
        val matches = EspnScoreboard.parse(sample("espn-atp-scoreboard"), league("atp"), now)
        assertEquals(setOf("184969", "184944"), matches.map { it.id }.toSet())
        val doubles = matches.first { it.id == "184969" }
        val detail = doubles.sportDetail as SportsDetail.Tennis
        assertEquals(TennisSet(7, 6, 7, 2, FixtureSide.HOME), detail.sets[0])
        assertEquals(TennisSet(1, 2), detail.sets[1])
        assertEquals(4, detail.homePlayer.seed)
        assertEquals(SportsBugText("BOLELLI/VAVASSORI 7 1 / KRAJICEK/MEKTIC 6(2) 2", "2nd Set", "BOLELLI/VAVASSORI serving"), SportsFixtureText.bug(doubles))
        val singles = matches.first { it.id == "184944" }
        assertEquals(SportsBugText("CERUNDOLO 3 1 / MENSIK 6 1", "2nd Set", "CERUNDOLO serving"), SportsFixtureText.bug(singles))
    }

    @Test fun finishedFightCardKeepsMethodRoundAndTime() {
        val event = EspnScoreboard.parse(sample("espn-ufc-scoreboard"), league("ufc"), now).single()
        assertEquals(FixtureStatus.FINAL, event.status)
        val card = event.sportDetail as SportsDetail.Card
        assertNull(card.live)
        val main = card.mainEvent!!
        assertEquals("Brendan Allen", main.first.name)
        assertEquals(1, main.winner)
        assertEquals(FightMethod.SUBMISSION, main.method)
        assertEquals(5, main.endRound)
        assertEquals("2:46", main.endClock)
        assertNull(main.round)
        assertEquals(SportsBugText("B. Allen v C. Duncan", "Final", "B. Allen · Submission · R5 2:46"), SportsFixtureText.bug(event))
        val draw = card.bouts.first { it.first.name == "Ernesta Kareckaite" }
        assertNull(draw.winner)
        assertEquals(FightMethod.DRAW, draw.method)
        assertEquals("Draw", SportsFixtureText.finish(draw))
        val decision = card.bouts.first { it.second.name == "Ketlen Souza" || it.first.name == "Ketlen Souza" }
        assertEquals(FightMethod.DECISION, decision.method)
        assertEquals(3, decision.endRound)
        assertEquals("L. Godínez · Decision", SportsFixtureText.finish(decision))
        val knockout = card.bouts.first { it.first.name == "Malcolm Wellmaker" || it.second.name == "Malcolm Wellmaker" }
        assertEquals(FightMethod.KO, knockout.method)
        assertEquals("O. Tanzilovi · KO/TKO · R2 2:39", SportsFixtureText.finish(knockout))
        assertEquals(listOf(FightMethod.DRAW, FightMethod.KO, FightMethod.KO, FightMethod.SUBMISSION, FightMethod.KO, FightMethod.KO, FightMethod.KO, FightMethod.KO,
            FightMethod.KO, FightMethod.DECISION, FightMethod.DECISION, FightMethod.SUBMISSION), card.bouts.map { it.method })
        assertEquals(card, SportsDetails.decode(SportsDetails.encode(card)))
    }

    @Test fun finalRoundLeaderboardWithTies() {
        val event = EspnScoreboard.parse(sample("espn-pga-scoreboard"), league("pga"), now).single()
        assertEquals(FixtureStatus.FINAL, event.status)
        assertEquals("Final", event.detail)
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(4, golf.round)
        assertEquals(listOf("1", "T2", "T2", "T4", "T4", "T4", "T4", "T4", "T9", "T9"), golf.leaders.map { it.position })
        assertEquals(GolfPlayer("1", "Michael Brennan", "M. Brennan", "USA", "https://a.espncdn.com/i/teamlogos/countries/500/usa.png", "-16", "F", "-3"), golf.leaders[0])
        assertTrue(golf.leaders.all { it.thru == "F" })
        assertEquals(SportsBugText("M. Brennan -16", "Final", "F"), SportsFixtureText.bug(event))
    }

    @Test fun motorRacingFollowsTheSessionsNotTheEventStatus() {
        val event = EspnScoreboard.parse(sample("espn-f1-scoreboard"), league("f1"), now).single()
        assertEquals(FixtureStatus.SCHEDULED, event.status)
        assertEquals(millis("2026-10-11T12:00:00Z"), event.startMillis)
        val sessions = event.sportDetail as SportsDetail.Sessions
        assertEquals("Race", sessions.current?.name)
        assertEquals(listOf("M. Verstappen", "C. Leclerc", "L. Hamilton"), sessions.sessions.first { it.name == "Qualifying" }.top)
        assertEquals(listOf("M. Verstappen", "L. Hamilton", "C. Leclerc"), sessions.sessions.first { it.name == "Sprint" }.top)
        assertEquals(SportsBugText("Singapore Airlines Singapore Grand Prix", "Race · next", "Marina Bay Street Circuit"), SportsFixtureText.bug(event))
    }

    @Test fun nflBeforeKickOffAndThursdaysFinal() {
        val games = EspnScoreboard.parse(sample("espn-nfl-scoreboard"), league("nfl"), now)
        val london = games.first { it.id == "401872981" }
        assertEquals(FixtureStatus.SCHEDULED, london.status)
        assertNull(london.score)
        assertNull(london.homeLine)
        assertEquals("Jacksonville Jaguars v Philadelphia Eagles", london.title)
        assertEquals("3-1", london.home?.record)
        assertEquals("2-2", london.away?.record)
        assertEquals(listOf("NFL Net"), london.broadcasters)
        assertEquals("Tottenham Hotspur Stadium", london.venue)
        assertEquals(5, london.round)
        assertEquals(millis("2026-10-11T13:30:00Z"), london.startMillis)
        assertEquals(SportsBugText("PHI @ JAX", null, null), SportsFixtureText.bug(london))
        assertEquals(listOf("Fox"), games.first { it.id == "401872990" }.broadcasters)
        val thursday = games.first { it.id == "401872980" }
        assertEquals(FixtureStatus.FINAL, thursday.status)
        assertEquals("16–24", thursday.score)
        assertEquals(listOf("0", "7", "17", "0"), thursday.awayLine?.periods)
        assertEquals("1-4", thursday.away?.record)
        assertEquals(SportsBugText("TB 24–16 DAL", "Final", null), SportsFixtureText.bug(thursday))
    }

    @Test fun overtimeAndShootoutFinalsLabelTheExtraColumns() {
        val games = EspnScoreboard.parse(sample("espn-nhl-scoreboard"), league("nhl"), now)
        val shootout = games.first { it.id == "401892473" }
        assertEquals("Final/SO", shootout.detail)
        assertEquals("1–0", shootout.score)
        assertEquals(listOf("0", "0", "0", "0", "1"), shootout.homeLine?.periods)
        assertEquals(listOf("P1", "P2", "P3", "OT", "SO"), (1..5).map { SportsFixtureText.periodLabel(shootout, it) })
        val overtime = games.first { it.detail == "Final/OT" }
        assertEquals(listOf("P1", "P2", "P3", "OT"), (1..4).map { SportsFixtureText.periodLabel(overtime, it) })
        val regulation = games.first { it.id == "401892469" }
        assertEquals("Final", regulation.detail)
        assertEquals(3, regulation.homeLine?.periods?.size)
        assertEquals("2OT", SportsFixtureText.periodLabel("ice-hockey", 5))
        assertEquals("3OT", SportsFixtureText.periodLabel("basketball", 7))
        val college = EspnScoreboard.parse(sample("espn-ncaaf-scoreboard"), league("college-football"), now).single()
        assertEquals("Final/OT", college.detail)
        assertEquals("35–34", college.score)
        assertEquals(listOf("7", "14", "0", "6", "8"), college.homeLine?.periods)
        assertEquals("OT", SportsFixtureText.periodLabel(college, 5))
    }

    @Test fun rugbyHalfTimeLineThatContradictsTheTriesIsDropped() {
        val games = EspnScoreboard.parse(sample("espn-urc-scoreboard"), league("urc"), now)
        val leinster = games.first { it.title.startsWith("Leinster") }
        assertEquals(FixtureStatus.FINAL, leinster.status)
        assertEquals("29–5", leinster.score)
        assertEquals(emptyList<String>(), leinster.homeLine?.periods)
        assertEquals(emptyList<String>(), leinster.awayLine?.periods)
        assertTrue(leinster.events.any { it.kind == FixtureEventKind.TRY && it.clock == "9'" })
        val bulls = games.first { it.title.startsWith("Bulls") }
        assertEquals("57–15", bulls.score)
        assertEquals(listOf("29", "28"), bulls.homeLine?.periods)
        assertEquals(listOf("10", "5"), bulls.awayLine?.periods)
        assertEquals("FT", bulls.detail)
    }

    @Test fun cricketResultAndPlayerOfTheMatch() {
        val summary = EspnSummary.parse(sample("espn-cricket-summary"), cricket)
        assertEquals(FixtureStatus.FINAL, summary.status)
        assertEquals("Australia won by 112 runs", summary.result)
        assertEquals(listOf(SummaryAward("playerOfTheMatch", "Player Of The Match", "Glenn Maxwell", FixtureSide.HOME),
            SummaryAward("playerOfTheSeries", "Player Of The Series", "Mitchell Starc", FixtureSide.HOME)), summary.awards)
        assertEquals(listOf(SummaryInnings(FixtureSide.HOME, 1, 278, 8, "50", null), SummaryInnings(FixtureSide.AWAY, 2, 166, 10, "39.1", 279)), summary.innings)
        val board = javaClass.getResourceAsStream("/sports/espn-cricket-scoreboard-real-20261008.json")!!.bufferedReader().use { it.readText() }
        val game = EspnScoreboard.parse(board, cricket, now).single()
        val detail = game.sportDetail as SportsDetail.Cricket
        assertEquals("Australia won by 112 runs", detail.result)
        assertEquals(detail, SportsDetails.decode(SportsDetails.encode(detail)))
    }

    @Test fun finishedBaseballHasNoCountAndNamesThePitchers() {
        val summary = EspnSummary.parse(JSONObject(sample("espn-mlb-summary")), "baseball")
        assertEquals(FixtureStatus.FINAL, summary.status)
        assertNull(summary.count)
        assertNull(summary.result)
        assertEquals(listOf("Peters struck out swinging.", "Benintendi struck out swinging.", "Murakami struck out swinging."), summary.lastPlays.map { it.text })
        assertEquals(listOf(SummaryAward("winningPitcher", "Winning Pitcher", "Gavin Williams", FixtureSide.HOME),
            SummaryAward("losingPitcher", "Losing Pitcher", "Sean Burke", FixtureSide.AWAY), SummaryAward("savingPitcher", "Saving Pitcher", "Cade Smith", FixtureSide.HOME)),
            summary.awards)
        assertEquals(listOf("0", "0", "0", "2", "0", "0", "0", "0"), summary.homeLine?.periods)
    }

    @Test fun livescoreRowsStuckPastTheGameLengthAreIgnoredEvenWhenFreshlyUpdated() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all")).associateBy { it.eventId }
        assertEquals(10, scores.size)
        fun merged(id: String, start: String, sport: String, league: String = "sdb-1") = SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away",
            FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)), scores, now).single()
        listOf("2517720", "2526143").forEach { id ->
            val stuck = merged(id, "2026-10-10T22:00:00Z", "ice-hockey")
            assertEquals(id, FixtureStatus.SCHEDULED, stuck.status)
            assertNull(id, stuck.score)
        }
        assertEquals(FixtureStatus.SCHEDULED, merged("2526140", "2026-10-10T01:05:00Z", "ice-hockey").status)
        val japan = merged("2589510", "2026-10-11T08:05:00Z", "basketball")
        assertEquals(FixtureStatus.LIVE, japan.status)
        assertEquals(2, japan.period)
        assertEquals("32–24", japan.score)
        val nbl = merged("2487910", "2026-10-11T06:00:00Z", "basketball", "nbl")
        assertEquals(FixtureStatus.FINAL, nbl.status)
        assertEquals("100–110", nbl.score)
        val second = merged("2492866", "2026-10-11T07:00:00Z", "soccer", "j1-league")
        assertEquals(FixtureStatus.LIVE, second.status)
        assertEquals(2, second.period)
        assertEquals("76'", second.clock)
        val half = merged("2604884", "2026-10-11T07:30:00Z", "soccer")
        assertEquals("HT", half.detail)
        assertEquals("0–0", half.score)
        val npb = merged("2616721", "2026-10-11T05:00:00Z", "baseball")
        assertEquals(FixtureStatus.FINAL, npb.status)
        assertNull(npb.score)
    }
}
