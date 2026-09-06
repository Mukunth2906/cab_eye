package com.cabeye.rider.telemetry

import android.util.Log

/**
 * Instrumentation, present from the first commit because it *is* the evaluation data.
 *
 * Everything here writes to logcat under one stable tag so a whole session can be pulled
 * off the device with:
 * ```
 * adb logcat -s CABEYE_METRICS
 * ```
 * Logcat rather than a file, deliberately: it needs no storage permission, survives the app
 * crashing, and can be watched live during a demo. A file writer can be added later behind
 * the same call sites without touching any of them.
 *
 * ## The latency budget
 * The project's entire claim is about time, so the stages below are measured on every
 * booking and a breach is logged at WARN — loudly, per the brief, rather than swallowed.
 * ```
 * T0 mic released -> T1 speech final -> T2 intent parsed -> T3 place resolved -> T4 first sound
 *   ASR  <= 800 ms | intent <= 300 ms | resolve <= 400 ms | TTS first audio <= 300 ms
 *   TOTAL <= 2000 ms
 * ```
 */
object Telemetry {

    const val TAG = "CABEYE_METRICS"

    // -- Per-stage budgets, in milliseconds ------------------------------------------
    const val BUDGET_ASR_MS = 800L
    const val BUDGET_INTENT_MS = 300L
    const val BUDGET_RESOLVE_MS = 400L
    const val BUDGET_TTS_FIRST_AUDIO_MS = 300L
    const val BUDGET_TOTAL_MS = 2000L

    /** The five timestamps that bound the four measured stages. */
    enum class Stage {
        /** T0 — microphone released; the clock the rider actually experiences starts here. */
        MIC_RELEASED,

        /** T1 — recogniser returned its final result. */
        SPEECH_FINAL,

        /** T2 — intent extracted, whether by regex fast path or LLM. */
        INTENT_PARSED,

        /** T3 — destination resolved against the gazetteer. */
        PLACE_RESOLVED,

        /** T4 — first audio sample of the response actually reached the speaker. */
        FIRST_SOUND
    }

    /**
     * One booking attempt, from the rider's press to the app's first sound.
     *
     * Create one per attempt, call [mark] as each stage completes, then [finish].
     *
     * @param rideId correlates this record with the backend's WebSocket log
     */
    class RideTrace(private val rideId: String) {

        private val startNanos = System.nanoTime()
        private val marks = LinkedHashMap<Stage, Long>()

        // -- Counters the evaluation asks for ----------------------------------------

        /** Physical presses. The target is a number very close to zero. */
        var tapCount: Int = 0

        /** How many times the app had to ask a disambiguating question. */
        var clarificationTurns: Int = 0

        /** Total seconds of synthesised speech played — speech time is task time. */
        var speechSeconds: Double = 0.0

        /** True if regex handled the intent; false means the LLM was called. */
        var regexFastPathHit: Boolean = true

        /** Milliseconds from first press to a confirmed booking. */
        var timeToBookingMs: Long = 0

        /** Records the moment a stage completed. */
        fun mark(stage: Stage) {
            val elapsed = (System.nanoTime() - startNanos) / 1_000_000
            marks[stage] = elapsed
            Log.d(TAG, "ride=$rideId stage=${stage.name} t=+${elapsed}ms")
        }

        /**
         * Emits the full record and checks it against the budget.
         *
         * A breach is logged at WARN with the offending stage named, so it shows up in a
         * filtered logcat without anyone having to go looking for it.
         */
        fun finish() {
            val t0 = marks[Stage.MIC_RELEASED]
            val t1 = marks[Stage.SPEECH_FINAL]
            val t2 = marks[Stage.INTENT_PARSED]
            val t3 = marks[Stage.PLACE_RESOLVED]
            val t4 = marks[Stage.FIRST_SOUND]

            val asr = span(t0, t1)
            val intent = span(t1, t2)
            val resolve = span(t2, t3)
            val tts = span(t3, t4)
            val total = span(t0, t4)

            Log.i(TAG, buildString {
                append("RIDE_SUMMARY ride=$rideId ")
                append("timeToBookingMs=$timeToBookingMs ")
                append("taps=$tapCount ")
                append("clarifications=$clarificationTurns ")
                append("speechSeconds=${"%.1f".format(speechSeconds)} ")
                append("path=${if (regexFastPathHit) "REGEX" else "LLM"} ")
                append("| asr=${asr ?: "-"}ms intent=${intent ?: "-"}ms ")
                append("resolve=${resolve ?: "-"}ms tts=${tts ?: "-"}ms total=${total ?: "-"}ms")
            })

            checkBudget("ASR", asr, BUDGET_ASR_MS)
            checkBudget("INTENT", intent, BUDGET_INTENT_MS)
            checkBudget("RESOLVE", resolve, BUDGET_RESOLVE_MS)
            checkBudget("TTS_FIRST_AUDIO", tts, BUDGET_TTS_FIRST_AUDIO_MS)
            checkBudget("TOTAL", total, BUDGET_TOTAL_MS)
        }

