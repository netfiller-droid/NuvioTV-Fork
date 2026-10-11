package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SportsScoreDaysTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val now = Instant.parse("2026-10-11T02:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 10, 11)
    private val hour = 60L * 60 * 1000

    private fun fixture(id: String, start: String, status: FixtureStatus, league: String = "afl", logo: String? = null,
        source: SportsService = SportsService.ESPN) =
        SportsFixture(id, league, "australian-football", "Game $id", null, null, Instant.parse(start).toEpochMilli(), status, leagueLogo = logo, source = source)

    @After fun clear() { SportsLeagues.custom = emptyList(); SportsDays.guideDays(0) }

    @Test fun stripRunsFromAFewPastDaysToTheEndOfTheFixturesWindow() {
        SportsDays.guideDays(0)
        assertEquals((-3..2).map { today.plusDays(it.toLong()) }, SportsScoreDays.strip(today))
        SportsDays.guideDays(7)
        assertEquals(11, SportsScoreDays.strip(today).size)
        assertEquals(today.plusDays(7), SportsScoreDays.strip(today).last())
        assertEquals(listOf(today), SportsScoreDays.strip(today, future = 1, past = 0))
        assertTrue(SportsScoreDays.offered(today.minusDays(3), now, sydney))
        assertFalse(SportsScoreDays.offered(today.minusDays(4), now, sydney))
        assertTrue(SportsScoreDays.offered(today.plusDays(7), now, sydney))
        assertFalse(SportsScoreDays.offered(today.plusDays(8), now, sydney))
    }

    @Test fun gamesBelongToTheirLocalDayWithLiveAndRunningLateGamesOnToday() {
        assertEquals(today, SportsScoreDays.dateOf(now, sydney))
        val lastNight = fixture("1", "2026-10-10T09:00:00Z", FixtureStatus.FINAL)
        val stillOn = fixture("2", "2026-10-10T12:30:00Z", FixtureStatus.LIVE)
        val notUpdated = fixture("3", "2026-10-10T12:45:00Z", FixtureStatus.SCHEDULED)
        val tonight = fixture("4", "2026-10-11T08:00:00Z", FixtureStatus.SCHEDULED)
        val earlyMorning = fixture("5", "2026-10-10T14:30:00Z", FixtureStatus.FINAL)
        val all = listOf(lastNight, stillOn, notUpdated, tonight, earlyMorning, lastNight)
        assertEquals(LocalDate.of(2026, 10, 10), SportsScoreDays.dayOf(lastNight, now, sydney))
        assertEquals(today, SportsScoreDays.dayOf(stillOn, now, sydney))
        assertEquals(today, SportsScoreDays.dayOf(notUpdated, now, sydney))
        assertEquals(today, SportsScoreDays.dayOf(earlyMorning, now, sydney))
        assertEquals(listOf("2", "3", "4", "5"), SportsScoreDays.on(all, today, now, sydney).map { it.id })
        assertEquals(listOf("1"), SportsScoreDays.on(all, today.minusDays(1), now, sydney).map { it.id })
        assertTrue(SportsScoreDays.on(all, today.plusDays(1), now, sydney).isEmpty())
    }

    @Test fun dayCountsSayLiveGamesResultsOrNothing() {
        val live = fixture("1", "2026-10-11T01:00:00Z", FixtureStatus.LIVE)
        val later = fixture("2", "2026-10-11T08:00:00Z", FixtureStatus.SCHEDULED)
        val done = fixture("3", "2026-10-10T08:00:00Z", FixtureStatus.FINAL)
        val postponed = fixture("4", "2026-10-10T09:00:00Z", FixtureStatus.SCHEDULED)
        assertEquals(ScoreDaySummary(ScoreDayKind.LIVE, 1), SportsScoreDays.summary(today, today, listOf(live, later)))
        assertEquals(ScoreDaySummary(ScoreDayKind.GAMES, 1), SportsScoreDays.summary(today, today, listOf(later)))
        assertEquals(ScoreDaySummary(ScoreDayKind.GAMES, 2), SportsScoreDays.summary(today.plusDays(2), today, listOf(later, later.copy(id = "9"))))
        assertEquals(ScoreDaySummary(ScoreDayKind.RESULTS, 1), SportsScoreDays.summary(today.minusDays(1), today, listOf(done, postponed)))
        assertEquals(ScoreDaySummary(ScoreDayKind.GAMES, 1), SportsScoreDays.summary(today.minusDays(1), today, listOf(postponed)))
        assertEquals(ScoreDaySummary(ScoreDayKind.NONE), SportsScoreDays.summary(today.minusDays(2), today, emptyList()))
        assertEquals(ScoreDaySummary(ScoreDayKind.UNKNOWN), SportsScoreDays.summary(today.minusDays(2), today, null))
    }

    @Test fun localDaysMapToEachServicesDates() {
        assertEquals(listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)),
            SportsScoreDays.serviceDates(LocalDate.of(2026, 10, 10), sydney, SportsService.ESPN))
        assertEquals(listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)),
            SportsScoreDays.serviceDates(LocalDate.of(2026, 10, 10), sydney, SportsService.THESPORTSDB))
        val london = ZoneId.of("Europe/London")
        assertEquals(listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)),
            SportsScoreDays.serviceDates(LocalDate.of(2026, 10, 10), london, SportsService.ESPN))
        val (from, until) = SportsScoreDays.bounds(today, sydney)
        assertEquals(24 * hour, until - from)
    }

    @Test fun settledPastDaysAreNotFetchedAgainButRecentOnesAre() {
        val zone = SportsDays.ESPN_ZONE
        val day = LocalDate.of(2026, 10, 9)
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val settled = SportsCacheEntry(emptyList(), end + 7 * hour)
        assertFalse(SportsScoreDays.due(settled, day, zone, end + 30 * hour))
        assertFalse(SportsScoreDays.due(settled.copy(failedAt = end + 29 * hour, failures = 3), day, zone, end + 30 * hour))
        val early = SportsCacheEntry(emptyList(), end + hour)
        assertFalse(SportsScoreDays.due(early, day, zone, end + hour + 60_000))
        assertTrue(SportsScoreDays.due(early, day, zone, end + 30 * hour))
        assertTrue(SportsScoreDays.due(null, day, zone, end))
        val failed = SportsCacheEntry(emptyList(), null, end + 10 * hour, 1)
        assertFalse(SportsScoreDays.due(failed, day, zone, end + 10 * hour + 60_000))
        assertTrue(SportsScoreDays.due(failed, day, zone, end + 11 * hour))
        val future = SportsCacheEntry(emptyList(), end - 48 * hour)
        assertFalse(SportsScoreDays.due(SportsCacheEntry(emptyList(), end + hour), day.plusDays(6), zone, end + 2 * hour))
        assertTrue(SportsScoreDays.due(future, day, zone, end))
        assertTrue(SportsScoreDays.due(SportsCacheEntry(emptyList(), end + 8 * hour), day, zone, end + 7 * hour))
    }

    @Test fun crestsAreRememberedPerLeaguePreferringEspnAndFollowTheLogoSetting() {
        val espn = "https://a.espncdn.com/i/teamlogos/leagues/500/afl.png"
        val sdb = "https://r2.thesportsdb.com/images/media/league/badge/afl.png"
        val found = SportsLeagueCrests.collect(listOf(
            fixture("1", "2026-10-10T09:00:00Z", FixtureStatus.FINAL, logo = sdb, source = SportsService.THESPORTSDB),
            fixture("2", "2026-10-10T09:00:00Z", FixtureStatus.FINAL, logo = espn),
            fixture("3", "2026-10-10T09:00:00Z", FixtureStatus.FINAL, league = "nrl", logo = sdb, source = SportsService.THESPORTSDB),
            fixture("4", "2026-10-10T09:00:00Z", FixtureStatus.FINAL, league = "epl")))
        assertEquals(mapOf("afl" to SportsCrest(espn, SportsService.ESPN), "nrl" to SportsCrest(sdb, SportsService.THESPORTSDB)), found)
        val kept = SportsLeagueCrests.merge(found, mapOf("afl" to SportsCrest(sdb, SportsService.THESPORTSDB)))
        assertEquals(espn, kept["afl"]?.url)
        val upgraded = SportsLeagueCrests.merge(kept, mapOf("nrl" to SportsCrest("https://a.espncdn.com/nrl.png", SportsService.ESPN)))
        assertEquals(SportsService.ESPN, upgraded["nrl"]?.service)
        assertEquals(found, SportsLeagueCrests.merge(found, found))
        assertEquals(mapOf("afl" to espn), SportsLeagueCrests.shown(found, SportsLogos.ESPN))
        assertEquals(mapOf("afl" to espn, "nrl" to sdb), SportsLeagueCrests.shown(found, SportsLogos.ALL))
        assertTrue(SportsLeagueCrests.shown(found, SportsLogos.OFF).isEmpty())
        assertEquals(found, SportsLeagueCrests.decode(SportsLeagueCrests.encode(found)))
        assertTrue(SportsLeagueCrests.decode(null).isEmpty())
        assertTrue(SportsLeagueCrests.decode("not json").isEmpty())
        assertTrue(SportsLeagueCrests.decode("""{"afl":{"url":"javascript:x"},"nrl":"x"}""").isEmpty())
        val many = (1..SportsLeagueCrests.MAX + 20).associate { "l$it" to SportsCrest("https://x.test/$it.png", SportsService.ESPN) }
        val bounded = SportsLeagueCrests.merge(emptyMap(), many)
        assertEquals(SportsLeagueCrests.MAX, bounded.size)
        assertTrue("l${SportsLeagueCrests.MAX + 20}" in bounded)
    }

    @Test fun pickerGroupsLeaguesBySportWithCountsAndMergesAddedLeagues() {
        val netball = SportsDbLeagues.league(SportsDbLeague("4459", "Suncorp Super Netball", "Netball", "Super Netball"))
        val extraFootball = SportsDbLeagues.league(SportsDbLeague("9999", "Aaa Amateur League", "Soccer"))
        val offered = SportsLeagues.ALL + netball + extraFootball
        val sports = SportsLeaguePicker.sports(offered, setOf("afl", "epl", netball.id, "missing"))
        assertEquals(SportsLeagues.ALL.map { it.sport }.distinct() + netball.sport, sports.map { it.sport })
        assertEquals(offered.size, sports.sumOf { it.leagues.size })
        val football = sports.first { it.sport == "soccer" }
        assertEquals(extraFootball.id, football.leagues.last().id)
        assertEquals(1, football.chosen)
        assertEquals(1, sports.first { it.sport == netball.sport }.chosen)
        assertEquals(1, sports.first { it.sport == "australian-football" }.chosen)
        assertEquals(listOf("afl", "epl", netball.id), SportsLeaguePicker.saved(offered, setOf(netball.id, "epl", "afl", "gone")))
        assertEquals(setOf("afl"), SportsLeaguePicker.toggle(setOf("afl", "epl"), "epl"))
        assertEquals(setOf("afl", "epl"), SportsLeaguePicker.toggle(setOf("afl"), "epl"))
    }

    @Test fun pickerColumnsFollowTheAvailableWidth() {
        assertEquals(3, SportsLeaguePicker.columns(532f))
        assertEquals(8, SportsLeaguePicker.columns(1452f))
        assertEquals(1, SportsLeaguePicker.columns(100f))
        assertEquals(1, SportsLeaguePicker.columns(0f))
        assertEquals(2, SportsLeaguePicker.columns(348f))
    }

    @Test fun pickerSourceLinesAndMonograms() {
        val afl = SportsLeagues.byId("afl")!!
        val bigBash = SportsLeagues.byId("big-bash")!!
        val f1 = SportsLeagues.ALL.first { it.espn == "racing/f1" }
        assertEquals(LeagueSource.ESPN, SportsLeaguePicker.source(afl, false))
        assertEquals(LeagueSource.BOTH, SportsLeaguePicker.source(afl, true))
        assertEquals(LeagueSource.NEEDS_KEY, SportsLeaguePicker.source(bigBash, false))
        assertEquals(LeagueSource.THESPORTSDB, SportsLeaguePicker.source(bigBash, true))
        assertEquals(LeagueSource.ESPN, SportsLeaguePicker.source(f1.copy(sportsDb = null), true))
        assertEquals("AFL", SportsLeaguePicker.monogram("AFL"))
        assertEquals("PL", SportsLeaguePicker.monogram("Premier League"))
        assertEquals("UEFA", SportsLeaguePicker.monogram("UEFA Champions League"))
        assertEquals("ALM", SportsLeaguePicker.monogram("A-League Men"))
        assertEquals("F1", SportsLeaguePicker.monogram("Formula 1"))
        assertEquals("IND", SportsLeaguePicker.monogram("IndyCar"))
        assertEquals("?", SportsLeaguePicker.monogram(" "))
    }
}
