package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

enum class ScoreDayKind { LIVE, GAMES, RESULTS, NONE, UNKNOWN }

data class ScoreDaySummary(val kind: ScoreDayKind, val count: Int = 0)

object SportsScoreDays {
    const val PAST_DAYS = 3
    const val SETTLED_MILLIS = 6L * 60 * 60 * 1000
    const val MAX_ENTRIES = 240

    fun dateOf(nowMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

    fun strip(today: LocalDate, future: Int = SportsDays.DAYS, past: Int = PAST_DAYS): List<LocalDate> =
        (-past.coerceIn(0, 7) until future.coerceIn(1, SportsDays.MAX_DAYS)).map { today.plusDays(it.toLong()) }

    fun bounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun serviceDates(date: LocalDate, zone: ZoneId, service: SportsService): List<LocalDate> =
        bounds(date, zone).let { (from, until) -> SportsDays.serviceDates(from, until, SportsDays.zone(service)) }

    fun offered(date: LocalDate, nowMillis: Long, zone: ZoneId): Boolean {
        val today = dateOf(nowMillis, zone)
        return !date.isBefore(today.minusDays(PAST_DAYS.toLong())) && date.isBefore(today.plusDays(SportsDays.MAX_DAYS.toLong()))
    }

    fun dayOf(fixture: SportsFixture, nowMillis: Long, zone: ZoneId): LocalDate {
        val today = dateOf(nowMillis, zone)
        val day = SportsFixtureTimes.day(fixture, nowMillis, zone)
        return if (fixture.status == FixtureStatus.LIVE || (fixture.status == FixtureStatus.SCHEDULED && day.isBefore(today) && fixture.startMillis <= nowMillis)) today else day
    }

    fun on(fixtures: List<SportsFixture>, date: LocalDate, nowMillis: Long, zone: ZoneId): List<SportsFixture> =
        fixtures.filter { dayOf(it, nowMillis, zone) == date }.distinctBy { it.key }

    fun summary(date: LocalDate, today: LocalDate, fixtures: List<SportsFixture>?): ScoreDaySummary {
        if (fixtures == null) return ScoreDaySummary(ScoreDayKind.UNKNOWN)
        if (fixtures.isEmpty()) return ScoreDaySummary(ScoreDayKind.NONE)
        val live = fixtures.count { it.status == FixtureStatus.LIVE }
        val finished = fixtures.count { it.status == FixtureStatus.FINAL }
        return when {
            live > 0 -> ScoreDaySummary(ScoreDayKind.LIVE, live)
            date.isBefore(today) && finished > 0 -> ScoreDaySummary(ScoreDayKind.RESULTS, finished)
            else -> ScoreDaySummary(ScoreDayKind.GAMES, fixtures.size)
        }
    }

    fun settled(date: LocalDate, zone: ZoneId, fetchedAt: Long): Boolean =
        fetchedAt >= date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + SETTLED_MILLIS

    fun due(entry: SportsCacheEntry?, date: LocalDate, zone: ZoneId, nowMillis: Long): Boolean {
        if (entry == null) return true
        val fetched = entry.fetchedAt
        if (fetched != null && fetched <= nowMillis && settled(date, zone, fetched)) return false
        return SportsRefresh.due(entry, nowMillis) && SportsDays.due(date, zone, entry, nowMillis)
    }
}

data class SportsCrest(val url: String, val service: SportsService)

object SportsLeagueCrests {
    const val MAX = 300

    fun collect(fixtures: List<SportsFixture>): Map<String, SportsCrest> = fixtures.filter { it.leagueLogo != null }.groupBy { it.league }
        .mapValues { (_, list) -> list.sortedBy { it.source != SportsService.ESPN }.first().let { SportsCrest(it.leagueLogo!!, it.source) } }

