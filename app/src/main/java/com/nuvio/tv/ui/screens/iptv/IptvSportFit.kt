@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.SportsCountdown

@Composable
internal fun SportFitText(variants: List<AnnotatedString>, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    if (variants.isEmpty()) return
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val chosen = remember(variants, width, bounded, style, maxLines) {
            if (!bounded || variants.size == 1) variants.first()
            else variants.firstOrNull { !measurer.measure(it, style, maxLines = maxLines, constraints = Constraints(maxWidth = width)).hasVisualOverflow }
                ?: variants.last()
        }
        Text(chosen, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SportFitPlain(variants: List<String>, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val texts = remember(variants) { variants.filter(String::isNotEmpty).distinct().map { AnnotatedString(it) } }
    SportFitText(texts, style, color, modifier, maxLines)
}

@Composable
internal fun SportPairText(first: @Composable () -> Unit, second: @Composable () -> Unit, modifier: Modifier = Modifier, gap: Int = 6) {
    Layout(content = { first(); second() }, modifier = modifier) { measurables, constraints ->
        val max = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val space = gap.dp.roundToPx()
        val lead = measurables[0].measure(Constraints(maxWidth = max))
        val tail = measurables.getOrNull(1)?.measure(Constraints())
        val fits = tail != null && tail.width > 0 && lead.width + space + tail.width <= max
        val width = (if (fits) lead.width + space + tail!!.width else lead.width).coerceIn(constraints.minWidth, max)
        val height = maxOf(lead.height, if (fits) tail!!.height else 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val top = (height - lead.height) / 2
            lead.placeRelative(0, top)
            if (fits) {
                val leadBase = lead[FirstBaseline]
                val tailBase = tail!![FirstBaseline]
                val y = if (leadBase > 0 && tailBase > 0) top + leadBase - tailBase else (height - tail.height) / 2
                tail.placeRelative(lead.width + space, y)
            }
        }
    }
}

@Composable
internal fun sportCountdowns(now: Long, start: Long): List<String> {
    val left = SportsCountdown.until(now, start) ?: return listOf(stringResource(R.string.iptv_ui10_sport_starting))
    val long = sportCountdown(now, start)
    return when {
        left.days > 0 -> listOf(long, pluralStringResource(R.plurals.iptv_ui20_sport_in_days, left.days, left.days))
        left.hours > 0 -> listOf(long, stringResource(R.string.iptv_ui20_sport_in_hours_minutes, left.hours, left.minutes),
            stringResource(R.string.iptv_ui20_sport_in_hours, left.hours))
        else -> listOf(long, stringResource(R.string.iptv_ui20_sport_in_minutes, left.minutes))
    }
}