        private fun span(from: Long?, to: Long?): Long? =
            if (from == null || to == null) null else to - from

        private fun checkBudget(name: String, actual: Long?, budget: Long) {
            if (actual != null && actual > budget) {
                Log.w(TAG, "BUDGET_EXCEEDED ride=$rideId stage=$name " +
                        "actual=${actual}ms budget=${budget}ms over=${actual - budget}ms")
            }
        }
    }

    /**
     * Logs a clarification decision — both numbers, every time, as the brief requires.
     *
     * Recorded even when the answer is "don't ask", because the cases where the gate
     * correctly stayed silent are the ones that justify the gate existing.
     *
     * @param scoreGap difference between the top two candidate scores
     * @param divergenceKm straight-line distance between those candidates
     * @param asked whether the rider was actually questioned
     */
    fun logClarificationDecision(
        candidateA: String,
        candidateB: String,
        scoreGap: Float,
        divergenceKm: Float,
        asked: Boolean
    ) {
        Log.i(TAG, "CLARIFY_DECISION a=\"$candidateA\" b=\"$candidateB\" " +
                "gap=${"%.3f".format(scoreGap)} divergenceKm=${"%.2f".format(divergenceKm)} " +
                "asked=$asked")
    }

    /** Logs a rider-visible state transition, for reconstructing a session afterwards. */
    fun logStateChange(from: String, to: String) {
        Log.d(TAG, "STATE $from -> $to")
    }

    /** Logs an utterance and its duration, feeding the speech-seconds total. */
    fun logSpeech(text: String, tier: String, durationMs: Long) {
        Log.d(TAG, "SPEECH tier=$tier durationMs=$durationMs text=\"$text\"")
        speechMillisTotal += durationMs
    }

    /**
     * Running total of synthesised speech, in milliseconds, for the whole session.
     *
     * Accumulated here rather than on [RideTrace] because most speech happens outside a booking
     * attempt — narration during the ride, the recovery ladder, the reconnect announcements —
     * and a per-attempt figure would silently exclude exactly the utterances that make a ride
     * feel long. Speech time is task time, so the total that matters is the one the rider
     * actually sat through.
     */
    @Volatile
    private var speechMillisTotal: Long = 0

    /** Total seconds of speech this session. Read by the session summary. */
    val speechSecondsTotal: Double get() = speechMillisTotal / 1000.0

    // ---------------------------------------------------------------------------------
    //  Socket instrumentation
    // ---------------------------------------------------------------------------------

    /**
     * Logs a WebSocket lifecycle moment.
     *
     * Socket connect time and reconnect count are first-class metrics for this product, not
     * ops trivia. Every second between a drop and a recovery is a second in which a rider is
     * being told nothing — and the app's contract says silence means everything is fine. So the
     * cost of an outage is measured in the same log as the latency budget, and can be read back
     * from a session with:
     * ```
     * adb logcat -s CABEYE_METRICS | findstr SOCKET
     * ```
     *
     * @param phase one of SUBSCRIBE / OPEN / DROP / DUPLICATE / RECONCILE / UNSUBSCRIBE
     * @param detail free-form key=value pairs
     */
    fun logSocket(phase: String, rideId: String, detail: String) {
        Log.i(TAG, "SOCKET $phase ride=$rideId $detail")
    }

    /**
     * Logs what a reconnect actually cost the rider.
     *
     * @param outageMillis wall time from the drop to the recovered connection
     * @param reconnects how many attempts it took
     * @param announced what the rider was told after reconciliation, or "-" for nothing —
     *   which is the good case, and worth recording precisely because it looks like absence
     */
    fun logReconcile(rideId: String, outageMillis: Long, reconnects: Int, announced: String) {
        Log.i(TAG, "SOCKET RECONCILE ride=$rideId outageMs=$outageMillis " +
                "reconnects=$reconnects announced=\"$announced\"")
    }

    /** Logs a driver-side action, so a two-phone demo can be reconstructed from one logcat. */
    fun logDriverAction(action: String, rideId: String, detail: String = "") {
        Log.i(TAG, "DRIVER $action ride=$rideId $detail")
    }
}
