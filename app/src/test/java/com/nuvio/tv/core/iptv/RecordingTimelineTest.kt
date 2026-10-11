package com.nuvio.tv.core.iptv

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    private class Run(val packets: Int, val pcrStart: Long, val pid: Int = 0x100)

    private fun stream(vararg runs: Run, ticksPerPacket: Long = 900, pcrEvery: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        runs.forEach { run ->
            repeat(run.packets) { index ->
                val packet = ByteArray(TsClock.PACKET) { 0x11 }
                packet[0] = 0x47
                packet[1] = (run.pid shr 8).toByte()
                packet[2] = run.pid.toByte()
                if (index % pcrEvery == 0) {
                    val pcr = (run.pcrStart + index * ticksPerPacket) and TsClock.MASK
                    packet[3] = 0x30
                    packet[4] = 7
                    packet[5] = 0x10
                    packet[6] = (pcr shr 25).toByte()
                    packet[7] = (pcr shr 17).toByte()
                    packet[8] = (pcr shr 9).toByte()
                    packet[9] = (pcr shr 1).toByte()
                    packet[10] = ((pcr and 1) shl 7).toByte()
                } else packet[3] = 0x10
                out.write(packet)
            }
        }
        return out.toByteArray()
    }

    private fun feed(clock: RecordingClock, data: ByteArray, offset: Long = 0, chunk: Int = 1_000) {
        var at = 0
        while (at < data.size) {
            val size = minOf(chunk, data.size - at)
            clock.feed(offset + at, data, at, size)
            at += size
        }
    }

    @Test fun clockMarksPacketAlignedOffsetsEveryFewSeconds() {
        val data = stream(Run(12_000, 1_000_000))
        val clock = RecordingClock()
        clock.cut(0)
        feed(clock, data, chunk = 777)
        val timeline = requireNotNull(clock.timeline(data.size.toLong()))
        assertEquals(RecordingMark(0, 0), timeline.marks.first())
        assertTrue(timeline.marks.all { it.offset % TsClock.PACKET == 0L && !it.cut })
        assertTrue(timeline.marks.zipWithNext().all { (a, b) -> b.millis - a.millis in 4_000L..4_100L })
        assertEquals(119_960L, timeline.endMillis)
        assertTrue(timeline.marks.size in 29..31)
    }

    @Test fun reconnectAndTimestampJumpBecomeCuts() {
        val first = stream(Run(1_000, 5_000))
        val second = stream(Run(1_000, 7_000_000_000))
        val third = stream(Run(1_000, 123))
        val clock = RecordingClock()
        clock.cut(0)
        feed(clock, first)
        clock.cut(first.size.toLong())
        feed(clock, second, first.size.toLong())
        feed(clock, third, (first.size + second.size).toLong())
        val timeline = requireNotNull(clock.timeline((first.size + second.size + third.size).toLong()))
        val cuts = timeline.marks.filter { it.cut }
        assertEquals(listOf(first.size.toLong(), (first.size + second.size).toLong()), cuts.map { it.offset })
        assertEquals(9_960L, cuts[0].millis)
        assertEquals(19_920L, cuts[1].millis)
        assertEquals(29_880L, timeline.endMillis)
    }

    @Test fun reconnectWithANewClockPidKeepsEveryPcr() {
        val first = stream(Run(6_000, 5_000))
        val second = stream(Run(6_000, 3_000_000_000, pid = 0x200))
        val clock = RecordingClock()
        clock.cut(0)
        feed(clock, first)
        clock.cut(first.size.toLong())
        feed(clock, second, first.size.toLong())
        val timeline = requireNotNull(clock.timeline((first.size + second.size).toLong()))
        assertEquals(listOf(first.size.toLong()), timeline.marks.filter { it.cut }.map { it.offset })
        assertEquals(59_960L, timeline.marks.first { it.cut }.millis)
        assertEquals(119_920L, timeline.endMillis)
    }

    @Test fun clockPidSwitchWithoutReconnectCutsAtTheFirstNewPcr() {
        val first = stream(Run(6_000, 5_000))
        val second = stream(Run(6_000, 3_000_000_000, pid = 0x200))
        val data = first + second
        val clock = RecordingClock()
        clock.cut(0)
        feed(clock, data, chunk = 1_316)
        val timeline = requireNotNull(clock.timeline(data.size.toLong()))
        assertEquals(listOf(first.size.toLong()), timeline.marks.filter { it.cut }.map { it.offset })
        assertEquals(59_960L, timeline.marks.first { it.cut }.millis)
        assertEquals(119_920L, timeline.endMillis)
        assertTrue(timeline.marks.zipWithNext().all { (a, b) -> b.millis - a.millis <= 8_100L })
    }

    @Test fun strayPcrsFromAnotherPidAreIgnored() {
        val main = stream(Run(6_000, 5_000))
        val stray = stream(Run(40, 9_000_000, pid = 0x300))
        val data = main.copyOf(188 * 3_000) + stray + main.copyOfRange(188 * 3_000, main.size)
        val clock = RecordingClock()
        clock.cut(0)
        feed(clock, data)
        val timeline = requireNotNull(clock.timeline(data.size.toLong()))
        assertTrue(timeline.marks.none { it.cut })
        assertEquals(59_960L, timeline.endMillis)
    }

    @Test fun clockFollowsTheTimestampWrap() {
        val data = stream(Run(3_000, TsClock.MASK - 900L * 1_000))
        val clock = RecordingClock()
        clock.cut(0)
        feed(clock, data)
        val timeline = requireNotNull(clock.timeline(data.size.toLong()))
        assertTrue(timeline.marks.none { it.cut })
        assertEquals(29_960L, timeline.endMillis)
    }

    @Test fun rollbackDropsLaterMarksAndRestoresTime() {
        val data = stream(Run(3_000, 0))
        val clock = RecordingClock()
        clock.cut(0)
        val keep = 188L * 1_500
        feed(clock, data.copyOf(188 * 2_000))
        clock.rollback(keep)
        feed(clock, data.copyOfRange(keep.toInt(), data.size), keep)
        val timeline = requireNotNull(clock.timeline(data.size.toLong()))
        assertTrue(timeline.marks.filter { it.offset < keep }.none { it.cut })
        assertEquals(1, timeline.marks.count { it.cut })
        assertEquals(keep, timeline.marks.first { it.cut }.offset)
        assertTrue(timeline.marks.first { it.cut }.millis in 14_900L..15_000L)
    }

    @Test fun clockWithoutClockReferencesHasNoTimeline() {
        val clock = RecordingClock()
        clock.cut(0)
        clock.feed(0, ByteArray(188 * 50) { if (it % 188 == 0) 0x47 else 0 }, 0, 188 * 50)
        assertNull(clock.timeline(188L * 50))
    }

    @Test fun timelineTextRoundTripsAndRejectsDamage() {
        val timeline = RecordingTimeline(listOf(RecordingMark(0, 0), RecordingMark(1_880, 4_000), RecordingMark(9_400, 4_100, cut = true)), 20_000, 9_000)
        assertEquals(timeline, RecordingTimeline.decode(timeline.encode()))
        assertEquals(timeline.copy(probed = true), RecordingTimeline.decode(timeline.copy(probed = true).encode()))
        assertNull(RecordingTimeline.decode("v1 10 10\n5 0\n-10 0\n"))
        assertNull(RecordingTimeline.decode("v9 1 1\n0 0\n"))
        assertNull(RecordingTimeline.decode(""))
    }

    @Test fun savedIndexIsTrustedOnlyWhenItCoversTheFile() {
        val clock = RecordingTimeline(listOf(RecordingMark(0, 0)), 100L shl 20, 600_000)
        assertTrue(clock.covers(100L shl 20))
        assertTrue(clock.covers((100L shl 20) + RecordingTimeline.UNCOVERED_BYTES - 1))
        assertFalse(clock.covers((100L shl 20) + RecordingTimeline.UNCOVERED_BYTES))
        assertFalse(clock.covers(160L shl 20))
        assertTrue(clock.covers(90L shl 20))
        assertTrue(clock.copy(probed = true).covers(100L shl 20))
        assertFalse(clock.copy(probed = true).covers((100L shl 20) + 1))
    }

    @Test fun probeFindsRunsAndCutsInAnOldRecording() {
        val runs = arrayOf(Run(40_000, 3_000_000), Run(30_000, 900_000_000), Run(50_000, TsClock.MASK - 90_000L * 60))
        val data = stream(*runs)
        var reads = 0
        val timeline = RecordingProbe.timeline(data.size.toLong(), 20 * 60_000L) { offset, size ->
            reads += 1
            data.copyOfRange(offset.toInt(), (offset + size).toInt().coerceAtMost(data.size))
        }
        assertTrue(timeline.probed)
        val cuts = timeline.marks.filter { it.cut }
        assertEquals(listOf(188L * 40_000, 188L * 70_000), cuts.map { it.offset })
        assertTrue(kotlin.math.abs(timeline.endMillis - 1_200_000) < 1_000)
        assertTrue(kotlin.math.abs(cuts[0].millis - 400_000) < 500)
        assertTrue(kotlin.math.abs(cuts[1].millis - 700_000) < 500)
        assertTrue(reads < 120)
    }

    @Test fun probeFollowsAClockPidChange() {
        val data = stream(Run(40_000, 3_000_000), Run(40_000, 900_000_000, pid = 0x200), Run(40_000, 4_000_000_000, pid = 0x300))
        val timeline = RecordingProbe.timeline(data.size.toLong(), 20 * 60_000L) { offset, size ->
            data.copyOfRange(offset.toInt(), (offset + size).toInt().coerceAtMost(data.size))
        }
        val cuts = timeline.marks.filter { it.cut }
        assertEquals(listOf(188L * 40_000, 188L * 80_000), cuts.map { it.offset })
        assertTrue(kotlin.math.abs(cuts[0].millis - 400_000) < 500)
        assertTrue(kotlin.math.abs(cuts[1].millis - 800_000) < 500)
        assertTrue(kotlin.math.abs(timeline.endMillis - 1_200_000) < 1_000)
        val segments = RecordingVodPlaylist.segments(timeline, data.size.toLong())
        assertEquals(2, RecordingVodPlaylist.text(segments, "r.ts").lines().count { it == "#EXT-X-DISCONTINUITY" })
        assertEquals(listOf(188L * 40_000, 188L * 80_000), segments.filter { it.cut }.map { it.offset })
    }

    @Test fun probeKeepsTheSameClockAcrossAContinuousPidChange() {
        val data = stream(Run(40_000, 3_000_000), Run(40_000, 3_000_000 + 900L * 40_000, pid = 0x200))
        val timeline = RecordingProbe.timeline(data.size.toLong(), 0) { offset, size ->
            data.copyOfRange(offset.toInt(), (offset + size).toInt().coerceAtMost(data.size))
        }
        assertEquals(listOf(188L * 40_000), timeline.marks.filter { it.cut }.map { it.offset })
        assertTrue(kotlin.math.abs(timeline.endMillis - 800_000) < 1_000)
    }

    @Test fun probeFallsBackToTheWallClockWithoutTimestamps() {
        val data = ByteArray(188 * 2_000) { if (it % 188 == 0) 0x47 else 0 }
        val timeline = RecordingProbe.timeline(data.size.toLong(), 600_000) { offset, size -> data.copyOfRange(offset.toInt(), (offset + size).toInt().coerceAtMost(data.size)) }
        assertEquals(listOf(RecordingMark(0, 0)), timeline.marks)
        assertEquals(600_000L, timeline.endMillis)
        assertEquals(data.size.toLong(), timeline.endOffset)
    }

    @Test fun playlistSplitsAlignedSegmentsWithDiscontinuities() {
        val timeline = RecordingTimeline(listOf(RecordingMark(0, 0), RecordingMark(188L * 400, 4_000), RecordingMark(188L * 1_000, 10_000, cut = true)),
            188L * 1_600, 16_000)
        val segments = RecordingVodPlaylist.segments(timeline, 188L * 1_600)
        assertEquals(188L * 1_600, segments.sumOf { it.length })
        assertEquals(16_000L, RecordingVodPlaylist.durationMillis(segments))
        assertTrue(segments.all { it.offset % TsClock.PACKET == 0L && it.length > 0 && it.millis in 1..RecordingVodPlaylist.TARGET_MILLIS })
        assertEquals(listOf(false, false, true), segments.map { it.cut })
        assertEquals(segments.zipWithNext().map { (a, _) -> a.offset + a.length }, segments.drop(1).map { it.offset })
        val text = RecordingVodPlaylist.text(segments, "recording.ts")
        val lines = text.lines()
        assertEquals("#EXTM3U", lines.first())
        assertTrue("#EXT-X-TARGETDURATION:6" in lines && "#EXT-X-PLAYLIST-TYPE:VOD" in lines && "#EXT-X-ENDLIST" in lines)
        assertEquals(1, lines.count { it == "#EXT-X-DISCONTINUITY" })
        assertTrue("#EXTINF:4.000," in lines)
        assertTrue("#EXT-X-BYTERANGE:112800@75200" in lines)
        assertEquals(segments.size, lines.count { it == "recording.ts" })
    }

    @Test fun playlistSplitsLongSpansAndExtendsPastTheIndex() {
        val even = RecordingVodPlaylist.segments(RecordingTimeline(listOf(RecordingMark(0, 0)), 188L * 10_000, 60_000), 188L * 10_000)
        assertEquals(10, even.size)
        assertEquals(60_000L, RecordingVodPlaylist.durationMillis(even))
        val longer = RecordingVodPlaylist.segments(RecordingTimeline(listOf(RecordingMark(0, 0), RecordingMark(188L * 1_000, 10_000)), 188L * 2_000, 20_000),
            188L * 3_000)
        assertEquals(188L * 3_000, longer.sumOf { it.length })
        assertTrue(kotlin.math.abs(RecordingVodPlaylist.durationMillis(longer) - 30_000) <= 2)
        val shorter = RecordingVodPlaylist.segments(RecordingTimeline(listOf(RecordingMark(0, 0), RecordingMark(188L * 1_000, 10_000)), 188L * 2_000, 20_000),
            188L * 1_500)
        assertTrue(kotlin.math.abs(RecordingVodPlaylist.durationMillis(shorter) - 15_000) <= 2)
        assertTrue(RecordingVodPlaylist.segments(RecordingTimeline(listOf(RecordingMark(0, 0)), 0, 0), 0).isEmpty())
    }

    @Test fun resumeKeepsTheMiddleOnly() {
        assertNull(RecordingResume.keep(10_000, 3_600_000))
        assertEquals(1_800_000L, RecordingResume.keep(1_800_000, 3_600_000))
        assertNull(RecordingResume.keep(3_550_000, 3_600_000))
        assertNull(RecordingResume.keep(10_700_000, 10_800_000))
        assertEquals(10_500_000L, RecordingResume.keep(10_500_000, 10_800_000))
        assertEquals(40_000L, RecordingResume.keep(40_000, 0))
        assertEquals(0.5f, RecordingResume.fraction(1_800_000, 3_600_000))
        assertNull(RecordingResume.fraction(null, 3_600_000))
        assertNull(RecordingResume.fraction(1_000, 0))
    }
}
