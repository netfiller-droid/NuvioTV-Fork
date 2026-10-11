package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsFormRankTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name.json")!!.bufferedReader().use { it.readText() }
    private val epl = SportsLeagues.byId("epl")!!

    @Test fun formReadsOldestFirstAndSurvivesTheCache() {
        val game = EspnScoreboard.parse(sample("espn-epl-scoreboard-real-20261010c"), epl, Instant.parse("2026-10-10T12:12:00Z").toEpochMilli())[0]
        assertEquals("WWWWL", game.home!!.form)
        assertEquals("DDLWD", game.away!!.form)
        assertEquals(listOf(FormMark.WIN, FormMark.WIN, FormMark.WIN, FormMark.WIN, FormMark.LOSS), SportsPage.form(game.home!!.form))
        val again = SportsFixtureCodec.decode(SportsFixtureCodec.encode(SportsCacheEntry(listOf(game), 1L)))!!.fixtures.single()
        assertEquals("WWWWL", again.home!!.form)
    }

    @Test fun tableRankComesFromTheStandingsNotTheRowOrder() {
        val summary = EspnSummary.parse(sample("espn-epl-summary-real-20261008"), epl)
        val rows = summary.tables.first().rows
        assertEquals(listOf(1, 2, 5), rows.map { it.rank })
    }
}
