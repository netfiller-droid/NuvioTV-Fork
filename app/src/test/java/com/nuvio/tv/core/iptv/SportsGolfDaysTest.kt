package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SportsGolfDaysTest {
    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val hour = 60L * 60 * 1000
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun golf(file: String, now: Long) =
        EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-pga-scoreboard-real-$file.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("pga")!!, now).single()

    @Test fun liveLaterRoundsStayInTheWindowAfterTheFirstDay() {
        val now = millis("2026-10-10T19:00:00Z")
        val round = golf("20261010", now)
        assertEquals(FixtureStatus.LIVE, round.status)
        assertEquals(millis("2026-10-08T04:00:00Z"), round.startMillis)
        val (from, until) = SportsDays.window(now, brisbane)
        assertTrue(SportsFixtureTimes.shown(round, from - 12 * hour, until, now))
        assertTrue(SportsFixtureTimes.shown(round.copy(status = FixtureStatus.SCHEDULED), from - 12 * hour, until, now))
        assertEquals(LocalDate.of(2026, 10, 11), SportsScoreDays.dayOf(round, now, brisbane))
        assertEquals(listOf(FixtureSection.LIVE), SportsFixtureSections.group(listOf(round), now, brisbane).map { it.section })
        val game = SportsFixture("1", "afl", "australian-football", "Old game", null, null, from - 13 * hour, FixtureStatus.FINAL)
        assertFalse(SportsFixtureTimes.shown(game, from - 12 * hour, until, now))
        assertTrue(SportsFixtureTimes.shown(game.copy(status = FixtureStatus.LIVE), from - 12 * hour, until, now))
        assertFalse(SportsFixtureTimes.shown(game.copy(startMillis = until), from - 12 * hour, until, now))
    }

    @Test fun aFinishedTournamentBelongsToItsLastDayWithCatchUp() {
        val now = millis("2026-10-11T23:30:00Z")
        val final = golf("20261011d", now)
        assertEquals(FixtureStatus.FINAL, final.status)
        assertEquals(millis("2026-10-11T22:00:00Z"), SportsFixtureTimes.end(final))
        assertEquals(SportsFixtureTimes.end(final), SportsCatchup.end(final))
        val today = LocalDate.of(2026, 10, 12)
        assertEquals(today, SportsScoreDays.dayOf(final, now, brisbane))
        assertEquals(listOf(final.key), SportsScoreDays.on(listOf(final), today, now, brisbane).map { it.key })
        val rows = SportsFixtureSections.group(listOf(final), now, brisbane)
        assertEquals(listOf(FixtureSection.FINISHED), rows.map { it.section })
        assertEquals(listOf(final.key), SportsCatchup.recent(listOf(final), now).map { it.key })
        val (from, until) = SportsDays.window(now, brisbane)
        assertTrue(SportsFixtureTimes.shown(final, from - 12 * hour, until, now))
        val nextDay = now + 24 * hour
        assertEquals(today, SportsScoreDays.dayOf(final, nextDay, brisbane))
        assertTrue(SportsFixtureSections.group(listOf(final), nextDay, brisbane).isEmpty())
        assertTrue(SportsCatchup.recent(listOf(final), nextDay).isEmpty())
        val london = ZoneId.of("Europe/London")
        assertEquals(LocalDate.of(2026, 10, 11), SportsScoreDays.dayOf(final, nextDay, london))
    }

    @Test fun otherSportsKeepTheirKickOff() {
        val start = millis("2026-10-10T09:00:00Z")
        val game = SportsFixture("1", "afl", "australian-football", "Game", null, null, start, FixtureStatus.FINAL)
        assertEquals(start, SportsFixtureTimes.placed(game, start + 30 * hour))
        assertEquals(start + 180 * 60_000L, SportsFixtureTimes.end(game))
        val detail = SportsDetail.Golf("Open")
        val undated = SportsFixture("2", "pga", "golf", "Open", null, null, start, FixtureStatus.SCHEDULED, sportDetail = detail)
        assertEquals(start + 3 * 24 * hour + SportsFixtureTimes.GOLF_FINISH_MILLIS, SportsFixtureTimes.end(undated))
        assertEquals(start, SportsFixtureTimes.placed(undated, start - hour))
    }
}
