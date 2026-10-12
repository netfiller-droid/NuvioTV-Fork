package com.nuvio.tv.core.iptv

class LiveBehindGauge(private val showMillis: Long = SHOW_MILLIS, private val hideMillis: Long = HIDE_MILLIS) {
    private val times = LongArray(SLOTS)
    private val values = LongArray(SLOTS)
    private var first = 0
    private var count = 0
    private var mode = NONE
    private var since = 0L
    private var joined: Long? = null
    private var lowestTarget: Long? = null
    var millis: Long? = null
        private set
    var shown = false
        private set

    @Synchronized fun reset() { clear(NONE, 0L); millis = null; shown = false }

    @Synchronized fun steady(nowMs: Long, behindMs: Long?): Long? {
        enter(STEADY, nowMs)
        return settle(behindMs?.coerceAtLeast(0), true)
    }

    @Synchronized fun clock(nowMs: Long, liveOffsetMs: Long, targetOffsetMs: Long?, ready: Boolean): Long? {
        enter(CLOCK, nowMs)
        if (joined == null && !ready) return settle(null, false)
        val join = joined ?: liveOffsetMs.also { joined = it }
        targetOffsetMs?.takeIf { it >= 0 }?.let { target -> lowestTarget = minOf(lowestTarget ?: target, target) }
        return settle((liveOffsetMs - maxOf(join, lowestTarget ?: join)).coerceAtLeast(0), true)
    }

    @Synchronized fun playlist(nowMs: Long, behindMs: Long, segmentMs: Long?): Long? {
        enter(PLAYLIST, nowMs)
        val span = span(segmentMs)
        return settle(lowest(nowMs, behindMs.coerceAtLeast(0), span), nowMs - since >= span)
    }

    private fun enter(next: Int, nowMs: Long) { if (mode != next) clear(next, nowMs) }

    private fun clear(next: Int, nowMs: Long) { first = 0; count = 0; mode = next; since = nowMs; joined = null; lowestTarget = null }

    private fun settle(value: Long?, warmed: Boolean): Long? {
        millis = value
        shown = when {
            value == null || value < hideMillis -> false
            value >= showMillis && warmed -> true
            else -> shown
        }
        return value
    }

    private fun lowest(nowMs: Long, value: Long, spanMs: Long): Long {
        while (count > 0 && nowMs - times[first] >= spanMs) { first = (first + 1) % SLOTS; count-- }
        val last = (first + count - 1) % SLOTS
        if (count > 0 && nowMs - times[last] < MERGE_MILLIS) values[last] = minOf(values[last], value)
        else {
            if (count == SLOTS) { first = (first + 1) % SLOTS; count-- }
            val slot = (first + count) % SLOTS
            times[slot] = nowMs; values[slot] = value; count++
        }
        var low = value
        for (i in 0 until count) low = minOf(low, values[(first + i) % SLOTS])
        return low
    }

    companion object {
        const val SHOW_MILLIS = 5_000L
        const val HIDE_MILLIS = 3_000L
        const val DEFAULT_SEGMENT_MILLIS = 6_000L
        const val MIN_SPAN_MILLIS = 3_000L
        const val MAX_SPAN_MILLIS = 15_000L
        private const val MERGE_MILLIS = 500L
        private const val SLOTS = 32
        private const val NONE = 0
        private const val STEADY = 1
        private const val CLOCK = 2
        private const val PLAYLIST = 3

        fun span(segmentMs: Long?): Long = ((segmentMs?.takeIf { it > 0 } ?: DEFAULT_SEGMENT_MILLIS) * 3 / 2).coerceIn(MIN_SPAN_MILLIS, MAX_SPAN_MILLIS)
    }
}
