package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsPendingRecordsTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_791_000_000_000L
    private val game = SportsFixture("740100", "epl", "soccer", "Arsenal v Leeds United", FixtureTeam("Arsenal"), FixtureTeam("Leeds United"), now + 24 * hour,
        FixtureStatus.SCHEDULED)
    private val custom = SportsFixture("55", "sdb-4328", "soccer", "Hull v Leeds", FixtureTeam("Hull"), FixtureTeam("Leeds"), now + 2 * hour,
        FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)

    private fun other(index: Int, start: Long) = SportsFixture("o$index", "mlb", "baseball", "Game $index", FixtureTeam("Home $index"), FixtureTeam("Away $index"),
        start, FixtureStatus.SCHEDULED)

    @Test fun leaguesComeFromTheFixtureKeys() {
        val all = listOf(SportsPendingRecords.of(1, game), SportsPendingRecords.of(1, custom), SportsPendingRecords.of(2, game))
        assertEquals(setOf("epl", "sdb-4328"), SportsPendingRecords.leagues(all))
        assertTrue(SportsPendingRecords.leagues(emptyList()).isEmpty())
    }

    @Test fun updateFollowsKickoffChangesAndDropsEndedGames() {
        val all = listOf(SportsPendingRecords.of(1, game), SportsPendingRecords.of(1, custom))
        assertEquals(all.sortedBy { it.startMillis }, SportsPendingRecords.update(all, emptyList(), now))
        val moved = game.copy(startMillis = now + 30 * hour)
        assertEquals(now + 30 * hour, SportsPendingRecords.update(all, listOf(moved), now).first { it.key == game.key }.startMillis)
        assertEquals(listOf(custom.key), SportsPendingRecords.update(all, listOf(game.copy(status = FixtureStatus.FINAL)), now).map { it.key })
        assertEquals(listOf(game.key), SportsPendingRecords.update(all, emptyList(), custom.startMillis + SportsRefresh.durationMillis(custom)).map { it.key })
        val early = custom.copy(startMillis = now - 4 * hour, status = FixtureStatus.LIVE)
        assertEquals(listOf(game.key), SportsPendingRecords.update(all, listOf(early), now).map { it.key })
    }

    @Test fun watchedIsBoundedToTheProfileAndSoonestGames() {
        val fixtures = (1..30).map { other(it, now + it * hour) }
        val all = fixtures.reversed().map { SportsPendingRecords.of(1, it) } + SportsPendingRecords.of(2, game)
        val watched = SportsPendingRecords.watched(all, 1, fixtures + game, now)
        assertEquals(SportsPendingRecords.MAX_WATCHED, watched.size)
        assertEquals(fixtures.take(SportsPendingRecords.MAX_WATCHED), watched)
        assertEquals(listOf(game), SportsPendingRecords.watched(all, 2, fixtures + game, now))
        assertTrue(SportsPendingRecords.watched(all, 2, listOf(game.copy(status = FixtureStatus.FINAL)), now).isEmpty())
    }

    @Test fun claimTakesEachRuleOnce() {
        val all = listOf(SportsPendingRecords.of(1, game), SportsPendingRecords.of(1, custom), SportsPendingRecords.of(2, game))
        val (kept, taken) = SportsPendingRecords.claim(all, 1, listOf(game, game, other(1, now)))
        assertEquals(listOf(game), taken)
        assertEquals(listOf(SportsPendingRecords.of(1, custom), SportsPendingRecords.of(2, game)), kept)
        val (again, none) = SportsPendingRecords.claim(kept, 1, listOf(game))
        assertTrue(none.isEmpty())
        assertEquals(kept, again)
    }

    @Test fun recordsFromALeagueThatIsNowBuiltInFindTheirGame() {
        val stored = SportsPendingRecord(1, "sdb-4430:sdb-2290100", now + 2 * hour, now + 5 * hour)
        val migrated = SportsPendingRecords.migrate(stored)
        assertEquals("top-14:sdb-2290100", migrated.key)
        assertEquals(setOf("top-14"), SportsPendingRecords.leagues(listOf(migrated)))
        assertEquals(game.key, SportsPendingRecords.migrate(SportsPendingRecords.of(1, game)).key)
        val espn = SportsFixture("401900001", "top-14", "rugby", "Toulouse v Toulon", FixtureTeam("Toulouse"), FixtureTeam("Toulon"), now + 2 * hour + 5 * 60_000,
            FixtureStatus.SCHEDULED)
        val updated = SportsPendingRecords.update(listOf(migrated), listOf(espn), now).single()
        assertEquals(espn.key, updated.key)
        assertEquals(listOf(espn), SportsPendingRecords.ready(listOf(updated), 1, listOf(espn), { true }, now))
        val twin = espn.copy(id = "401900002", title = "Clermont v Pau", home = FixtureTeam("Clermont"), away = FixtureTeam("Pau"))
        assertEquals(listOf(migrated), SportsPendingRecords.update(listOf(migrated), listOf(espn, twin), now))
        val sameSource = espn.copy(id = "2290101", source = SportsService.THESPORTSDB)
        assertEquals(listOf(migrated), SportsPendingRecords.update(listOf(migrated), listOf(sameSource), now))
        assertEquals(listOf(migrated), SportsPendingRecords.update(listOf(migrated), listOf(espn.copy(startMillis = now + 4 * hour)), now))
        assertTrue(SportsPendingRecords.update(listOf(migrated), listOf(espn.copy(status = FixtureStatus.FINAL)), now).isEmpty())
    }

    @Test fun recordsKeepTheirTeamsSoAnotherSourceCanTakeOver() {
        val sportsDb = SportsFixture("2290100", "top-14", "rugby", "Stade Toulousain v RC Toulonnais", FixtureTeam("Toulouse"), FixtureTeam("Toulon"), now + 2 * hour,
            FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)
        val record = SportsPendingRecords.of(1, sportsDb)
        assertEquals("Toulouse" to "Toulon", record.home to record.away)
        assertEquals(record, SportsPendingRecords.decode(SportsPendingRecords.encode(record)))
        assertEquals(SportsPendingRecord(1, "epl:740100", 1, 2), SportsPendingRecords.decode("1|1|2|epl:740100"))
        assertEquals(SportsPendingRecord(1, "epl:740100", 1, 2), SportsPendingRecords.decode("1|1|2|epl:740100\t\tLeeds"))
        val espn = sportsDb.copy(id = "401900001", source = SportsService.ESPN, startMillis = now + 2 * hour + 10 * 60_000)
        val twin = espn.copy(id = "401900002", home = FixtureTeam("Clermont"), away = FixtureTeam("Pau"))
        assertEquals(espn.key, SportsPendingRecords.update(listOf(record), listOf(twin, espn), now).single().key)
        assertEquals(espn.key, SportsPendingRecords.update(listOf(record), listOf(twin, espn.copy(home = espn.away, away = espn.home)), now).single().key)
        assertEquals(listOf(record), SportsPendingRecords.update(listOf(record), listOf(twin), now))
    }
}
