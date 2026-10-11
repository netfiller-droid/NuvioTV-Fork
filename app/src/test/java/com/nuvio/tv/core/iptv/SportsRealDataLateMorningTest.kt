package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataLateMorningTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261011c.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T01:03:00Z")

    private fun withPlays(root: JSONObject, count: Int): JSONObject {
        val plays = root.getJSONArray("plays")
        return JSONObject(root.toString()).put("plays", JSONArray((0 until count).map { plays.getJSONObject(it) }))
    }

    @Test fun baseballFinishedAtBatKeepsTheRunnerAndClearsTheCount() {
        val game = EspnScoreboard.parse(sample("mlb-scoreboard"), league("mlb"), now).single()
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("Bot 4th", game.detail)
        assertEquals(SportsDetail.Baseball(4, InningHalf.BOTTOM, 0, 0, 0, true, false, false), game.sportDetail)
        assertEquals(SportsBugText("CHW 0–0 CLE", "▼4", "0 outs · 0–0"), SportsFixtureText.bug(game))
        assertEquals("Sean Burke pitches to Jose Ramirez", game.situation?.lastPlay)
        val root = JSONObject(sample("mlb-summary"))
        val summary = EspnSummary.parse(root, "baseball")
        assertEquals("Bot 4th", summary.detail)
        assertEquals(SummaryCount(0, 0, 0, true, false, false, 4, true, "Sean Burke", null), summary.count)
        assertEquals(listOf("Kwan singled to right.", "Bottom of the 4th inning", "Middle of the 4th inning"), summary.lastPlays.map { it.text })
        assertEquals(listOf(0.583f, 0.635f), summary.winProbability.takeLast(2))
        val beforeEnd = EspnSummary.parse(withPlays(root, 12), "baseball").count!!
        assertEquals(1, beforeEnd.strikes)
        assertEquals("Steven Kwan", beforeEnd.batter)
        val next = JSONObject(root.toString())
        next.getJSONObject("situation").put("balls", 1).put("batter", JSONObject().put("playerId", 32801).put("athlete", JSONObject().put("displayName", "Jose Ramirez")))
        val moved = EspnSummary.parse(next, "baseball").count!!
        assertEquals(1, moved.balls)
        assertEquals(1, moved.strikes)
        assertEquals("Jose Ramirez", moved.batter)
        val middle = withPlays(root, 7)
        middle.getJSONObject("header").getJSONArray("competitions").getJSONObject(0).getJSONObject("status").put("periodPrefix", "Mid")
        assertEquals(SummaryCount(null, null, null, false, false, false, 4, false, null, null), EspnSummary.parse(middle, "baseball").count)
    }

    @Test fun basketballEndOfQuarterHidesTheZeroClock() {
        val games = EspnScoreboard.parse(sample("nba-scoreboard"), league("nba"), now)
        val ended = games.first { it.id == "401898396" }
        assertEquals(FixtureStatus.LIVE, ended.status)
        assertEquals("0.0", ended.clock)
        assertEquals("End of 3rd", SportsFixtureText.periodClock(ended))
        assertEquals(SportsBugText("ATL 96–86 IND", "End of 3rd"), SportsFixtureText.bug(ended))
        assertEquals("End of the 3rd Quarter", ended.situation?.lastPlay)
        assertEquals("End of 3rd", SportsFixtureText.periodClock(ended.copy(clock = "00:00")))
        assertEquals("Q3 · 0.4", SportsFixtureText.periodClock(ended.copy(clock = "0.4")))
        val raptors = games.first { it.id == "401902645" }
        assertEquals("Q4 · 5:40", SportsFixtureText.periodClock(raptors))
        assertEquals(FixtureLine("95", listOf("24", "33", "32", "6")), raptors.homeLine)
        assertEquals(FixtureLine("89", listOf("18", "27", "27", "17")), raptors.awayLine)
        assertEquals(FixtureSituation(null, null, "misses free throw 1 of 2", 78), raptors.situation)
        val game = EspnSummary.parse(sample("nba-summary"), league("nba"))
        assertEquals(4, game.period)
        assertEquals("5:55", game.clock)
        assertEquals(SummaryLine("95", listOf("24", "33", "32", "6")), game.homeLine)
        assertEquals(SummaryPlay("Trey Jemison III defensive rebound", 4, "6:02"), game.lastPlays.first())
        assertEquals(millis("2026-10-11T01:01:38Z"), game.moments.last().wallclockMillis)
    }

    @Test fun collegeFootballHalfTimeAfterALateTouchdown() {
        val games = EspnScoreboard.parse(sample("ncaaf-scoreboard"), league("college-football"), now)
        val half = games.first { it.id == "401856715" }
        assertEquals(FixtureStatus.LIVE, half.status)
        assertEquals("Halftime", half.detail)
        assertEquals(FixtureSituation(null, null, "Extra Point Good (A. Zouagui KICK)", null), half.situation)
        assertEquals(SportsBugText("LSU 17–21 UK", "Halftime"), SportsFixtureText.bug(half))
        val drive = games.first { it.id == "401856712" }
        assertEquals(FixtureSituation("3rd & 6 · UGA 18", FixtureSide.AWAY,
            "Shotgun #20 D.Phillips Jr. rush middle for 0 yards to the UGA18 (#1 D.Thompkins; #18 B.Hubbard)", 44), drive.situation)
        val game = EspnSummary.parse(sample("ncaaf-summary"), league("college-football"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("Halftime", game.detail)
        assertEquals(SummaryLine("21", listOf("7", "14")), game.homeLine)
        assertEquals(listOf(SummaryPlay("End of 2nd quarter.", 2, "0:00"), SummaryPlay("K. Minchey run for 1 yd, for a TD (A. Zouagui KICK)", 2, "0:03"),
            SummaryPlay("Timeout LSU, clock 00:03", 2, "0:03")), game.lastPlays)
        val touchdown = game.moments.last()
        assertEquals(MomentKind.TOUCHDOWN, touchdown.kind)
        assertEquals(FixtureSide.HOME, touchdown.side)
        assertEquals(millis("2026-10-11T00:40:22Z"), touchdown.wallclockMillis)
    }

    @Test fun hockeyFightingMajorsAndTheSecondIntermission() {
        val games = EspnScoreboard.parse(sample("nhl-scoreboard"), league("nhl"), now)
        assertEquals("End of 2nd", SportsFixtureText.periodClock(games.first { it.id == "401892472" }))
        val third = games.first { it.id == "401892475" }
        assertEquals(3, third.period)
        assertEquals("P3 · 20:00", SportsFixtureText.periodClock(third))
        assertEquals("End of 2nd Period", third.situation?.lastPlay)
        val root = JSONObject(sample("nhl-summary"))
        val game = EspnSummary.parse(root, "ice-hockey")
        assertEquals("End of 2nd", game.detail)
        assertEquals(SummaryLine("3", listOf("1", "2")), game.awayLine)
        assertEquals(4, game.moments.size)
        assertNull(game.strength)
        fun upTo(count: Int) = EspnSummary.parse(withPlays(root, count), "ice-hockey").strength
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(5))
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(7))
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(17))
        assertNull(upTo(18))
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), upTo(23))
        assertNull(upTo(24))
    }

    @Test fun fightCardWalkoutsShowTheStatusInsteadOfARound() {
        val card = EspnScoreboard.parse(sample("ufc-scoreboard"), league("ufc"), now).single()
        assertEquals(FixtureStatus.LIVE, card.status)
        assertEquals("Walkouts", card.detail)
        val bouts = (card.sportDetail as SportsDetail.Card).bouts
        assertEquals(listOf(FixtureStatus.FINAL, FixtureStatus.LIVE, FixtureStatus.SCHEDULED), bouts.map { it.state })
        assertEquals(listOf(1, null, null), bouts.map { it.winner })
        assertNull(bouts[1].round)
        assertEquals(SportsBugText("L. Godínez v K. Souza", "Walkouts", "W Strawweight"), SportsFixtureText.bug(card))
        val fighting = card.copy(sportDetail = SportsDetail.Card(bouts.map { if (it.state == FixtureStatus.LIVE) it.copy(round = 2) else it }))
        assertEquals("Round 2 of 3", SportsFixtureText.bug(fighting).state)
    }
}
