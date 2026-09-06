package com.cabeye.rider.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One tone in a sequence.
 *
 * @param frequencyHz base pitch before [pitchShift]
 * @param durationMs how long it sounds
 * @param pan -1 hard left, 0 centre, +1 hard right
 * @param pitchShift multiplier on [frequencyHz]
 * @param volume linear gain 0..1
 * @param gapAfterMs silence inserted after this tone, for multi-tone earcons
 */
data class ToneSpec(
    val frequencyHz: Float,
    val durationMs: Int,
    val pan: Float = 0f,
    val pitchShift: Float = 1f,
    val volume: Float = 1f,
    val gapAfterMs: Int = 0
)

/**
 * Synthesises tones as raw PCM and plays them through [AudioTrack].
 *
 * ## Why AudioTrack and not SoundPool
 * `SoundPool` plays fixed clips at fixed rates. Two things here rule it out:
 *
 *  - **Per-ear panning.** The driver-approach cue encodes the car's real bearing as stereo
 *    position. That needs independent left and right gain, which is achieved below by
 *    generating *interleaved stereo PCM with a different gain per channel* — genuinely
 *    different samples in each ear, not a balance approximation.
 *  - **Runtime pitch.** Pitch rises continuously as the car nears. Here that is applied at
 *    synthesis time (`frequency × pitchShift`) rather than by resampling, so the tone stays
 *    clean at every distance instead of degrading as the shift grows.
 *
 * ## Clicks
 * A sine wave that starts and stops at a non-zero sample produces an audible click. Every
 * tone therefore gets a short attack/release envelope. This matters more than it sounds:
 * the earcons repeat every 1.5 s for an entire approach phase, and a click on each one
 * becomes genuinely unpleasant.
 */
class ToneSynth {

    companion object {
        private const val TAG = "CabEye.ToneSynth"

        /** 44.1 kHz — universally supported, and well above anything these tones need. */
        const val SAMPLE_RATE = 44_100

        /** Attack/release ramp length. ~8 ms is inaudible as a fade but removes the click. */
        private const val RAMP_MS = 8
    }

    /**
     * Renders and plays a sequence of tones, blocking until it finishes.
     *
     * **Blocks the calling thread** — callers must run this on a dedicated audio thread,
     * never the main thread. [AudioEngineImpl] owns that thread.
     *
     * @param tones the sequence to play, in order
     * @param masterVolume multiplied into every tone's own volume
     */
    fun playBlocking(tones: List<ToneSpec>, masterVolume: Float = 1f) {
        for (spec in tones) {
            playSingleBlocking(spec, masterVolume)
            if (spec.gapAfterMs > 0) {
                Thread.sleep(spec.gapAfterMs.toLong())
            }
        }
    }

    private fun playSingleBlocking(spec: ToneSpec, masterVolume: Float) {
        val pcm = render(spec, masterVolume)
        if (pcm.isEmpty()) return

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // SONIFICATION is the correct usage for non-speech UI feedback.
                        // It keeps earcons on the same stream as the narration and lets the
                        // system duck other apps rather than pausing them.
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)   // 2 bytes per 16-bit sample
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()

            // MODE_STATIC gives no completion callback worth relying on, so wait out the
            // known duration. The small margin lets the buffer drain before release, which
            // otherwise truncates the tail of the tone on some devices.
            Thread.sleep(spec.durationMs.toLong() + 40L)
        } catch (e: InterruptedException) {
            // Deliberate cancellation (heartbeat stopped, engine released). Restore the
            // flag so the owning thread's loop notices and exits.
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Tone playback failed: ${e.message}")
        } finally {
            runCatching {
                track?.stop()
                track?.release()
            }
        }
    }

    /**
     * Renders one tone to interleaved 16-bit stereo PCM.
     *
     * Uses **equal-power panning**: gains follow cos/sin of the pan angle rather than a
     * straight linear split. A linear pan sounds noticeably quieter in the middle, which
     * would make a driver directly ahead seem further away than one off to the side —
     * exactly backwards for a cue whose whole job is to convey direction.
     */
    private fun render(spec: ToneSpec, masterVolume: Float): ShortArray {
        val frames = (SAMPLE_RATE * spec.durationMs / 1000)
        if (frames <= 0) return ShortArray(0)

        val frequency = (spec.frequencyHz * spec.pitchShift).coerceIn(20f, 20_000f)
        val amplitude = (spec.volume * masterVolume).coerceIn(0f, 1f)

        val panAngle = ((spec.pan.coerceIn(-1f, 1f) + 1f) * (PI / 4)).toFloat()
        val leftGain = cos(panAngle)
        val rightGain = sin(panAngle)

        val rampFrames = min(frames / 2, SAMPLE_RATE * RAMP_MS / 1000)
        val out = ShortArray(frames * 2)
        val angularStep = 2.0 * PI * frequency / SAMPLE_RATE

        for (i in 0 until frames) {
            val envelope = when {
                rampFrames <= 0 -> 1f
                i < rampFrames -> i.toFloat() / rampFrames
                i >= frames - rampFrames -> (frames - i).toFloat() / rampFrames
                else -> 1f
            }

            val sample = sin(angularStep * i).toFloat() * envelope * amplitude
            out[i * 2] = (sample * leftGain * Short.MAX_VALUE).toInt().toShort()
            out[i * 2 + 1] = (sample * rightGain * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
