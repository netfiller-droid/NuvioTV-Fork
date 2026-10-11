package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureChannel
import com.nuvio.tv.core.iptv.FixtureLinkReason
import com.nuvio.tv.core.iptv.FixtureListing
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.SportsCatchup
import com.nuvio.tv.core.iptv.SportsChannelPick
import com.nuvio.tv.core.iptv.SportsChannelPicks
import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsChannelRules
import com.nuvio.tv.core.iptv.SportsCrest
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsDbLeague
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureMatching
import com.nuvio.tv.core.iptv.SportsGuideSlices
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeagueCrests
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsPickKind
import com.nuvio.tv.core.iptv.SportsPickList
import com.nuvio.tv.core.iptv.SportsPolling
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsScoreDays
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsSources
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class IptvSportsFixtures(val enabled: Boolean, val fixtures: List<SportsFixture> = emptyList(), val failed: Boolean = false,
    val missingKey: Boolean = false, val noLeagues: Boolean = false)

data class IptvSportsPickOption(val pick: SportsChannelPick, val detail: String? = null)

data class IptvFixtureLink(val row: IptvListedChannel, val reason: FixtureLinkReason, val programme: GuideProgramme? = null, val broadcaster: String? = null)

class IptvSportsFixturesRepository(private val preferences: IptvSportsPreferences, private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    client: IptvSportsFixturesClient, store: IptvSportsFixturesStore, private val guideDays: () -> Int? = { null }) {
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val cache = IptvSportsFixturesCache(client, store)
    private val days = IptvSportsDayCache(client, store)
    private val crests = IptvSportsCrests(store)

    suspend fun load(nowMillis: Long, zone: ZoneId, refresh: Boolean, favourites: Set<String> = emptySet(), followedOnly: Boolean = false,
        alsoLeagues: Set<String> = emptySet()): IptvSportsFixtures =
        withContext(Dispatchers.IO) {
            if (!preferences.enabled) return@withContext IptvSportsFixtures(false)
            syncDays()
            val chosen = SportsLeagues.chosen(preferences.leagues)
            val leagues = if (followedOnly) (SportsPolling.followedLeagues(favourites) + alsoLeagues).let { ids -> chosen.filter { it.id in ids } } else chosen
            if (leagues.isEmpty()) return@withContext IptvSportsFixtures(true, noLeagues = true)
            val key = if (preferences.hasKey) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null
            val usable = leagues.filter { SportsSources.available(it, key != null) }
            if (usable.isEmpty()) return@withContext IptvSportsFixtures(true, missingKey = true)
            val result = cache.load(usable, key, nowMillis, zone, refresh, favourites, prune = !followedOnly, country = Locale.getDefault().country)
            crests.keep(result.fixtures)
            result.copy(fixtures = SportsSources.logos(result.fixtures, preferences.logos))
        }

    suspend fun day(date: LocalDate, zone: ZoneId, nowMillis: Long, fetch: Boolean = true): IptvSportsFixtures = withContext(Dispatchers.IO) {
        if (!preferences.enabled) return@withContext IptvSportsFixtures(false)
        syncDays()
        val chosen = SportsLeagues.chosen(preferences.leagues)
        if (chosen.isEmpty()) return@withContext IptvSportsFixtures(true, noLeagues = true)
        val key = if (preferences.hasKey) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null
        val usable = chosen.filter { SportsSources.available(it, key != null) }
        if (usable.isEmpty()) return@withContext IptvSportsFixtures(true, missingKey = true)
        if (!SportsScoreDays.offered(date, nowMillis, zone)) return@withContext IptvSportsFixtures(true)
        val result = days.load(usable, key, date, zone, nowMillis, fetch)
        crests.keep(result.fixtures)
        result.copy(fixtures = SportsSources.logos(result.fixtures, preferences.logos))
    }

    suspend fun leagueCrests(): Map<String, String> = withContext(Dispatchers.IO) { SportsLeagueCrests.shown(crests.known(), preferences.logos) }

    suspend fun leagueList(nowMillis: Long): List<SportsDbLeague>? {
        val key = withContext(Dispatchers.IO) { if (preferences.hasKey) preferences.key()?.takeIf { IptvSportsPreferences.validKey(it) } else null } ?: return null
        return cache.leagues(key, nowMillis)
    }