    fun merge(known: Map<String, SportsCrest>, found: Map<String, SportsCrest>): Map<String, SportsCrest> {
        val merged = LinkedHashMap(known)
        for ((league, crest) in found) {
            val old = merged[league]
            if (old == null || old.service != SportsService.ESPN || crest.service == SportsService.ESPN) { merged.remove(league); merged[league] = crest }
        }
        return merged.entries.toList().takeLast(MAX).associate { it.key to it.value }
    }

    fun shown(crests: Map<String, SportsCrest>, mode: SportsLogos): Map<String, String> = when (mode) {
        SportsLogos.OFF -> emptyMap()
        SportsLogos.ESPN -> crests.filterValues { it.service == SportsService.ESPN }.mapValues { it.value.url }
        SportsLogos.ALL -> crests.mapValues { it.value.url }
    }

    fun encode(crests: Map<String, SportsCrest>): String = JSONObject().apply {
        crests.entries.take(MAX).forEach { (league, crest) -> put(league, JSONObject().put("url", crest.url).put("source", crest.service.name)) }
    }.toString()

    fun decode(text: String?): Map<String, SportsCrest> = runCatching {
        val json = JSONObject(text ?: return emptyMap())
        val out = LinkedHashMap<String, SportsCrest>()
        val keys = json.keys()
        while (keys.hasNext() && out.size < MAX) {
            val league = keys.next()
            if (league.isBlank() || league.length > 80) continue
            val item = json.optJSONObject(league) ?: continue
            val url = sportsImage(item.optString("url")) ?: continue
            out[league] = SportsCrest(url, SportsService.entries.firstOrNull { it.name == item.optString("source") } ?: SportsService.ESPN)
        }
        out
    }.getOrDefault(emptyMap())
}

enum class LeagueSource { ESPN, BOTH, THESPORTSDB, NEEDS_KEY }

data class LeaguePickerSport(val sport: String, val leagues: List<SportsLeague>, val chosen: Int)

object SportsLeaguePicker {
    const val TILE_DP = 168f
    const val GAP_DP = 12f
    const val MAX_COLUMNS = 8

    fun sports(offered: List<SportsLeague>, chosen: Set<String>): List<LeaguePickerSport> {
        val order = SportsLeagues.ALL.map { it.sport }.distinct()
        val builtIn = SportsLeagues.ALL.withIndex().associate { it.value.id to it.index }
        return offered.distinctBy { it.id }.groupBy { it.sport }.entries
            .sortedWith(compareBy({ order.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index } }, { it.key }))
            .map { (sport, leagues) ->
                val sorted = leagues.sortedWith(compareBy({ builtIn[it.id] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                LeaguePickerSport(sport, sorted, sorted.count { it.id in chosen })
            }
    }

    fun columns(widthDp: Float, tileDp: Float = TILE_DP, gapDp: Float = GAP_DP, max: Int = MAX_COLUMNS): Int =
        if (widthDp <= 0f || tileDp <= 0f) 1 else ((widthDp + gapDp) / (tileDp + gapDp)).toInt().coerceIn(1, max)

    fun toggle(chosen: Set<String>, id: String): Set<String> = if (id in chosen) chosen - id else chosen + id

    fun saved(offered: List<SportsLeague>, chosen: Set<String>): List<String> = offered.map { it.id }.distinct().filter { it in chosen }

    fun source(league: SportsLeague, hasKey: Boolean): LeagueSource = when {
        league.espn != null -> if (hasKey && league.sportsDb != null) LeagueSource.BOTH else LeagueSource.ESPN
        hasKey -> LeagueSource.THESPORTSDB
        else -> LeagueSource.NEEDS_KEY
    }

    fun monogram(name: String): String {
        val words = name.split(' ', '-', '.').filter { word -> word.isNotEmpty() && word.first().isLetterOrDigit() }
        val capitals = words.filter { it.length in 2..4 && it.all { c -> c.isUpperCase() || c.isDigit() } }
        val letters = capitals.firstOrNull() ?: words.singleOrNull()?.take(3)?.uppercase() ?: words.take(3).joinToString("") { it.first().uppercase() }
        return letters.take(4).ifEmpty { "?" }
    }
}
