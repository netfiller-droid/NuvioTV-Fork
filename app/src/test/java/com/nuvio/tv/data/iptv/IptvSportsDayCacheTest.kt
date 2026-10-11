package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsCrest
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class IptvSportsDayCacheTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val sydney = ZoneId.of("Australia/Sydney")
    private val now = Instant.parse("2026-09-28T10:00:00Z").toEpochMilli()
    private val day = LocalDate.of(2026, 9, 26)
    private val body = javaClass.getResource("/sports/espn-afl-scoreboard.json")!!.readText()

    private fun withCache(status: Int = 200, block: suspend (IptvSportsDayCache, IptvSportsFixturesStore, MockWebServer, AtomicInteger) -> Unit) {
        val directory = Files.createTempDirectory("sports-days").toFile()
        val server = MockWebServer()
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.incrementAndGet()
                return if (status == 200) MockResponse().setBody(body) else MockResponse().setResponseCode(status)
            }
        }
        try {
            val store = IptvSportsFixturesStore(File(directory, "cache"))
            runBlocking { block(IptvSportsDayCache(IptvSportsFixturesClient(espnBase = server.url("/sports/")), store), store, server, calls) }
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test fun aPastDayIsFetchedOnceAndKeptOnceItsResultsAreSettled() = withCache { cache, _, server, calls ->
        val first = cache.load(listOf(afl), null, day, sydney, now)
        assertEquals(listOf("1133711"), first.fixtures.map { it.id })
        assertEquals(FixtureStatus.FINAL, first.fixtures.single().status)
        assertFalse(first.failed)
        assertEquals(2, calls.get())
        val paths = listOfNotNull(server.takeRequest().path, server.takeRequest().path).sorted()
        assertEquals(listOf("/sports/australian-football/afl/scoreboard?dates=20260925", "/sports/australian-football/afl/scoreboard?dates=20260926"), paths)
        val again = cache.load(listOf(afl), null, day, sydney, now + 3 * 60 * 60 * 1000)
        assertEquals(first.fixtures, again.fixtures)
        assertEquals(2, calls.get())
        assertTrue(cache.load(listOf(afl), null, day.minusDays(1), sydney, now).fixtures.isEmpty())
        assertEquals(3, calls.get())
    }

    @Test fun peekingUsesOnlyWhatIsAlreadyKnown() = withCache { cache, store, _, calls ->
        assertTrue(cache.load(listOf(afl), null, day, sydney, now, fetch = false).fixtures.isEmpty())
        assertEquals(0, calls.get())
        val fixture = SportsFixture("7", "afl", "australian-football", "Stored game", null, null, Instant.parse("2026-09-26T05:00:00Z").toEpochMilli(),
            FixtureStatus.FINAL)
        store.write(IptvSportsFixturesStore.key(SportsService.ESPN, afl, LocalDate.of(2026, 9, 26)), SportsCacheEntry(listOf(fixture), now - 60_000))
        assertEquals(listOf("Stored game"), cache.load(listOf(afl), null, day, sydney, now, fetch = false).fixtures.map { it.title })
        assertEquals(0, calls.get())
    }

    @Test fun failuresAreReportedAndBackedOff() = withCache(status = 500) { cache, _, _, calls ->
        val result = cache.load(listOf(afl), null, day, sydney, now)
        assertTrue(result.failed)
        assertTrue(result.fixtures.isEmpty())
        assertEquals(2, calls.get())
        assertTrue(cache.load(listOf(afl), null, day, sydney, now + 30_000).failed)
        assertEquals(2, calls.get())
    }

    @Test fun leaguesThatNeedAKeyAreSkippedWithoutOne() = withCache { cache, _, _, calls ->
        val result = cache.load(listOf(SportsLeagues.byId("big-bash")!!), null, day, sydney, now)
        assertTrue(result.fixtures.isEmpty())
        assertFalse(result.failed)
        assertEquals(0, calls.get())
    }

    @Test fun crestsAreKeptAcrossRestartsAndOnlyWrittenWhenTheyChange() {
        val directory = Files.createTempDirectory("sports-crests").toFile()
        try {
            val store = IptvSportsFixturesStore(directory)
            val logo = "https://a.espncdn.com/i/teamlogos/leagues/500/afl.png"
            val fixture = SportsFixture("1", "afl", "australian-football", "Game", null, null, now, FixtureStatus.FINAL, leagueLogo = logo)
            val crests = IptvSportsCrests(store)
            assertTrue(crests.known().isEmpty())
            crests.keep(listOf(fixture))
            val file = File(directory, "meta-crests.json")
            assertTrue(file.isFile)
            file.setLastModified(1_000L)
            crests.keep(listOf(fixture, fixture.copy(id = "2", leagueLogo = null)))
            assertEquals(1_000L, file.lastModified())
            assertEquals(mapOf("afl" to SportsCrest(logo, SportsService.ESPN)), IptvSportsCrests(store).known())
            store.prune(emptySet())
            assertTrue(file.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
