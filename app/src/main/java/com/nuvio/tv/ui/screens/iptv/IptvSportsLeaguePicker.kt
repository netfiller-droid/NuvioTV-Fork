@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LeagueSource
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeaguePicker
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere

private val TileShape = RoundedCornerShape(14.dp)
private val CrestShape = RoundedCornerShape(10.dp)
private val TILE_HEIGHT = 140.dp
private val CREST = 40.dp

@Composable
internal fun IptvSportsLeaguePicker(offered: List<SportsLeague>, chosen: Set<String>, hasKey: Boolean, crests: Map<String, String>,
    onSave: (List<String>) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit, overlay: @Composable () -> Unit = {}) {
    var selected by remember(chosen) { mutableStateOf(chosen) }
    val sports = remember(offered, selected) { SportsLeaguePicker.sports(offered, selected) }
    var sport by rememberSaveable { mutableStateOf(sports.firstOrNull { it.chosen > 0 }?.sport ?: sports.firstOrNull()?.sport.orEmpty()) }
    val current = sports.firstOrNull { it.sport == sport } ?: sports.firstOrNull()
    val sportFocus = remember { FocusRequester() }
    val firstTile = remember(current?.sport) { FocusRequester() }
    fun save() { if (selected != chosen) onSave(SportsLeaguePicker.saved(offered, selected)) }
    Dialog(onDismissRequest = { save(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { sportFocus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
            if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
            Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsDetailHeader(stringResource(R.string.iptv_sport_leagues), stringResource(R.string.iptv_sport7_leagues_subtitle))
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.weight(1f).fillMaxWidth().focusRestorer(sportFocus).focusGroup().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sports.forEach { item ->
                            val on = item.sport == current?.sport
                            PickerRailRow(sportName(item.sport), stringResource(R.string.iptv_ui20_sched_league_count, item.chosen, item.leagues.size), on,
                                if (on) Modifier.focusRequester(sportFocus) else Modifier, onFocus = { sport = item.sport }, onClick = {
                                    runCatching { firstTile.requestFocus() }
                                })
                        }
                    }
                    PickerRailRow(stringResource(R.string.iptv_sport7_add_league), null, false, Modifier, icon = Icons.Filled.Add,
                        onClick = { save(); onAdd() })
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val columns = SportsLeaguePicker.columns(maxWidth.value)
                        if (current != null) key(current.sport) {
                            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().focusRestorer(firstTile),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(SportsLeaguePicker.GAP_DP.dp),
                                verticalArrangement = Arrangement.spacedBy(SportsLeaguePicker.GAP_DP.dp)) {
                                itemsIndexed(current.leagues, key = { _, league -> league.id }) { index, league ->
                                    LeagueTile(league, league.id in selected, crests[league.id], SportsLeaguePicker.source(league, hasKey),
                                        (if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
                                            .focusProperties { if (index % columns == 0) left = sportFocus },
                                        onToggle = { selected = SportsLeaguePicker.toggle(selected, league.id) })
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.iptv_ui20_sched_leagues_hint), style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.iptv_ui20_sched_leagues_note), style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            overlay()
        }
    }
}

@Composable
private fun PickerRailRow(text: String, count: String?, selected: Boolean, modifier: Modifier, icon: ImageVector? = null, onFocus: () -> Unit = {},
    onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 46.dp)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .iptvItem(focused, selected)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (!isSelect(native.keyCode)) return@onPreviewKeyEvent false
            if (native.action == AndroidKeyEvent.ACTION_DOWN) pressed = true
            if (native.action == AndroidKeyEvent.ACTION_UP) { if (pressed) onClick(); pressed = false }
            true
        }
        .focusable().padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val content = if (focused) itemContent(true) else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) NuvioTheme.colors.Secondary else Color.Transparent))
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(text, style = iptvItemStyle(selected || focused), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null) Text(count, style = iptvMetaStyle().copy(fontFeatureSettings = "tnum"), color = NuvioTheme.colors.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun LeagueTile(league: SportsLeague, on: Boolean, crest: String?, source: LeagueSource, modifier: Modifier, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().height(TILE_HEIGHT)
        .onFocusChanged { focused = it.isFocused }
        .then(if (on && !focused) Modifier.border(1.5.dp, NuvioTheme.colors.Secondary.copy(alpha = .6f), TileShape) else Modifier)
        .iptvItem(focused, on, TileShape)
        .then(if (!on && !focused) Modifier.background(NuvioTheme.colors.TextPrimary.copy(alpha = .04f), TileShape) else Modifier)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (!isSelect(native.keyCode)) return@onPreviewKeyEvent false
            if (native.action == AndroidKeyEvent.ACTION_DOWN) pressed = true
            if (native.action == AndroidKeyEvent.ACTION_UP) { if (pressed) onToggle(); pressed = false }
            true
        }
        .focusable().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            LeagueCrest(crest, league.name, CREST)
            Spacer(Modifier.weight(1f))
            if (on) Icon(Icons.Filled.CheckCircle, stringResource(R.string.cd_selected), Modifier.size(20.dp), tint = NuvioTheme.colors.Secondary)
        }
        Text(league.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(stringResource(sourceText(source)), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (source == LeagueSource.NEEDS_KEY) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary)
    }
}

@Composable
private fun LeagueCrest(url: String?, name: String, size: Dp) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val inset = size / 8
    val pixels = with(LocalDensity.current) { (size - inset * 2).roundToPx() }.coerceAtLeast(1)
    val shown = url != null && !failed
    val backed = shown && !url.orEmpty().contains("-dark")
    Box(Modifier.size(size).clip(CrestShape).background(when {
        backed -> Color.White.copy(alpha = .9f)
        shown -> Color.Transparent
        else -> NuvioTheme.colors.TextPrimary.copy(alpha = .08f)
    }, CrestShape), contentAlignment = Alignment.Center) {
        if (url != null && !failed) {
            val request = remember(url, pixels) { ImageRequest.Builder(context).data(url).size(pixels, pixels).build() }
            AsyncImage(request, null, Modifier.fillMaxSize().padding(inset), contentScale = ContentScale.Fit, onError = { failed = true })
        } else {
            Text(SportsLeaguePicker.monogram(name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
    }
}

private fun sourceText(source: LeagueSource): Int = when (source) {
    LeagueSource.ESPN -> R.string.iptv_sport7_source_espn
    LeagueSource.BOTH -> R.string.iptv_ui20_sched_source_both
    LeagueSource.THESPORTSDB -> R.string.iptv_sport7_source_sportsdb
    LeagueSource.NEEDS_KEY -> R.string.iptv_ui20_sched_source_needs_key
}