    suspend fun links(ref: IptvSourceRef, fixtures: List<SportsFixture>, nowMillis: Long, hiddenCategories: Set<String>): Map<String, List<IptvFixtureLink>> =
        withContext(Dispatchers.IO) {
            syncDays()
            val upcoming = fixtures.filter { it.status != FixtureStatus.FINAL }.take(MAX_FIXTURES)
            val recent = SportsCatchup.recent(fixtures, nowMillis)
            val current = upcoming + recent
            if (current.isEmpty()) return@withContext emptyMap()
            val rules = preferences.channelRules
            val excluded = (hiddenCategories.take(500) + exactCategories(ref, rules.categories(SportsPickList.EXCLUDED)).flatten()).take(500).toSet()
            val associations = catalogue.guideAssociations(ref)
            val order = (associations.priority + associations.feedIds).distinct().take(16)
            val source = preferences.channelSource
            val matched = mutableListOf<IptvAiringMatch>()
            val slices = SportsGuideSlices.plan(upcoming, recent, nowMillis)
            if (source.guide && order.isNotEmpty()) for (slice in slices.keys) {
                currentCoroutineContext().ensureActive()
                val start = slice * SportsGuideSlices.SLICE_MILLIS
                val near = (slice - NEIGHBOUR_SLICES..slice + 1).flatMap { slices[it].orEmpty() }.distinctBy { it.key }
                fun query(limit: Int) = guides.sportsMatches(ref.profileId, order, start, start + SportsGuideSlices.SLICE_MILLIS, limit, SportsGuideSlices.LOOKBACK_MILLIS)
                val found = query(PROGRAMMES_PER_SLICE).let { if (it.size >= PROGRAMMES_PER_SLICE / order.size) query(BUSY_PROGRAMMES_PER_SLICE) else it }
                matched += found.filter { match -> near.any { SportsFixtureMatching.guideMatch(it, match.programme, nowMillis) != null } }
            }
            val unique = matched.distinctBy { Triple(it.key, it.programme.start.epochMillis, it.programme.titles.firstOrNull()?.text) }
            val guideRows = browse.guideChannels(ref, unique, excluded)
            val byKey = unique.groupBy { it.key }
            val listings = guideRows.flatMap { row -> byKey[row.guide.key].orEmpty().map { FixtureListing(row.item.channel.id, it.programme) } }
            val terms = if (source.broadcasters) SportsFixtureMatching.searchTerms(current.flatMap { it.broadcasters }.distinct()) else emptyList()
            val broadcastRows = if (terms.isEmpty()) emptyList() else buildList<IptvListedChannel> {
                val all = quiet("sports channels") { browse.searchAny(ref, terms, excludedCategories = excluded, limit = ROWS_PER_SEARCH) }
                addAll(all)
                if (all.size >= ROWS_PER_SEARCH && terms.size > 1) for (term in terms) {
                    if (size >= MAX_BROADCAST_ROWS) break
                    addAll(quiet("sports channels") { browse.searchAny(ref, listOf(term), excludedCategories = excluded, limit = minOf(ROWS_PER_TERM, MAX_BROADCAST_ROWS - size)) })
                }
                val preferred = exactCategories(ref, rules.categories(SportsPickList.PREFERRED)).map { names -> names.filter { it !in excluded }.toSet() }
                    .filter { it.isNotEmpty() }.take(MAX_PREFERRED_CATEGORIES)
                for (names in preferred) addAll(quiet("sports channels") { browse.searchAny(ref, terms, categories = names, limit = ROWS_PER_PREFERRED) })
            }
            val rows = (guideRows + broadcastRows).distinctBy { it.item.channel.id }
            val channels = rows.map { FixtureChannel(it.item.channel.id, it.item.overlay.customName ?: it.item.channel.data.name,
                it.item.attributes[CATEGORY_ATTRIBUTE]?.trim()?.take(240), it.item.overlay.hidden) }
            val byId = rows.associateBy { it.item.channel.id }
            SportsFixtureMatching.link(current, channels, listings, excluded, nowMillis, source = source, rules = rules).mapValues { (_, links) ->
                links.mapNotNull { link -> byId[link.channelId]?.let { IptvFixtureLink(it, link.reason, link.programme, link.broadcaster) } }
            }.filterValues { it.isNotEmpty() }
        }

    fun ranked(links: List<IptvFixtureLink>, rules: SportsChannelRules = preferences.channelRules): List<IptvFixtureLink> =
        rules.ranked(links.sortedBy { it.reason.ordinal }.distinctBy { it.row.item.channel.id }) { it.row.item.channel.id to it.row.item.attributes[CATEGORY_ATTRIBUTE] }
            .sortedBy { it.reason.ordinal }

