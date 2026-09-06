package com.cabeye.rider.audio

import android.content.Context
import android.util.Log
import com.cabeye.rider.net.AppRole
import com.cabeye.rider.speech.OnDeviceSpeechInput
import com.cabeye.rider.speech.SpeechInput
import com.cabeye.rider.speech.SpeechInputListener

/**
 * Owns the audio hardware, and hands it over or takes it away when the role changes.
 *
 * ## The requirement this implements
 * "Switching mode must tear down the rider's audio session, not merely hide the UI."
 *
 * Hiding the UI is what a naïve implementation does, and it leaves a live `TextToSpeech`
 * instance, a held audio-focus request and a warm `SpeechRecognizer` in a car being driven by
 * someone who did not consent to any of them. An open microphone in a vehicle carrying a
 * passenger who cannot see that it is open is not a rough edge; it is the thing not to do.
 *
 * ## What "tear down" concretely means here
 * On [switchTo] `DRIVER`:
 *  1. any in-flight utterance is stopped and the heartbeat is halted,
 *  2. the recogniser is cancelled and **released** — the object is destroyed, not paused,
 *  3. the real [AudioEngineImpl] is released, which shuts down TTS, kills the earcon and
 *     heartbeat threads, and **abandons audio focus** so whatever the driver was listening to
 *     un-ducks,
 *  4. [engine] is replaced with a [SilentAudioEngine], so even a call site that was never
 *     given a role check cannot produce a sound.
 *
 * Steps 3 and 4 are independent defences and both are kept. Step 3 is the real one; step 4 is
 * what makes a future missed guard a logged no-op instead of a hazard.
 *
 * Switching back to `RIDER` constructs fresh instances. Nothing is reused across the boundary,
 * because a half-torn-down engine is exactly the kind of thing that works in testing and fails
 * on the third switch.
 */
class AudioSession(private val context: Context) {

    private companion object {
        const val TAG = "CabEye.AudioSession"
    }

    /**
     * The active engine.
     *
     * In rider mode this is a live [AudioEngineImpl]. In driver mode it is a
     * [SilentAudioEngine] and the real one has been released. Callers hold this property
     * rather than a captured reference — a captured one would keep a released engine alive
     * past a role switch.
     */
    @Volatile
    var engine: AudioEngine = SilentAudioEngine()
        private set

    /**
     * The recogniser, or null when there is genuinely no microphone open or openable.
     *
     * Null in driver mode. Not "an object that refuses to listen" — actually absent, so the
     * type system stops driver-mode code from opening a microphone at all.
     */
    @Volatile
    var speech: SpeechInput? = null
        private set

    /** True once TTS has finished initialising in rider mode. */
    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    private var role: AppRole? = null

    /**
     * Moves the audio session to match a role. Idempotent for the same role.
     *
     * @param onReady called once speech is genuinely available. Fires immediately in driver
     *   mode, where there is nothing to wait for.
     */
    @Synchronized
    fun switchTo(role: AppRole, onReady: () -> Unit = {}) {
        if (this.role == role) {
            onReady()
            return
        }
        Log.i(TAG, "Audio session: ${this.role?.name ?: "none"} -> ${role.name}")
        this.role = role

        when (role) {
            AppRole.RIDER -> activateRider(onReady)
            AppRole.DRIVER -> shutdownForDriver(onReady)
        }
    }

    private fun activateRider(onReady: () -> Unit) {
        // Fresh instances every time. See the class javadoc: nothing survives the boundary.
        val realEngine = AudioEngineImpl(context)
        engine = realEngine
        speech = OnDeviceSpeechInput(context)

        ready = false
        realEngine.initialise {
            ready = true
            onReady()
        }
    }

    private fun shutdownForDriver(onReady: () -> Unit) {
        val outgoing = engine
        val outgoingSpeech = speech

        // Swap the no-op in FIRST. Anything that fires during the teardown below — a queued
        // TTS callback, a coroutine that has not noticed the switch yet — then lands on the
        // silent engine rather than on one that is half-released.
        engine = SilentAudioEngine()
        speech = null
        ready = false

        runCatching {
            outgoing.stopSpeaking()
            outgoing.heartbeat(false)
            // Releases TTS, the earcon and heartbeat threads, and the audio-focus request.
            outgoing.release()
        }.onFailure { Log.w(TAG, "Audio engine teardown: ${it.message}") }

        runCatching {
            outgoingSpeech?.cancel()
            outgoingSpeech?.release()
        }.onFailure { Log.w(TAG, "Recogniser teardown: ${it.message}") }

        Log.i(TAG, "Rider audio session torn down: TTS released, mic released, focus abandoned")
        onReady()
    }

    /** True when a microphone can be opened at all. False in driver mode, always. */
    val micAvailable: Boolean get() = speech != null

    /** Convenience so callers do not each write the null check. */
    fun startListening(listener: SpeechInputListener): Boolean {
        val stt = speech ?: return false
        stt.start(listener)
        return true
    }

    /** Full release, for process teardown. */
    @Synchronized
    fun release() {
        runCatching { engine.release() }
        runCatching { speech?.release() }
        engine = SilentAudioEngine()
        speech = null
        ready = false
        role = null
    }
}
