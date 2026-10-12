package com.nuvio.tv.core.iptv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsGoaliesTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }

    @Test fun realNhlSummaryGivesOneGoaliePerSide() {
        val summary = EspnSummary.parse(sample("espn-nhl-summary-real-20261010.json"), SportsLeagues.byId("nhl")!!)
        val shown = SportsGoalies.inNet(summary.goalies, awayFirst = true)
        assertEquals(2, shown.size)
        assertEquals(FixtureSide.AWAY, shown[0].side)
        assertEquals(FixtureSide.HOME, shown[1].side)
        val grubauer = shown.first { it.name == "Philipp Grubauer" }
        assertEquals(17, grubauer.saves)
        assertEquals(18, grubauer.shotsAgainst)
        assertEquals(".944", SportsGoalies.savePct(grubauer.savePct))
        assertEquals("Grubauer", SportsGoalies.surname(grubauer.name))
    }

    @Test fun nullStatsAreReadAsMissing() {
        val root = JSONObject(sample("espn-nhl-summary-real-20261010.json"))
        val players = root.getJSONObject("boxscore").getJSONArray("players")
        for (team in 0 until players.length()) {
            val groups = players.getJSONObject(team).getJSONArray("statistics")
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index).takeIf { it.optString("name") == "goalies" } ?: continue
                val keys = group.getJSONArray("keys")
                val saves = (0 until keys.length()).first { keys.optString(it) == "saves" }
                keys.put((0 until keys.length()).first { keys.optString(it) == "savePct" }, JSONObject.NULL)
                val athletes = group.getJSONArray("athletes")
                for (athlete in 0 until athletes.length()) athletes.getJSONObject(athlete).getJSONArray("stats").put(saves, JSONObject.NULL)
            }
        }
        val goalies = EspnSummary.parse(root.toString(), SportsLeagues.byId("nhl")!!).goalies
        assertTrue(goalies.isNotEmpty())
        assertTrue(goalies.all { it.saves == null && it.savePct == null })
        assertEquals(18, goalies.first { it.name == "Philipp Grubauer" }.shotsAgainst)
    }

    @Test fun pulledGoalieGivesWayToTheOneFacingMoreShots() {
        val list = listOf(SummaryGoalie(FixtureSide.HOME, "A Starter", 3, 5, ".600"), SummaryGoalie(FixtureSide.HOME, "B Backup", 20, 21, ".952"),
            SummaryGoalie(FixtureSide.AWAY, "C Only", null, null, null))
        assertEquals(listOf("B Backup", "C Only"), SportsGoalies.inNet(list, awayFirst = false).map { it.name })
        assertEquals(listOf("C Only", "B Backup"), SportsGoalies.inNet(list, awayFirst = true).map { it.name })
        assertTrue(SportsGoalies.inNet(emptyList(), true).isEmpty())
    }

    @Test fun emptyNetNamesTheSideWithEveryGoalieOff() {
        val home = SummaryGoalie(FixtureSide.HOME, "A Starter", 20, 22, ".909", pulled = true)
        val away = SummaryGoalie(FixtureSide.AWAY, "C Only", 30, 33, ".909")
        assertEquals(FixtureSide.HOME, SportsGoalies.emptyNet(listOf(home, away)))
        assertNull(SportsGoalies.emptyNet(listOf(home.copy(pulled = false), away)))
        assertNull(SportsGoalies.emptyNet(emptyList()))
    }

    @Test fun savePercentageReadsLikeHockey() {
        assertEquals(".944", SportsGoalies.savePct("0.944"))
        assertEquals(".900", SportsGoalies.savePct(".9"))
        assertEquals("1.000", SportsGoalies.savePct("1"))
        assertEquals("n/a", SportsGoalies.savePct("n/a"))
        assertNull(SportsGoalies.savePct(" "))
        assertNull(SportsGoalies.savePct(null))
        assertEquals("Mononym", SportsGoalies.surname(" Mononym "))
    }
}
