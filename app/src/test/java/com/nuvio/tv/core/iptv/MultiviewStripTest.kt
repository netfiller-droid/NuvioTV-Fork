package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class MultiviewStripTest {
    private val sydney = FixtureTeam("Sydney FC", shortName = "Sydney", abbreviation = "SYD", colour = "6CACE4")
    private val victory = FixtureTeam("Melbourne Victory", shortName = "Melbourne Victory Football Club", abbreviation = "MVC")
    private val celtics = FixtureTeam("Boston Celtics", shortName = "Celtics", abbreviation = "BOS")
    private val knicks = FixtureTeam("New York Knicks", shortName = "Knicks", abbreviation = "NY")

    private fun soccer(status: FixtureStatus, start: Long = 0L, score: String? = "2–1", clock: String? = "67'") =
        SportsFixture("1", "aus.1", "soccer", "Sydney v Victory", sydney, victory, start, status, score = score, clock = clock, period = 2)

    @Test fun stripScalesWithTheScreenSoBothUiWidthsLookTheSame() {
        for (layout in MultiviewLayout.entries) for (slots in 1..4) {
            val small = multiviewGeometry(layout, slots, 0, 960f, 540f, 2f)
            val large = multiviewGeometry(layout, slots, 0, 1920f, 1080f, 4f)
            small.zip(large).forEach { (a, b) ->
                val x = MultiviewStrip.size(MultiviewStrip.picture(a.width, a.height).first, 960f)
                val y = MultiviewStrip.size(MultiviewStrip.picture(b.width, b.height).first, 1920f)
                assertEquals(x.compact, y.compact)
                assertEquals(x.score * 2f, y.score, .6f)
                assertEquals(x.line * 2f, y.line, 1.2f)
                assertTrue(x.line + x.labelLine + x.progress < a.height * .25f)
            }
        }
    }

    @Test fun gridTilesAreLegibleAndSmallTilesUseCodes() {
        val grid = MultiviewStrip.size(479f, 960f)
        assertFalse(grid.compact)
        assertEquals(17f, grid.score, .2f)
        assertTrue(grid.line < 270f * .12f)
        val small = MultiviewStrip.size(318f, 960f)
        assertTrue(small.compact)
        assertTrue(small.score >= 13f && small.name >= 11f && small.label >= 10f)
        val single = MultiviewStrip.size(960f, 960f)
        assertTrue(single.score > grid.score && single.line < 540f * .1f)
    }

    @Test fun pictureFitsSixteenByNineInsideTallTiles() {
        assertEquals(479f to 479f * 9f / 16f, MultiviewStrip.picture(479f, 540f))
        val (width, height) = MultiviewStrip.picture(960f, 300f)
        assertEquals(300f * 16f / 9f, width, .01f)
        assertEquals(300f, height, .01f)
    }

    @Test fun showsLiveGamesAndGamesAboutToStart() {
        val now = 10L * 60 * 60 * 1000
        assertTrue(MultiviewStrip.shows(soccer(FixtureStatus.LIVE), now))
        assertTrue(MultiviewStrip.shows(soccer(FixtureStatus.SCHEDULED, now + 45 * 60 * 1000), now))
        assertTrue(MultiviewStrip.shows(soccer(FixtureStatus.SCHEDULED, now - 5 * 60 * 1000), now))
        assertFalse(MultiviewStrip.shows(soccer(FixtureStatus.SCHEDULED, now + 2 * 60 * 60 * 1000), now))
        assertFalse(MultiviewStrip.shows(soccer(FixtureStatus.FINAL), now))
    }

    @Test fun sidesFollowTheSportsOrderAndMarkTheTeamBehind() {
        val home = MultiviewStrip.sides(soccer(FixtureStatus.LIVE))!!
        assertEquals(listOf(FixtureSide.HOME, FixtureSide.AWAY), home.map { it.side })
        assertEquals(listOf("2", "1"), home.map { it.score })
        assertEquals(listOf(false, true), home.map { it.behind })
        val nba = SportsFixture("2", "nba", "basketball", "Knicks at Celtics", celtics, knicks, 0L, FixtureStatus.LIVE, score = "88–90")
        val away = MultiviewStrip.sides(nba)!!
        assertEquals(listOf(FixtureSide.AWAY, FixtureSide.HOME), away.map { it.side })
        assertEquals(listOf("90", "88"), away.map { it.score })
        assertEquals(listOf(false, true), away.map { it.behind })
        assertEquals(listOf(null, null), MultiviewStrip.sides(soccer(FixtureStatus.SCHEDULED))!!.map { it.score })
        assertNull(MultiviewStrip.sides(SportsFixture("3", "f1", "motorsport", "Grand Prix", null, null, 0L, FixtureStatus.LIVE)))
    }

    @Test fun teamNamesAreShortOrCodes() {
        assertEquals("Sydney", MultiviewStrip.teamText(sydney, compact = false))
        assertEquals("SYD", MultiviewStrip.teamText(sydney, compact = true))
        assertEquals("MVC", MultiviewStrip.teamText(victory, compact = false))
        assertEquals("Knicks", MultiviewStrip.teamText(knicks, compact = false))
        assertEquals("Wanderers", MultiviewStrip.teamText(FixtureTeam("Western Sydney Wanderers", shortName = "Wanderers"), compact = true))
    }

    @Test fun clockOnlyWhenLiveAndScoresShown() {
        assertEquals("67'", MultiviewStrip.liveState(soccer(FixtureStatus.LIVE), hidden = false))
        assertNull(MultiviewStrip.liveState(soccer(FixtureStatus.LIVE), hidden = true))
        assertNull(MultiviewStrip.liveState(soccer(FixtureStatus.SCHEDULED), hidden = false))
        val race = SportsFixture("3", "f1", "motorsport", "Japanese Grand Prix", null, null, 0L, FixtureStatus.LIVE)
        assertEquals("Japanese Grand Prix", MultiviewStrip.eventText(race, hidden = true))
    }

    @Test fun labelsChoice() {
        assertEquals(MultiviewLabels.ALWAYS, MultiviewLabels.parse(null))
        assertEquals(MultiviewLabels.ALWAYS, MultiviewLabels.parse("nonsense"))
        assertEquals(MultiviewLabels.OFF, MultiviewLabels.parse("OFF"))
        assertTrue(MultiviewLabels.ALWAYS.shown(false))
        assertTrue(MultiviewLabels.FOCUSED.shown(true))
        assertFalse(MultiviewLabels.FOCUSED.shown(false))
        assertFalse(MultiviewLabels.OFF.shown(true))
    }
}