    suspend fun alwaysChannels(ref: IptvSourceRef, hiddenCategories: Set<String>, limit: Int = MAX_ALWAYS): List<IptvListedChannel> =
        withContext(Dispatchers.IO) {
            val rules = preferences.channelRules
            if (rules.always.isEmpty()) return@withContext emptyList()
            val hidden = hiddenCategories.map { it.trim() }.toSet()
            val picked = rules.channels(SportsPickList.ALWAYS).map { it.value }.take(limit)
            val rows = quiet("sports always channels") { browse.channels(ref, picked) }.toMutableList()
            val names = exactCategories(ref, rules.categories(SportsPickList.ALWAYS)).flatten().filter { it.trim() !in hidden }.take(MAX_ALWAYS_QUERIES)
            for (name in names) {
                if (rows.size >= limit) break
                rows += quiet("sports always channels") { browse.page(ref, IptvBrowseQuery(category = name), null, (limit - rows.size).coerceIn(1, ROWS_PER_ALWAYS)).channels }
            }
            rows.filter { (it.item.attributes[CATEGORY_ATTRIBUTE]?.trim() ?: "") !in hidden && !it.item.overlay.hidden }
                .distinctBy { it.item.channel.id }.take(limit)
        }

    suspend fun pickCategories(profileId: Int): List<SportsChannelPick> = withContext(Dispatchers.IO) {
        catalogue.sources(profileId).filter { it.playbackEligible }.flatMap { source ->
            currentCoroutineContext().ensureActive()
            try { catalogue.categories(source.ref).map { it.name } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("sports categories", error); emptyList() }
        }.filter { it.isNotBlank() && it.length <= 240 }.distinctBy { it.trim().lowercase() }
            .map { SportsChannelPick(SportsPickKind.CATEGORY, it, it.trim()) }
    }

    suspend fun pickChannels(profileId: Int, search: String, limit: Int = MAX_PICK_CHANNELS): List<IptvSportsPickOption> = withContext(Dispatchers.IO) {
        val term = search.trim().take(256)
        if (term.length < 2) return@withContext emptyList()
        val sources = catalogue.sources(profileId).filter { it.playbackEligible }
        sources.flatMap { source ->
            currentCoroutineContext().ensureActive()
            val items = try { catalogue.page(source.ref, IptvBrowseQuery(search = term), null, limit).items }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports channel search", error); emptyList() }
            items.map { item ->
                val name = item.overlay.customName ?: item.channel.data.name
                val category = item.attributes[CATEGORY_ATTRIBUTE]?.trim()?.takeIf { it.isNotEmpty() }
                val where = listOfNotNull(category, source.label.takeIf { sources.size > 1 }).joinToString(" · ")
                IptvSportsPickOption(SportsChannelPick(SportsPickKind.CHANNEL, item.channel.id, name.trim().take(240)), where.ifEmpty { null })
            }
        }.distinctBy { it.pick.value }.take(limit)
    }

    private suspend fun quiet(label: String, block: suspend () -> List<IptvListedChannel>): List<IptvListedChannel> {
        currentCoroutineContext().ensureActive()
        return try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure(label, error); emptyList() }
    }

    private fun exactCategories(ref: IptvSourceRef, picks: List<String>): List<List<String>> {
        val wanted = picks.map(SportsChannelPicks::category).filter(String::isNotEmpty).distinct()
        if (wanted.isEmpty()) return emptyList()
        val names = try { catalogue.categories(ref).map { it.name } }
            catch (error: Exception) { IptvLog.failure("sports categories", error); emptyList() }
        val byKey = names.filter { it.length <= 240 }.groupBy(SportsChannelPicks::category)
        return wanted.mapNotNull { byKey[it] }
    }

    private fun syncDays() {
        try { guideDays()?.let(SportsDays::guideDays) }
        catch (error: Exception) { IptvLog.failure("sports guide days", error) }
    }

    private companion object {
        const val MAX_FIXTURES = 320
        const val NEIGHBOUR_SLICES = 3
        const val PROGRAMMES_PER_SLICE = 400
        const val BUSY_PROGRAMMES_PER_SLICE = 2000
        const val ROWS_PER_SEARCH = 200
        const val ROWS_PER_TERM = 150
        const val MAX_BROADCAST_ROWS = 1200
        const val MAX_ALWAYS_QUERIES = 12
        const val CATEGORY_ATTRIBUTE = "group-title"
        const val MAX_PREFERRED_CATEGORIES = 4
        const val ROWS_PER_PREFERRED = 100
        const val MAX_ALWAYS = 120
        const val ROWS_PER_ALWAYS = 120
        const val MAX_PICK_CHANNELS = 60
    }
}

