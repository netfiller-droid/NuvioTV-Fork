@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.MultiviewLabels
import com.nuvio.tv.core.iptv.MultiviewStrip
import com.nuvio.tv.core.iptv.MultiviewStripSide
import com.nuvio.tv.core.iptv.MultiviewStripSize
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.TeamColours
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

@Composable
internal fun MultiviewTileStrip(fixture: SportsFixture?, hidden: Boolean, row: IptvListedChannel?, programme: GuideProgramme?, now: Long,
    size: MultiviewStripSize, pictureWidth: Float, modifier: Modifier = Modifier) {
    if (fixture == null && row == null) return
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Black.copy(alpha = .84f))))) {
        if (fixture != null) StripScore(fixture, hidden, size)
        if (row != null) StripLabel(row, programme, size, logo = fixture == null, maxName = (pictureWidth * .45f).dp)
        val fraction = programme?.takeIf { row != null }?.let { progress(it, now) }
        if (fraction != null) Box(Modifier.fillMaxWidth().height(size.progress.dp).background(Color.White.copy(alpha = .14f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(NuvioTheme.palette.accentBrush()))
        }
    }
}

internal fun multiviewLabelsLabel(labels: MultiviewLabels): Int = when (labels) {
    MultiviewLabels.ALWAYS -> R.string.iptv_ui20_multiview_labels_always
    MultiviewLabels.FOCUSED -> R.string.iptv_ui20_multiview_labels_focused
    MultiviewLabels.OFF -> R.string.iptv_ui20_multiview_labels_off
}

private fun stripStyle(base: TextStyle, size: Float): TextStyle = base.copy(fontSize = size.sp, lineHeight = (size * 1.2f).sp)

@Composable
private fun StripScore(fixture: SportsFixture, hidden: Boolean, size: MultiviewStripSize) {
    val sides = MultiviewStrip.sides(fixture)
    val gap = (size.padding * .75f).dp
    Row(Modifier.fillMaxWidth().height(size.line.dp).padding(horizontal = size.padding.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)) {
        if (sides == null) Text(MultiviewStrip.eventText(fixture, hidden), style = stripStyle(MaterialTheme.typography.labelLarge, size.name),
            fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else {
            StripTeam(sides[0], size, end = false, modifier = Modifier.weight(1f))
            StripCentre(fixture, sides[0], sides[1], hidden, size)
            StripTeam(sides[1], size, end = true, modifier = Modifier.weight(1f))
        }
        Box(Modifier.width(1.dp).height((size.crest * .8f).dp).background(Color.White.copy(alpha = .25f)))
        val live = fixture.status == FixtureStatus.LIVE
        Text(if (live) MultiviewStrip.liveState(fixture, hidden) ?: stringResource(R.string.iptv_sport_live) else clock(fixture.startMillis),
            style = stripStyle(MaterialTheme.typography.labelMedium, size.state).copy(fontFeatureSettings = "tnum"),
            fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold, color = if (live) NuvioTheme.colors.Error else Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = (size.state * 8f).dp))
    }
}

@Composable
private fun StripTeam(side: MultiviewStripSide, size: MultiviewStripSize, end: Boolean, modifier: Modifier) {
    val stripe = remember(side.team.colour) { Color(TeamColours.stripe(side.team.colour, STRIP_BASE)) }
    val gap = (size.padding * .75f).dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap, if (end) Alignment.End else Alignment.Start)) {
        if (!end) { StripMark(stripe, size); TeamLogo(side.team, size.crest.dp) }
        Text(MultiviewStrip.teamText(side.team, size.compact), style = stripStyle(MaterialTheme.typography.labelLarge, size.name),
            fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (end) TextAlign.End else TextAlign.Start, modifier = Modifier.weight(1f, fill = false))
        if (end) { TeamLogo(side.team, size.crest.dp); StripMark(stripe, size) }
    }
}

@Composable
private fun StripMark(colour: Color, size: MultiviewStripSize) {
    Box(Modifier.width(size.stripe.dp).height((size.crest * .9f).dp).clip(RoundedCornerShape((size.stripe / 2f).dp)).background(colour))
}

@Composable
private fun StripCentre(fixture: SportsFixture, first: MultiviewStripSide, second: MultiviewStripSide, hidden: Boolean, size: MultiviewStripSize) {
    val big = stripStyle(MaterialTheme.typography.titleMedium, size.score).copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val small = big.copy(fontSize = (size.score * .75f).sp, fontWeight = FontWeight.SemiBold)
    val a = first.score
    val b = second.score
    when {
        a == null || b == null -> Text(stringResource(if (SportsFixtureText.awayFirst(fixture)) R.string.iptv_ui25_sep_at else R.string.iptv_ui25_sep_versus), style = small, color = Color.White.copy(alpha = .6f), maxLines = 1)
        hidden -> Box(Modifier.width((size.score * 2.4f).dp).height((size.score * .7f).dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .22f)))
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((size.padding * .5f).dp)) {
            Text(a, style = big, color = if (first.behind) Color.White.copy(alpha = .62f) else Color.White, maxLines = 1)
            Text("–", style = small, color = Color.White.copy(alpha = .45f), maxLines = 1)
            Text(b, style = big, color = if (second.behind) Color.White.copy(alpha = .62f) else Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun StripLabel(row: IptvListedChannel, programme: GuideProgramme?, size: MultiviewStripSize, logo: Boolean, maxName: Dp) {
    val showing = programme?.let(::title)?.takeIf(String::isNotBlank)
    val style = stripStyle(MaterialTheme.typography.labelMedium, size.label)
    Row(Modifier.fillMaxWidth().height((if (logo) size.labelLine * 1.35f else size.labelLine).dp).padding(horizontal = size.padding.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((size.padding * .75f).dp)) {
        if (logo) StripLogo(logoUrl(row), (size.labelLine * 1.7f).dp, size.labelLine.dp)
        Text(channelName(row), style = style, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (showing != null) Modifier.widthIn(max = maxName) else Modifier.weight(1f))
        if (showing != null) Text(showing, style = style, color = Color.White.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StripLogo(url: String?, width: Dp, height: Dp) {
    val link = url?.takeIf(String::isNotBlank) ?: return
    var failed by remember(link) { mutableStateOf(false) }
    if (failed) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixelWidth = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val pixelHeight = with(density) { height.roundToPx() }.coerceAtLeast(1)
    val request = remember(link, pixelWidth, pixelHeight) { ImageRequest.Builder(context).data(link).size(pixelWidth, pixelHeight).build() }
    AsyncImage(request, null, Modifier.size(width, height), contentScale = ContentScale.Fit, onError = { failed = true })
}

private const val STRIP_BASE = 0xFF141414.toInt()
