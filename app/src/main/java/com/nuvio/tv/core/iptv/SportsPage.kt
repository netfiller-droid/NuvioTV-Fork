package com.nuvio.tv.core.iptv

enum class SportsFill { LATER, RESULTS, TOMORROW, TEAMS_NEXT }

data class SportsFillSegment(val kind: SportsFill, val fixtures: List<SportsFixture>)

data class SportsShelf(val row: FixtureRow, val segments: List<SportsFillSegment> = emptyList()) {
    val fixtures: List<SportsFixture> get() = row.fixtures + segments.flatMap { it.fixtures }
}

enum class FormMark { WIN, DRAW, LOSS }

data class SportsStanding(val position: Int?, val points: String?)

data class SportsMeeting(val dateMillis: Long?, val first: String, val second: String)

object SportsPage {
    const val CARD_MIN_DP = 196f
    const val CARD_GAP_DP = 12f
    const val MAX_PER_ROW = 12
    const val FORM_LENGTH = 5
    private val SCORE = Regex("^\\s*(\\d{1,3})\\s*[-–]\\s*(\\d{1,3})")

    fun perRow(widthDp: Float, minDp: Float = CARD_MIN_DP, gapDp: Float = CARD_GAP_DP): Int =
        if (widthDp <= 0f) 1 else ((widthDp + gapDp) / (minDp + gapDp)).toInt().coerceIn(1, MAX_PER_ROW)

    fun cardWidth(widthDp: Float, perRow: Int, gapDp: Float = CARD_GAP_DP): Float =
        ((widthDp - gapDp * (perRow.coerceAtLeast(1) - 1)) / perRow.coerceAtLeast(1)).coerceAtLeast(0f)

    fun shelves(rows: List<FixtureRow>, capacity: Int, favourites: Set<String>, nowMillis: Long): List<SportsShelf> {
        fun of(section: FixtureSection) = rows.firstOrNull { it.section == section }?.fixtures.orEmpty()
        val later = of(FixtureSection.TODAY)
        val results = of(FixtureSection.FINISHED)
        val tomorrow = of(FixtureSection.TOMORROW)
        val next = teamsNext(rows.flatMap { it.fixtures }, favourites, nowMillis)
        val absorbed = HashSet<String>()
        val shelves = mutableListOf<SportsShelf>()
        rows.forEach { row ->
            val keys = row.fixtures.map { it.key }
            if (shelves.isNotEmpty() && row.section != FixtureSection.CLOSE && keys.isNotEmpty() && keys.all { it in absorbed }) return@forEach
            val sources = when (row.section) {
                FixtureSection.LIVE -> listOf(SportsFill.LATER to later, SportsFill.RESULTS to results, SportsFill.TOMORROW to tomorrow, SportsFill.TEAMS_NEXT to next)
                FixtureSection.TODAY -> listOf(SportsFill.RESULTS to results, SportsFill.TOMORROW to tomorrow, SportsFill.TEAMS_NEXT to next)
                FixtureSection.FINISHED -> listOf(SportsFill.TOMORROW to tomorrow, SportsFill.TEAMS_NEXT to next)
                FixtureSection.TOMORROW, FixtureSection.DAY -> listOf(SportsFill.TEAMS_NEXT to next)
                FixtureSection.CLOSE -> emptyList()
            }
            val after = if (row.section == FixtureSection.TOMORROW || row.section == FixtureSection.DAY) row.fixtures.maxOfOrNull { it.startMillis } ?: Long.MIN_VALUE
                else Long.MIN_VALUE
            val seen = HashSet(keys)
            var count = keys.size
            val segments = mutableListOf<SportsFillSegment>()
            if (count in 1 until capacity) for ((kind, list) in sources) {
                if (count >= capacity) break
                val picked = list.filter { it.key !in seen && (kind != SportsFill.TEAMS_NEXT || it.startMillis > after) }.take(capacity - count)
                if (picked.isEmpty()) continue
                picked.forEach { seen += it.key }
                count += picked.size
                segments += SportsFillSegment(kind, picked)
            }
            segments.forEach { segment -> segment.fixtures.forEach { absorbed += it.key } }
            shelves += SportsShelf(row, segments)
        }
        return shelves
    }

    fun teamsNext(fixtures: List<SportsFixture>, favourites: Set<String>, nowMillis: Long): List<SportsFixture> {
        if (favourites.isEmpty()) return emptyList()
        val covered = HashSet<String>()
        val picked = mutableListOf<SportsFixture>()
        fixtures.asSequence().filter { it.status == FixtureStatus.SCHEDULED && it.startMillis + SportsRefresh.durationMillis(it) > nowMillis }
            .distinctBy { it.key }.sortedBy { it.startMillis }.forEach { fixture ->
                val keys = listOfNotNull(fixture.home, fixture.away).flatMap { SportsFavourites.matching(favourites, fixture.league, it) }
                if (keys.any { it !in covered }) { covered += keys; picked += fixture }
            }
        return picked
    }

    fun form(text: String?): List<FormMark>? = text?.trim()?.uppercase()?.mapNotNull { char ->
        when (char) {
            'W' -> FormMark.WIN
            'D', 'T' -> FormMark.DRAW
            'L' -> FormMark.LOSS
            else -> null
        }
    }?.takeLast(FORM_LENGTH)?.takeIf { it.isNotEmpty() }

    fun standing(summary: SportsSummary?, side: FixtureSide): SportsStanding? {
        val table = summary?.tables?.firstOrNull { t -> t.rows.any { it.side == FixtureSide.HOME } && t.rows.any { it.side == FixtureSide.AWAY } } ?: return null
        val row = table.rows.firstOrNull { it.side == side } ?: return null
        fun stat(vararg keys: String) = row.stats.firstOrNull { stat -> keys.any { it.equals(stat.first, ignoreCase = true) } }?.second?.trim()?.takeIf(String::isNotEmpty)
        val position = row.rank ?: stat("RANK", "R", "POS")?.toIntOrNull()?.takeIf { it in 1..999 }
        val points = stat("PTS")?.takeIf { it.length <= 5 }
        return if (position == null && points == null) null else SportsStanding(position, points)
    }

    fun lastMeeting(summary: SportsSummary?, firstSide: FixtureSide): SportsMeeting? {
        val meetings = summary?.headToHead.orEmpty().filter { it.side != null && it.score != null && it.result != null }
        val last = meetings.filter { it.dateMillis != null }.maxByOrNull { it.dateMillis!! } ?: meetings.firstOrNull() ?: return null
        val (a, b) = SCORE.find(last.score!!)?.destructured?.let { (x, y) -> x.toInt() to y.toInt() } ?: return null
        val high = maxOf(a, b).toString()
        val low = minOf(a, b).toString()
        val (own, other) = when (last.result!!.trim().uppercase().firstOrNull()) {
            'W' -> high to low
            'L' -> low to high
            'D', 'T' -> if (a == b) high to low else return null
            else -> return null
        }
        return if (last.side == firstSide) SportsMeeting(last.dateMillis, own, other) else SportsMeeting(last.dateMillis, other, own)
    }

    fun ruleColours(first: String?, second: String?, base: Int): Pair<Int, Int>? {
        if (TeamColours.parse(first) == null || TeamColours.parse(second) == null) return null
        return TeamColours.stripe(first, base) to TeamColours.stripe(second, base)
    }

    fun shortLists(names: List<String>): List<String> {
        val clean = names.map(String::trim).filter(String::isNotEmpty).distinct()
        return (clean.size downTo 1).map { keep -> clean.take(keep).joinToString(", ") + if (clean.size > keep) " +${clean.size - keep}" else "" }
    }
}
