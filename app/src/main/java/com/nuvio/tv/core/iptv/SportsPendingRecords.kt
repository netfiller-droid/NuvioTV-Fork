package com.nuvio.tv.core.iptv

data class SportsPendingRecord(val profileId: Int, val key: String, val startMillis: Long, val untilMillis: Long, val home: String? = null, val away: String? = null)

object SportsPendingRecords {
    const val MAX = 50
    const val MAX_WATCHED = 12
    const val MATCH_MILLIS = 20L * 60 * 1000
    private const val MAX_NAME = 120

    fun of(profileId: Int, fixture: SportsFixture): SportsPendingRecord =
        SportsPendingRecord(profileId, fixture.key, fixture.startMillis, fixture.startMillis + SportsRefresh.durationMillis(fixture), name(fixture.home), name(fixture.away))

    private fun name(team: FixtureTeam?): String? = team?.name?.replace('\t', ' ')?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }

    fun migrate(record: SportsPendingRecord): SportsPendingRecord = record.copy(key = SportsDbLeagues.favourite(record.key))

    fun wanted(fixture: SportsFixture, nowMillis: Long): Boolean =
        fixture.status != FixtureStatus.FINAL && fixture.startMillis + SportsRefresh.durationMillis(fixture) > nowMillis

    fun encode(record: SportsPendingRecord): String = "${record.profileId}|${record.startMillis}|${record.untilMillis}|${record.key}" +
        if (record.home != null && record.away != null) "\t${record.home}\t${record.away}" else ""

    fun decode(text: String): SportsPendingRecord? {
        val fields = text.split('\t')
        val parts = fields[0].split('|', limit = 4)
        if (parts.size != 4 || parts[3].isEmpty() || parts[3].length > 400) return null
        val profile = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val start = parts[1].toLongOrNull() ?: return null
        val until = parts[2].toLongOrNull()?.takeIf { it > start } ?: return null
        val teams = fields.drop(1).map { it.trim().take(MAX_NAME) }.takeIf { it.size == 2 && it.all(String::isNotEmpty) }
        return SportsPendingRecord(profile, parts[3], start, until, teams?.get(0), teams?.get(1))
    }

    fun prune(all: Collection<SportsPendingRecord>, nowMillis: Long): List<SportsPendingRecord> =
        all.filter { it.untilMillis > nowMillis }.distinctBy { it.profileId to it.key }.sortedBy { it.startMillis }.take(MAX)

    fun toggle(all: Collection<SportsPendingRecord>, profileId: Int, fixture: SportsFixture, nowMillis: Long): List<SportsPendingRecord> {
        val kept = all.filterNot { it.profileId == profileId && it.key == fixture.key }
        return prune(if (kept.size < all.size || !wanted(fixture, nowMillis)) kept else kept + of(profileId, fixture), nowMillis)
    }

    fun leagues(all: Collection<SportsPendingRecord>): Set<String> = all.map { it.key.substringBefore(':') }.filter { it.isNotEmpty() }.toSet()

    fun update(all: Collection<SportsPendingRecord>, fixtures: Collection<SportsFixture>, nowMillis: Long): List<SportsPendingRecord> {
        val known = fixtures.associateBy { it.key }
        return prune(all.mapNotNull { record ->
            val fixture = known[record.key] ?: same(record, fixtures) ?: return@mapNotNull record
            if (fixture.status == FixtureStatus.FINAL) null else of(record.profileId, fixture)
        }, nowMillis)
    }

    fun same(record: SportsPendingRecord, fixtures: Collection<SportsFixture>): SportsFixture? {
        val league = record.key.substringBefore(':')
        val near = fixtures.filter { it.league == league && it.key != record.key && kotlin.math.abs(it.startMillis - record.startMillis) <= MATCH_MILLIS }.distinctBy { it.key }
        val home = record.home
        val away = record.away
        val source = if (record.key.substringAfter(':').startsWith(SportsDbLeagues.CUSTOM_PREFIX)) SportsService.THESPORTSDB else SportsService.ESPN
        if (home == null || away == null) return near.singleOrNull()?.takeIf { it.source != source }
        fun plays(name: String, team: FixtureTeam?) = team != null && SportsFavourites.matching(setOf("$league:$name"), league, team).isNotEmpty()
        return near.filter { (plays(home, it.home) && plays(away, it.away)) || (plays(home, it.away) && plays(away, it.home)) }.singleOrNull()
    }

    fun watched(all: Collection<SportsPendingRecord>, profileId: Int, fixtures: Collection<SportsFixture>, nowMillis: Long, max: Int = MAX_WATCHED): List<SportsFixture> =
        ready(all, profileId, fixtures, { true }, nowMillis).take(max)

    fun claim(all: Collection<SportsPendingRecord>, profileId: Int, fixtures: Collection<SportsFixture>): Pair<List<SportsPendingRecord>, List<SportsFixture>> {
        val keys = all.filter { it.profileId == profileId }.map { it.key }.toSet()
        val claimed = fixtures.filter { it.key in keys }.distinctBy { it.key }
        val taken = claimed.map { it.key }.toSet()
        return all.filterNot { it.profileId == profileId && it.key in taken } to claimed
    }

    fun ready(all: Collection<SportsPendingRecord>, profileId: Int, fixtures: Collection<SportsFixture>, linked: (SportsFixture) -> Boolean,
        nowMillis: Long): List<SportsFixture> {
        val wanted = all.filter { it.profileId == profileId && it.untilMillis > nowMillis }.map { it.key }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return fixtures.filter { it.key in wanted && wanted(it, nowMillis) && linked(it) }.distinctBy { it.key }.sortedBy { it.startMillis }
    }
}
