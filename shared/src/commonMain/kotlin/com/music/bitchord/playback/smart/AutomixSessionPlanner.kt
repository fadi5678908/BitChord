package com.music.bitchord.playback.smart

/**
 * One bounded plan cache per playback controller. A scored route belongs to a pair of analysis
 * snapshots, not to a polling tick. Never retain a global catalogue or re-run the search at 30 Hz.
 */
class AutomixSessionPlanner {
    private class Cached(
        val out: TrackAnalysis,
        val inn: TrackAnalysis,
        val current: TransitionTrackInfo,
        val next: TransitionTrackInfo,
        val sequential: Boolean,
        val plan: TransitionPlan,
    )

    private var cached: Cached? = null
    internal var planningPasses = 0
        private set

    fun plan(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        current: TransitionTrackInfo,
        next: TransitionTrackInfo,
        currentTime: Double,
        albumSequential: Boolean = false,
    ): TransitionPlan {
        val previous = cached
        val base = if (previous != null && sameSnapshot(previous.out, outgoing) && sameSnapshot(previous.inn, incoming) &&
            previous.current == current && previous.next == next && previous.sequential == albumSequential
        ) {
            previous.plan
        } else {
            planningPasses++
            planTransition(
                analysis = outgoing,
                nextAnalysis = incoming,
                currentTrack = current,
                nextTrack = next,
                duration = current.durationMs / 1000.0,
                currentTime = 0.0,
                mode = CrossfadeMode.SMART,
                advanced = true,
                natural = true,
                albumSequential = albumSequential,
            ).also { cached = Cached(outgoing, incoming, current, next, albumSequential, it) }
        }
        if (base.blocked) return base
        val time = currentTime.orZero().coerceAtLeast(0.0)
        val started = if (time > base.transitionStart + 0.35) base.forPlaybackStart(time) else base
        return started.copy(shouldStart = !started.blocked && time >= started.transitionStart)
    }

    private fun sameSnapshot(left: TrackAnalysis, right: TrackAnalysis): Boolean = left === right ||
        // TrackAnalyzer returns a fresh empty placeholder until a real result arrives.
        (left.status.isBlank() && right.status.isBlank() && left.bpm == 0.0 && right.bpm == 0.0 && left == right)

    fun clear() { cached = null }
}
