package com.nuvio.tv.core.iptv

enum class GoLiveRoute { NONE, CUSHION, LOCAL, RETUNE }

object LiveGoLive {
    fun route(catchup: Boolean, catchupFromLive: Boolean, catchupStop: Long?, now: Long, localTimeshift: Boolean, localBehind: Boolean,
        paused: Boolean, behindLive: Boolean, cushionMillis: Long): GoLiveRoute = when {
        catchup -> if (catchupFromLive || catchupStop == null || catchupStop > now) GoLiveRoute.RETUNE else GoLiveRoute.NONE
        localTimeshift -> if (localBehind || paused) GoLiveRoute.LOCAL else GoLiveRoute.NONE
        paused -> GoLiveRoute.RETUNE
        cushionMillis > 0 && behindLive -> GoLiveRoute.CUSHION
        else -> GoLiveRoute.NONE
    }
}