class IptvSportsDayCache(private val client: IptvSportsFixturesClient, private val store: IptvSportsFixturesStore) {
    private val entries = object : LinkedHashMap<String, SportsCacheEntry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SportsCacheEntry>?): Boolean = size > SportsScoreDays.MAX_ENTRIES
    }
    private val loads = Mutex()
    private val requests = Semaphore(MAX_REQUESTS)

    suspend fun load(leagues: List<SportsLeague>, key: String?, date: LocalDate, zone: ZoneId, nowMillis: Long, fetch: Boolean = true): IptvSportsFixtures =
        withContext(Dispatchers.IO) {
            fun wanted(service: SportsService, league: SportsLeague) =
                SportsScoreDays.serviceDates(date, zone, service).map { Wanted(IptvSportsFixturesStore.key(service, league, it), service, league, it) }
            val usable = leagues.filter { SportsSources.available(it, key != null) }
            val found = loads.withLock {
                val primary = usable.associate { league -> league.id to wanted(if (league.espn != null) SportsService.ESPN else SportsService.THESPORTSDB, league) }
                val got = load(primary.values.flatten(), key, nowMillis, fetch).toMutableMap()
                val fallback = usable.filter { league -> key != null && league.espn != null && league.sportsDb != null &&
                    primary[league.id].orEmpty().all { got[it.key]?.fetchedAt == null } && primary[league.id].orEmpty().any { (got[it.key]?.failures ?: 0) > 0 } }
                    .flatMap { wanted(SportsService.THESPORTSDB, it) }
                got += load(fallback, key, nowMillis, fetch)
                got.values.toList()
            }
            val fixtures = SportsScoreDays.on(found.flatMap { it.fixtures }, date, nowMillis, zone)
            IptvSportsFixtures(true, fixtures, failed = fixtures.isEmpty() && found.any { it.fetchedAt == null && it.failures > 0 })
        }

    private suspend fun load(items: List<Wanted>, key: String?, nowMillis: Long, fetch: Boolean): Map<String, SportsCacheEntry> {
        if (items.isEmpty()) return emptyMap()
        val known = items.associate { item ->
            val held = synchronized(entries) { entries[item.key] }
            val stored = try { store.read(item.key) } catch (error: Exception) { IptvLog.failure("sports day cache", error); null }
            item.key to listOfNotNull(held, stored).maxByOrNull { it.fetchedAt ?: Long.MIN_VALUE }
        }
        val due = if (fetch) items.filter { SportsScoreDays.due(known[it.key], it.date, SportsDays.zone(it.service), nowMillis) } else emptyList()
        val fetched = if (due.isEmpty()) emptyMap() else coroutineScope {
            due.map { item -> async { requests.withPermit { item.key to request(item, known[item.key], key, nowMillis) } } }.awaitAll().toMap()
        }
        if (fetched.isNotEmpty()) {
            synchronized(entries) { entries.putAll(fetched) }
            IptvLog.info("sports day requests=${fetched.size} failed=${fetched.values.count { it.failedAt == nowMillis }}")
        }
        return items.mapNotNull { item -> (fetched[item.key] ?: known[item.key])?.let { item.key to it } }.toMap()
    }

    private suspend fun request(item: Wanted, previous: SportsCacheEntry?, key: String?, nowMillis: Long): SportsCacheEntry = try {
        SportsRefresh.succeeded(client.fixtures(item.service, item.league, item.date, key, nowMillis), nowMillis)
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) {
        IptvLog.failure("sports day ${item.service.name.lowercase()}${(error as? SportsFetchException)?.status?.let { " status=$it" }.orEmpty()}", error)
        SportsRefresh.failed(previous, nowMillis)
    }

    private data class Wanted(val key: String, val service: SportsService, val league: SportsLeague, val date: LocalDate)

    private companion object {
        const val MAX_REQUESTS = 2
    }
}

class IptvSportsCrests(private val store: IptvSportsFixturesStore) {
    private val lock = Any()
    private var crests: Map<String, SportsCrest>? = null

    fun known(): Map<String, SportsCrest> = synchronized(lock) {
        crests ?: (try { SportsLeagueCrests.decode(store.readText(KEY)) } catch (error: Exception) { IptvLog.failure("sports crests", error); emptyMap() })
            .also { crests = it }
    }

    fun keep(fixtures: List<SportsFixture>) {
        val found = SportsLeagueCrests.collect(fixtures)
        if (found.isEmpty()) return
        try {
            synchronized(lock) {
                val known = known()
                val merged = SportsLeagueCrests.merge(known, found)
                if (merged == known) return
                crests = merged
                store.writeText(KEY, SportsLeagueCrests.encode(merged))
            }
        } catch (error: Exception) { IptvLog.failure("sports crests", error) }
    }

    private companion object {
        const val KEY = "meta-crests"
    }
}
