package com.nuvio.tv.core.iptv

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SportsPageTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val now = ZonedDateTime.of(2026, 10, 11, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = 60L * 60 * 1000

    private fun team(name: String, colour: String? = null) = FixtureTeam(name, colour = colour)

    private fun game(id: String, start: Long, home: String, away: String, status: FixtureStatus = FixtureStatus.SCHEDULED, league: String = "epl") =
        SportsFixture(id, league, "soccer", "$home v $away", team(home), team(away), start, status, score = if (status == FixtureStatus.SCHEDULED) null else "1–0")

    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name.json")!!.bufferedReader().use { it.readText() }

    @Test fun cardsPerRowFollowTheWidth() {
        assertEquals(4, SportsPage.perRow(922f))
        assertTrue(SportsPage.perRow(1880f) >= 8)
        assertEquals(1, SportsPage.perRow(120f))
        assertEquals(1, SportsPage.perRow(0f))
        val width = SportsPage.cardWidth(922f, 4)
        assertEquals(922f, width * 4 + SportsPage.CARD_GAP_DP * 3, .01f)
        assertTrue(width >= SportsPage.CARD_MIN_DP)
        assertTrue(SportsPage.cardWidth(1880f, SportsPage.perRow(1880f)) >= SportsPage.CARD_MIN_DP)
    }

    @Test fun thinDayFillsWithResultsTomorrowAndTeamsNext() {
        val fixtures = listOf(
            game("later", now + 7 * hour, "Arsenal", "Leeds United"),
            game("done1", now - 4 * hour, "Fulham", "Brentford", FixtureStatus.FINAL),
            game("done2", now - 3 * hour, "Everton", "Wolves", FixtureStatus.FINAL),
            game("tom1", now + 24 * hour, "Chelsea", "Spurs"),
            game("far", now + 50 * hour, "Liverpool", "Burnley"))
        val favourites = setOf("epl:Liverpool")
        val rows = SportsFixtureSections.group(fixtures, now, zone, favourites = favourites)
        assertEquals(listOf(FixtureSection.TODAY, FixtureSection.TOMORROW, FixtureSection.DAY, FixtureSection.FINISHED), rows.map { it.section })
        val shelves = SportsPage.shelves(rows, 5, favourites, now)
        val first = shelves.first()
        assertEquals(listOf("later"), first.row.fixtures.map { it.id })
        assertEquals(listOf(SportsFill.RESULTS, SportsFill.TOMORROW, SportsFill.TEAMS_NEXT), first.segments.map { it.kind })
        assertEquals(listOf("later", "done2", "done1", "tom1", "far"), first.fixtures.map { it.id })
        assertEquals(listOf(FixtureSection.TODAY), shelves.map { it.row.section })
    }

    @Test fun fullRowsAndTightCapacityStayAsTheyAre() {
        val fixtures = (1..5).map { game("t$it", now + it * hour, "Home $it", "Away $it") } + game("tom", now + 24 * hour, "Chelsea", "Spurs")
        val rows = SportsFixtureSections.group(fixtures, now, zone)
        val shelves = SportsPage.shelves(rows, 4, emptySet(), now)
        assertTrue(shelves.all { it.segments.isEmpty() || it.row.section == FixtureSection.TOMORROW })
        assertEquals(5, shelves.first().fixtures.size)
        val partial = SportsPage.shelves(SportsFixtureSections.group(listOf(game("one", now + hour, "A", "B")) +
            (1..6).map { game("r$it", now - it * hour, "R$it", "S$it", FixtureStatus.FINAL) }, now, zone), 4, emptySet(), now)
        assertEquals(4, partial.first().fixtures.size)
        assertEquals(listOf(FixtureSection.TODAY, FixtureSection.FINISHED), partial.map { it.row.section })
    }

    @Test fun liveRowTakesLaterGamesFirstAndCloseRowIsLeftAlone() {
        val live = game("live", now - hour, "A", "B", FixtureStatus.LIVE)
        val rows = SportsFixtureSections.group(listOf(live, game("later", now + 3 * hour, "C", "D")), now, zone)
        val shelves = SportsPage.shelves(rows, 4, emptySet(), now)
        assertEquals(listOf(SportsFill.LATER), shelves.first().segments.map { it.kind })
        assertEquals(1, shelves.size)
        val close = SportsPage.shelves(listOf(FixtureRow(FixtureSection.LIVE, listOf(live, game("x", now - hour, "E", "F", FixtureStatus.LIVE))),
            FixtureRow(FixtureSection.CLOSE, listOf(live))), 4, emptySet(), now)
        assertEquals(2, close.size)
        assertTrue(close[1].segments.isEmpty())
    }

    @Test fun teamsNextTakesEachFavouritesNextGameOnce() {
        val fixtures = listOf(game("a", now + 30 * hour, "Liverpool", "Burnley"), game("b", now + 80 * hour, "Liverpool", "Everton"),
            game("c", now + 40 * hour, "Arsenal", "Leeds United"), game("d", now - 2 * hour, "Arsenal", "Spurs", FixtureStatus.FINAL))
        assertEquals(listOf("a", "c"), SportsPage.teamsNext(fixtures, setOf("epl:Liverpool", "epl:Arsenal"), now).map { it.id })
        assertTrue(SportsPage.teamsNext(fixtures, emptySet(), now).isEmpty())
    }

    @Test fun formMarksKeepTheLastFive() {
        assertEquals(listOf(FormMark.LOSS, FormMark.WIN, FormMark.WIN, FormMark.WIN, FormMark.WIN), SportsPage.form("LWWWW"))
        assertEquals(listOf(FormMark.DRAW, FormMark.WIN, FormMark.LOSS, FormMark.DRAW, FormMark.DRAW), SportsPage.form("wDWLDD"))
        assertNull(SportsPage.form(null))
        assertNull(SportsPage.form("4-0-1"))
    }

    @Test fun lastMeetingFollowsTheResultNotTheScoreOrder() {
        val nrl = EspnSummary.parse(sample("espn-nrl-summary-real-20261008"), SportsLeagues.byId("nrl")!!)
        assertEquals(SportsMeeting(ZonedDateTime.of(2025, 6, 14, 7, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), "12", "8"),
            SportsPage.lastMeeting(nrl, FixtureSide.HOME))
        assertEquals("8", SportsPage.lastMeeting(nrl, FixtureSide.AWAY)?.first)
        val urc = EspnSummary.parse(sample("espn-urc-summary-real-20261008"), SportsLeagues.byId("urc")!!)
        val meeting = SportsPage.lastMeeting(urc, FixtureSide.HOME)!!
        assertEquals("10" to "15", meeting.first to meeting.second)
        assertNull(SportsPage.lastMeeting(null, FixtureSide.HOME))
        assertNull(SportsPage.lastMeeting(SportsSummary(null, "soccer", FixtureStatus.SCHEDULED,
            headToHead = listOf(SummaryMeeting(1L, "1", "0", "1-0", "X", null, FixtureSide.HOME))), FixtureSide.HOME))
    }

    @Test fun standingUsesRankAndPointsWhenPresent() {
        val epl = EspnSummary.parse(sample("espn-epl-summary-real-20261008"), SportsLeagues.byId("epl")!!)
        val home = SportsPage.standing(epl, FixtureSide.HOME)
        assertEquals("12", home?.points)
        val table = SummaryTable("League", listOf(SummaryStanding("Top", null, listOf("PTS" to "15")),
            SummaryStanding("Arsenal", FixtureSide.HOME, listOf("R" to "2", "PTS" to "12")), SummaryStanding("Leeds", FixtureSide.AWAY, listOf("PTS" to "9"))))
        val summary = SportsSummary(null, "soccer", FixtureStatus.SCHEDULED, tables = listOf(table))
        assertEquals(SportsStanding(2, "12"), SportsPage.standing(summary, FixtureSide.HOME))
        assertEquals(SportsStanding(null, "9"), SportsPage.standing(summary, FixtureSide.AWAY))
        assertNull(SportsPage.standing(null, FixtureSide.HOME))
    }

    @Test fun ruleColoursNeedBothTeamsAndStayVisible() {
        val card = 0xFF141414.toInt()
        val black = 0xFF000000.toInt()
        assertNull(SportsPage.ruleColours("e03a3e", null, card))
        assertNull(SportsPage.ruleColours(null, "1d428a", card))
        listOf(card, black).forEach { base ->
            val (a, b) = SportsPage.ruleColours("e03a3e", "000000", base)!!
            assertTrue(TeamColours.contrast(a, base) >= TeamColours.STRIPE_CONTRAST)
            assertTrue(TeamColours.contrast(b, base) >= TeamColours.STRIPE_CONTRAST)
        }
    }

    @Test fun shortListsDropNamesFromTheEnd() {
        assertEquals(listOf("USA Net, Universo, Peacock", "USA Net, Universo +1", "USA Net +2"), SportsPage.shortLists(listOf("USA Net", "Universo", " Peacock", "USA Net")))
        assertTrue(SportsPage.shortLists(listOf(" ")).isEmpty())
    }
}
