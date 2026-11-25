package com.music.bitchord.playback.smart

/** A diagnostic is scoped to ordered QUEUE ENTRIES, never just the last plan that happened to run. */
class AutomixPlanDiagnostics {
    private var pair: Pair<String, String>? = null
    private var plan: TransitionPlan? = null

    fun remember(entries: Pair<String, String>, verdict: TransitionPlan) {
        pair = entries
        plan = verdict
    }

    fun forPair(entries: Pair<String, String>): TransitionPlan? = plan.takeIf { pair == entries }

    fun clear() { pair = null; plan = null }
}
