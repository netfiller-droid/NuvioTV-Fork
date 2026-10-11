package com.nuvio.tv.core.iptv

enum class MultiviewLabels {
    ALWAYS, FOCUSED, OFF;

    fun shown(focused: Boolean): Boolean = this == ALWAYS || (this == FOCUSED && focused)

    companion object {
        const val KEY = "multiview-labels"
        fun parse(value: String?): MultiviewLabels = entries.firstOrNull { it.name == value } ?: ALWAYS
    }
}

data class MultiviewStripSize(val compact: Boolean, val score: Float, val name: Float, val state: Float, val label: Float, val crest: Float,
    val line: Float, val labelLine: Float, val padding: Float, val stripe: Float, val progress: Float)

data class MultiviewStripSide(val team: FixtureTeam, val side: FixtureSide, val score: String?, val behind: Boolean)

object MultiviewStrip {
    const val LEAD_MILLIS = 60L * 60 * 1000
    private const val BASE_SCREEN = 960f
    private const val MIN_RELATIVE = .6f
    private const val MAX_RELATIVE = 1.5f
    private const val COMPACT = .85f
    private const val MAX_NAME = 16

    fun picture(width: Float, height: Float): Pair<Float, Float> {
        val fitted = minOf(width, height * 16f / 9f).coerceAtLeast(0f)
        return fitted to fitted * 9f / 16f
    }

    fun size(pictureWidth: Float, screenWidth: Float): MultiviewStripSize {
        val screen = (screenWidth / BASE_SCREEN).coerceAtLeast(.5f)
        val share = (pictureWidth / screenWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val relative = (if (share <= .5f) share * 2f else 1f + (share - .5f)).coerceIn(MIN_RELATIVE, MAX_RELATIVE)
        val scale = screen * relative
        fun at(base: Float, floor: Float) = maxOf(base * scale, floor * screen)
        val score = at(17f, 13f)
        val label = at(11.5f, 10f)
        val crest = score * 1.25f
        return MultiviewStripSize(relative < COMPACT, score, at(13f, 11f), at(11f, 10f), label, crest, maxOf(crest, score * 1.35f) + 6f * scale,
            label * 1.6f, 8f * scale, 3f * scale, maxOf(2f * scale, 1.5f * screen))
    }

    fun shows(fixture: SportsFixture, nowMillis: Long): Boolean = fixture.status == FixtureStatus.LIVE ||
        (fixture.status == FixtureStatus.SCHEDULED && fixture.startMillis - nowMillis <= LEAD_MILLIS)

    fun sides(fixture: SportsFixture): List<MultiviewStripSide>? {
        val home = fixture.home ?: return null
        val away = fixture.away ?: return null
        val scores = SportsFixtureText.scores(fixture)?.takeIf { fixture.status != FixtureStatus.SCHEDULED }
        val lead = scores?.let { SportsLedger.leader(it.first, it.second) }
        val both = listOf(MultiviewStripSide(home, FixtureSide.HOME, scores?.first, lead == 1), MultiviewStripSide(away, FixtureSide.AWAY, scores?.second, lead == 0))
        return if (SportsFixtureText.awayFirst(fixture)) both.reversed() else both
    }

    fun teamText(team: FixtureTeam, compact: Boolean): String {
        val code = SportsLedger.code(team)
        if (compact) return code ?: SportsFixtureText.code(team)
        val short = team.shortName?.trim()?.takeIf(String::isNotEmpty) ?: team.name.trim()
        return if (short.length > MAX_NAME && code != null) code else short
    }

    fun liveState(fixture: SportsFixture, hidden: Boolean): String? =
        if (fixture.status != FixtureStatus.LIVE || hidden) null else SportsFixtureText.bug(fixture).state?.trim()?.takeIf(String::isNotEmpty)

    fun eventText(fixture: SportsFixture, hidden: Boolean): String =
        if (hidden || fixture.status != FixtureStatus.LIVE) fixture.title else SportsFixtureText.bug(fixture).primary
}
