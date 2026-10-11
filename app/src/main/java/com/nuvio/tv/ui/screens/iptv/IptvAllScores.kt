@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.ScoreDayKind
import com.nuvio.tv.core.iptv.ScoreDaySummary
import com.nuvio.tv.core.iptv.SportsDbLive
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsRecordedWindow
import com.nuvio.tv.core.iptv.SportsScoreDays
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsSpoilers
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val FILTER_ALL = "all"
private const val FILTER_LIVE = "live"
private const val FILTER_MINE = "mine"
private const val FILTER_SPORT = "sport:"
private val LineShape = RoundedCornerShape(10.dp)
private val DayShape = RoundedCornerShape(10.dp)
private val TEAM_LINE = 66.dp
private val PLAIN_LINE = 40.dp
private val DAY_MIN = 84.dp
private val DAY_MAX = 150.dp
private val DAY_GAP = 6.dp
private const val DAY_SETTLE = 350L
private const val CLOCK_MILLIS = 60_000L

private sealed class ScoreEntry {
    data class Header(val league: String, val live: Int) : ScoreEntry()
    data class Line(val item: IptvFixtureItem) : ScoreEntry()
}

data class IptvScoreDay(val fixtures: List<SportsFixture>? = null, val loading: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class IptvScoreDaysViewModel @Inject constructor(private val repository: IptvSportsFixturesRepository, private val preferences: IptvSportsPreferences,
    private val recorder: IptvRecorder, private val profiles: ProfileManager) : ViewModel() {
    private val mutable = MutableStateFlow<Map<LocalDate, IptvScoreDay>>(emptyMap())
    val days = mutable.asStateFlow()
    private val revealedKeys = MutableStateFlow<Set<String>>(emptySet())
    val revealed = revealedKeys.asStateFlow()
    private var job: Job? = null
    private var peeking: Job? = null

    fun peek(dates: List<LocalDate>, zone: ZoneId) {
        if (peeking?.isActive == true) return
        peeking = viewModelScope.launch {
            for (date in dates) {
                if (mutable.value[date]?.fixtures != null) continue
                val result = try { repository.day(date, zone, System.currentTimeMillis(), fetch = false) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { IptvLog.failure("sports day", error); null }
                if (result != null && result.fixtures.isNotEmpty()) mutable.update { it + (date to (it[date] ?: IptvScoreDay()).copy(fixtures = result.fixtures)) }
            }
        }
    }

    fun select(date: LocalDate, zone: ZoneId) {
        job?.cancel()
        mutable.update { it + (date to (it[date] ?: IptvScoreDay()).copy(loading = true, failed = false)) }
        job = viewModelScope.launch {
            val result = try { repository.day(date, zone, System.currentTimeMillis()) }
                catch (cancel: CancellationException) { mutable.update { it + (date to (it[date] ?: IptvScoreDay()).copy(loading = false)) }; throw cancel }
                catch (error: Exception) { IptvLog.failure("sports day", error); null }
            mutable.update { days ->
                val known = days[date]?.fixtures
                days + (date to if (result == null || !result.enabled) IptvScoreDay(known, failed = true)
                    else IptvScoreDay(result.fixtures.takeIf { it.isNotEmpty() || !result.failed } ?: known, failed = result.failed))
            }
        }
    }

    fun reveal(key: String) = revealedKeys.update { it + key }

    fun spoilers(fixtures: List<SportsFixture>): Set<String> {
        if (!preferences.hideSpoilers || fixtures.isEmpty()) return emptySet()
        val profile = profiles.activeProfileId.value
        return SportsSpoilers.keys(fixtures, recorder.all.value.filter {
            it.profileId == profile && it.status != RecordingStatus.FAILED && it.status != RecordingStatus.CANCELLED && it.playedAtMillis == null
        }.mapNotNull { recording -> recording.title?.let { SportsRecordedWindow(it, recording.startMillis, recording.stopMillis) } })
    }
}

@Composable
internal fun IptvAllScores(state: IptvFixturesState, playingId: String?, onWatch: (IptvListedChannel) -> Unit, onReveal: (String) -> Unit,
    onReminder: (SportsFixture) -> Unit, onDismiss: () -> Unit, days: IptvScoreDaysViewModel = hiltViewModel()) {
    val zone = remember { ZoneId.systemDefault() }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(CLOCK_MILLIS); value = System.currentTimeMillis() } }
    val today = SportsScoreDays.dateOf(now, zone)
    val strip = remember(today, SportsDays.DAYS) { SportsScoreDays.strip(today) }
    var chosenDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(chosenDay).takeIf { it in strip } ?: today
    val past = day.isBefore(today)
    val loaded by days.days.collectAsStateWithLifecycle()
    val revealed by days.revealed.collectAsStateWithLifecycle()
    val current = remember(state.items, now) { state.items.groupBy { SportsScoreDays.dayOf(it.fixture, now, zone) } }
    val pastFixtures = if (past) loaded[day]?.fixtures.orEmpty() else emptyList()
    val pastSpoilers = remember(pastFixtures, revealed, state.showScores) { if (state.showScores) days.spoilers(pastFixtures) - revealed else emptySet() }
    val all = remember(day, current, pastFixtures, state.recent, state.favourites) {
        if (!past) current[day].orEmpty() else {
            val recent = state.recent.associateBy { it.fixture.key }
            pastFixtures.map { fixture ->
                IptvFixtureItem(fixture, recent[fixture.key]?.links.orEmpty(), SportsFavourites.has(state.favourites, fixture),
                    scheduleOnly = fixture.status != FixtureStatus.FINAL && fixture.source == SportsService.THESPORTSDB && fixture.sport !in SportsDbLive.SPORTS)
            }
        }
    }
    fun hidden(fixture: SportsFixture) = state.hidden(fixture) || fixture.key in pastSpoilers
    fun spoiler(fixture: SportsFixture) = state.spoiler(fixture) || fixture.key in pastSpoilers
    LaunchedEffect(strip) { days.peek(strip.filter { it.isBefore(today) }, zone) }
    LaunchedEffect(day) { if (past) days.select(day, zone) }
    val leagueOrder = remember { SportsLeagues.ALL.withIndex().associate { it.value.id to it.index } }
    val sportOrder = remember { SportsLeagues.ALL.map { it.sport }.distinct().withIndex().associate { it.value to it.index } }
    val sports = remember(all) { all.map { it.fixture.sport }.distinct().sortedBy { sportOrder[it] ?: Int.MAX_VALUE } }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    fun wanted(item: IptvFixtureItem, key: String): Boolean = when {
        key == FILTER_LIVE -> item.fixture.status == FixtureStatus.LIVE
        key == FILTER_MINE -> item.favourite
        key.startsWith(FILTER_SPORT) -> item.fixture.sport == key.removePrefix(FILTER_SPORT)
        else -> true
    }
    val entries = remember(all, filter) {
        all.filter { wanted(it, filter) }.groupBy { it.fixture.league }.entries
            .sortedWith(compareBy({ entry -> if (entry.value.any { it.fixture.status == FixtureStatus.LIVE }) 0 else 1 }, { leagueOrder[it.key] ?: Int.MAX_VALUE }))
            .flatMap { (league, list) ->
                listOf<ScoreEntry>(ScoreEntry.Header(league, list.count { it.fixture.status == FixtureStatus.LIVE })) + list.sortedWith(compareBy(
                    { when (it.fixture.status) { FixtureStatus.LIVE -> 0; FixtureStatus.SCHEDULED -> 1; FixtureStatus.FINAL -> 2 } },
                    { if (it.fixture.status == FixtureStatus.FINAL) -it.fixture.startMillis else it.fixture.startMillis })).map { ScoreEntry.Line(it) }
            }
    }
    LaunchedEffect(day, all.isEmpty()) { if (filter != FILTER_ALL && all.isNotEmpty() && all.none { wanted(it, filter) }) filter = FILTER_ALL }
    val summaries = strip.map { date ->
        SportsScoreDays.summary(date, today, if (date.isBefore(today)) loaded[date]?.fixtures else current[date].orEmpty().map { it.fixture })
    }
    val dayState = loaded[day]
    val message = when {
        entries.isNotEmpty() -> null
        past && dayState?.fixtures == null && dayState?.failed != true -> R.string.iptv_ui20_sched_loading
        past && dayState?.failed == true && dayState.fixtures.isNullOrEmpty() -> R.string.iptv_ui20_sched_failed
        else -> R.string.iptv_sport5_section_nothing
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        Row(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.iptv_sport5_section_all_scores), style = iptvTitleStyle(), color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val tabs = listOf(FILTER_ALL to stringResource(R.string.iptv_sport4_all), FILTER_LIVE to stringResource(R.string.iptv_sport5_section_live),
                        FILTER_MINE to stringResource(R.string.iptv_sport5_section_my_teams)) + sports.map { FILTER_SPORT + it to sportName(it) }
                    tabs.forEachIndexed { index, (key, label) ->
                        val count = all.count { wanted(it, key) }
                        if (count > 0 || key == FILTER_ALL || key == filter) IptvRailItem(label, count, filter == key,
                            (if (index == 0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) filter = key }, onClick = { filter = key })
                    }
                }
                Text(stringResource(R.string.iptv_sport5_section_scores_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = 8.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScoreDays(strip, summaries, today, day, onSelect = { chosenDay = it.toEpochDay() })
                if (message != null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                        color = if (message == R.string.iptv_ui20_sched_failed) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
                } else key(day) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(entries, key = { entry -> when (entry) { is ScoreEntry.Header -> "h:${entry.league}"; is ScoreEntry.Line -> entry.item.fixture.key } }) { entry ->
                            when (entry) {
                                is ScoreEntry.Header -> ScoreHeader(entry)
                                is ScoreEntry.Line -> {
                                    val item = entry.item
                                    val fixture = item.fixture
                                    ScoreLine(item, hidden(fixture), spoiler(fixture), fixture.key in state.reminders,
                                        item.links.any { it.row.item.channel.id == playingId },
                                        onClick = {
                                            when {
                                                item.links.size == 1 -> onWatch(item.links.first().row)
                                                item.links.isNotEmpty() || (fixture.status != FixtureStatus.FINAL && !past) -> choosing = fixture.key
                                            }
                                        },
                                        onHold = {
                                            when {
                                                spoiler(fixture) -> { days.reveal(fixture.key); onReveal(fixture.key) }
                                                sportCanRemind(fixture) -> onReminder(fixture)
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
        choosing?.let { key ->
            (all.firstOrNull { it.fixture.key == key } ?: state.item(key))?.let { item ->
                FixtureChannelsDialog(item, onWatch = { choosing = null; onWatch(it) }, onDismiss = { choosing = null })
            }
        }
    }
}

@Composable
private fun ScoreHeader(entry: ScoreEntry.Header) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((SportsLeagues.byId(entry.league)?.name ?: entry.league).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        if (entry.live > 0) Text("${entry.live} ${stringResource(R.string.iptv_sport_live)}", style = SportCaps.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold, color = NuvioTheme.colors.Error, maxLines = 1)
    }
}

@Composable
private fun ScoreLine(item: IptvFixtureItem, hidden: Boolean, spoiler: Boolean, reminded: Boolean, playing: Boolean, onClick: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    val fixture = item.fixture
    val live = fixture.status == FixtureStatus.LIVE
    val close = live && !hidden && !item.scheduleOnly && SportsFixtureSections.close(fixture)
    val home = fixture.home
    val away = fixture.away
    val teams = home != null && away != null
    Row(Modifier.fillMaxWidth().height(if (teams) TEAM_LINE else PLAIN_LINE)
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused, playing, LineShape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { held = true; onHold() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) { if (!held) onClick(); held = false }; true } else false
        }
        .focusable().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(if (teams) 44.dp else 22.dp).clip(RoundedCornerShape(2.dp)).background(if (close) NuvioTheme.colors.Warning else Color.Transparent))
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) { CardState(fixture, fixture.sportDetail.takeIf { !item.scheduleOnly }, hidden || item.scheduleOnly) }
        val content = itemContent(focused)
        if (home != null && away != null) {
            CardBands(fixture, home, away, focused, Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp), 22.dp, extra = null) { team, side ->
                BandScore(fixture, team, side, hidden, focused, item.scheduleOnly)
            }
        } else {
            Text(fixture.title, style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            val bug = SportsFixtureText.bug(fixture).takeIf { !hidden && fixture.status != FixtureStatus.SCHEDULED && !item.scheduleOnly }
            if (bug != null && bug.primary != fixture.title) Text(bug.primary, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else if (hidden && fixture.status != FixtureStatus.SCHEDULED) MaskBar(46.dp)
            Spacer(Modifier.width(4.dp))
        }
        if (item.favourite) Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = NuvioTheme.colors.Secondary)
        if (reminded) Icon(Icons.Filled.NotificationsActive, null, Modifier.size(14.dp), tint = NuvioTheme.colors.Secondary)
        val channel = item.links.firstOrNull()?.let { channelName(it.row) + if (item.links.size > 1) " +${item.links.size - 1}" else "" }
        Text(when {
            spoiler && hidden -> stringResource(R.string.iptv_sport5_section_hold_to_show)
            channel != null -> channel
            fixture.status == FixtureStatus.FINAL -> ""
            item.linking -> stringResource(R.string.iptv_sport3_finding_channels)
            else -> stringResource(R.string.iptv_sport_no_channel)
        }, style = MaterialTheme.typography.labelMedium, color = if (channel != null && !(spoiler && hidden)) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(160.dp))
    }
}

@Composable
private fun ScoreDays(strip: List<LocalDate>, summaries: List<ScoreDaySummary>, today: LocalDate, day: LocalDate, onSelect: (LocalDate) -> Unit) {
    val chosen = remember { FocusRequester() }
    var focusedDay by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(focusedDay) { focusedDay?.takeIf { it != day }?.let { delay(DAY_SETTLE); onSelect(it) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = strip.size.coerceAtLeast(1)
        val width = ((maxWidth - DAY_GAP * (count - 1)) / count).coerceIn(DAY_MIN, DAY_MAX)
        Row(Modifier.fillMaxWidth().focusRestorer(chosen).focusGroup().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DAY_GAP)) {
            strip.forEachIndexed { index, date ->
                val summary = summaries.getOrNull(index) ?: ScoreDaySummary(ScoreDayKind.UNKNOWN)
                DayChip(dayName(date, today), dayCount(summary, date.isBefore(today)), summary.kind == ScoreDayKind.LIVE, date == day, width,
                    if (date == day) Modifier.focusRequester(chosen) else Modifier, onFocus = { focusedDay = date }, onClick = { onSelect(date) })
            }
        }
    }
}

@Composable
private fun DayChip(label: String, count: String, live: Boolean, selected: Boolean, width: Dp, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    Column(modifier.width(width).height(58.dp)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .iptvItem(focused, selected, DayShape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (!isSelect(native.keyCode)) return@onPreviewKeyEvent false
            if (native.action == AndroidKeyEvent.ACTION_DOWN) pressed = true
            if (native.action == AndroidKeyEvent.ACTION_UP) { if (pressed) onClick(); pressed = false }
            true
        }
        .focusable().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) itemContent(true) else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.width(10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) NuvioTheme.colors.Secondary else Color.Transparent))
            Text(count, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (live) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary)
        }
    }
}

@Composable
private fun dayName(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.iptv_sport2_today)
    today.minusDays(1) -> stringResource(R.string.iptv_ui20_sched_yesterday)
    today.plusDays(1) -> stringResource(R.string.iptv_sport_tomorrow)
    else -> remember(date) { shortDay(date) }
}

@Composable
private fun dayCount(summary: ScoreDaySummary, past: Boolean): String = when (summary.kind) {
    ScoreDayKind.LIVE -> pluralStringResource(R.plurals.iptv_ui20_sched_live, summary.count, summary.count)
    ScoreDayKind.GAMES -> pluralStringResource(R.plurals.iptv_ui20_sched_games, summary.count, summary.count)
    ScoreDayKind.RESULTS -> pluralStringResource(R.plurals.iptv_ui20_sched_results, summary.count, summary.count)
    ScoreDayKind.NONE -> stringResource(R.string.iptv_ui20_sched_no_games)
    ScoreDayKind.UNKNOWN -> stringResource(if (past) R.string.iptv_ui20_sched_results_label else R.string.iptv_ui20_sched_no_games)
}

private fun shortDay(date: LocalDate): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEd")
    return SimpleDateFormat(pattern, locale).format(Date(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
}
