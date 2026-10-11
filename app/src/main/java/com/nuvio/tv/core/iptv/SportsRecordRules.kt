package com.nuvio.tv.core.iptv

data class SportsRecordTarget(val sourceId: String, val channelId: String, val programmeStartMillis: Long? = null, val programmeStopMillis: Long? = null,
    val programmeTitle: String? = null)

data class SportsRecordPlan(val fixture: SportsFixture, val target: SportsRecordTarget, val window: RecordingWindow, val final: Boolean)

data class SportsRecordOutcome(val plans: List<SportsRecordPlan> = emptyList(), val waiting: List<SportsFixture> = emptyList(),
    val missed: List<SportsFixture> = emptyList())

object SportsRecordRules {
    const val FINAL_EXTRA_MILLIS = 30L * 60 * 1000
    const val MAX_RULES = 50
    private const val DEFAULT_OVERTIME_MINUTES = 30
    private const val KNOCKOUT_MINUTES = 60
    private val OVERTIME_MINUTES = mapOf("american-football" to 90, "basketball" to 60, "ice-hockey" to 90, "baseball" to 90, "tennis" to 90, "cricket" to 60)
    private val FINAL = Regex("\\b(grand final|finals?|semi[- ]?finals?|quarter[- ]?finals?|preliminary final|qualifying final|elimination final|" +
        "play[- ]?offs?|wild ?card|knockout|championship game|super bowl|world series|stanley cup final|decider)\\b", RegexOption.IGNORE_CASE)

    fun wanted(rules: Set<String>, fixture: SportsFixture): Boolean =
        rules.isNotEmpty() && fixture.status != FixtureStatus.FINAL && listOfNotNull(fixture.home, fixture.away).any { SportsFavourites.matching(rules, fixture.league, it).isNotEmpty() }

    fun read(saved: Collection<String>): Set<String> =
        saved.map(SportsDbLeagues::favourite).filter { SportsFavourites.parse(it) != null && it.length <= 200 }.distinct().take(MAX_RULES).toSet()

    fun plan(rules: Set<String>, fixtures: List<SportsFixture>, links: Map<String, SportsRecordTarget>, scheduled: Set<String>, earlyMillis: Long,
        lateMillis: Long, nowMillis: Long): SportsRecordOutcome {
        if (rules.isEmpty()) return SportsRecordOutcome()
        val plans = mutableListOf<SportsRecordPlan>()
        val waiting = mutableListOf<SportsFixture>()
        val missed = mutableListOf<SportsFixture>()
        fixtures.distinctBy { it.key }.sortedBy { it.startMillis }.forEach { fixture ->
            if (!wanted(rules, fixture) || fixture.key in scheduled) return@forEach
            if (fixture.startMillis + SportsRefresh.durationMillis(fixture) <= nowMillis) return@forEach
            val target = links[fixture.key]
            when {
                target != null -> window(fixture, target, earlyMillis, lateMillis, nowMillis)?.let { plans += SportsRecordPlan(fixture, target, it, final(fixture, target.programmeTitle)) }
                nowMillis >= fixture.startMillis -> missed += fixture
                else -> waiting += fixture
            }
        }
        return SportsRecordOutcome(plans, waiting, missed)
    }

    fun window(fixture: SportsFixture, target: SportsRecordTarget, earlyMillis: Long, lateMillis: Long, nowMillis: Long): RecordingWindow? {
        require(earlyMillis >= 0 && lateMillis >= 0)
        val end = end(fixture, target.programmeStopMillis, target.programmeTitle, lateMillis)
        val stop = end + overtimeMillis(fixture, target.programmeTitle)
        val start = maxOf(fixture.startMillis - earlyMillis, nowMillis)
        if (stop <= start || (end <= start && fixture.status != FixtureStatus.LIVE)) return null
        return RecordingWindow(start, minOf(stop, start + RecordingPlan.MAX_DURATION_MILLIS))
    }

    fun end(fixture: SportsFixture, programmeStopMillis: Long?, programmeTitle: String?, lateMillis: Long): Long {
        val duration = SportsRefresh.durationMillis(fixture)
        val expected = fixture.startMillis + duration
        val broadcast = programmeStopMillis?.takeIf { it > fixture.startMillis + duration / 2 && it <= expected + duration }
        return maxOf(expected, broadcast ?: expected) + lateMillis + if (final(fixture, programmeTitle)) FINAL_EXTRA_MILLIS else 0
    }

    fun overtimeMillis(fixture: SportsFixture, programmeTitle: String? = null): Long =
        (if (fixture.sport == "soccer" && final(fixture, programmeTitle)) KNOCKOUT_MINUTES else OVERTIME_MINUTES[fixture.sport] ?: DEFAULT_OVERTIME_MINUTES) * 60_000L

    fun ended(fixture: SportsFixture?, stopMillis: Long, programmeStopMillis: Long?, lateMillis: Long, finalSinceMillis: Long?, nowMillis: Long): Boolean {
        if (fixture == null || fixture.status == FixtureStatus.LIVE || nowMillis >= stopMillis) return false
        val planned = minOf(stopMillis, maxOf(stopMillis - overtimeMillis(fixture), end(fixture, programmeStopMillis, null, lateMillis)))
        val after = if (fixture.status == FixtureStatus.FINAL) (finalSinceMillis ?: nowMillis) + lateMillis else planned
        return nowMillis >= maxOf(planned, after)
    }

    fun final(fixture: SportsFixture, programmeTitle: String? = null): Boolean {
        val round = (fixture.sportDetail as? SportsDetail.Tennis)?.round
        return listOfNotNull(fixture.title, round, programmeTitle, fixture.detail?.takeIf { fixture.status == FixtureStatus.SCHEDULED })
            .any { text -> FINAL.containsMatchIn(text) }
    }
}

data class SportsTeamGames(val key: String, val league: String, val name: String, val team: FixtureTeam?, val current: SportsFixture?,
    val upcoming: List<SportsFixture>, val last: SportsFixture?)

object SportsTeams {
    fun games(key: String, fixtures: List<SportsFixture>, nowMillis: Long): SportsTeamGames? {
        val (league, name) = SportsFavourites.parse(key) ?: return null
        val wanted = setOf(key)
        fun ours(team: FixtureTeam) = SportsFavourites.matching(wanted, league, team).isNotEmpty()
        val mine = fixtures.distinctBy { it.key }.filter { it.league == league && listOfNotNull(it.home, it.away).any(::ours) }.sortedBy { it.startMillis }
        val team = mine.firstNotNullOfOrNull { fixture -> listOfNotNull(fixture.home, fixture.away).firstOrNull { it.name.trim() == name } }
            ?: mine.firstNotNullOfOrNull { fixture -> listOfNotNull(fixture.home, fixture.away).firstOrNull(::ours) }
        val live = mine.lastOrNull { it.status == FixtureStatus.LIVE }
        val upcoming = mine.filter { it.status == FixtureStatus.SCHEDULED && it.startMillis + SportsRefresh.durationMillis(it) > nowMillis }
        return SportsTeamGames(key, league, name, team, live ?: upcoming.firstOrNull(), if (live != null) upcoming else upcoming.drop(1),
            mine.lastOrNull { it.status == FixtureStatus.FINAL })
    }

    fun home(games: SportsTeamGames, fixture: SportsFixture): Boolean? {
        val wanted = setOf(games.key)
        fun ours(team: FixtureTeam?) = team != null && (team.name.trim() == games.name || SportsFavourites.matching(wanted, games.league, team).isNotEmpty())
        val home = ours(fixture.home)
        val away = ours(fixture.away)
        return if (home == away) null else home
    }
}
