package com.cabeye.rider.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Named haptic patterns.
 *
 * Touch is the third channel, alongside speech and earcons, and it is the only one that
 * still works when the phone is in a pocket on a loud street. Each pattern is distinct in
 * rhythm rather than merely in length, because duration alone is very hard to judge
 * without a reference.
 *
 * @param timings alternating wait/vibrate durations in ms, starting with a wait
 * @param amplitudes per-segment strength 0..255, same length as [timings]
 */
enum class HapticPattern(val timings: LongArray, val amplitudes: IntArray) {

    /** Microphone opened. One crisp tick — the rider's cue that speaking will register. */
    LISTENING_START(longArrayOf(0, 40), intArrayOf(0, 200)),

    /** Microphone closed. Softer, shorter tick so it is clearly the bookend of the pair. */
    LISTENING_END(longArrayOf(0, 25), intArrayOf(0, 130)),

    /** Booking confirmed. Two quick taps — a small, unambiguous "done". */
    CONFIRMED(longArrayOf(0, 45, 70, 45), intArrayOf(0, 220, 0, 220)),

    /**
     * The driver has arrived. Deliberately the most distinctive pattern in the set:
     * long–short–long, unlike anything else here, so it is recognisable through a pocket
     * without the rider having to look or listen.
     */
    ARRIVED(longArrayOf(0, 180, 90, 60, 90, 180), intArrayOf(0, 255, 0, 180, 0, 255)),

    /** A question is being asked and an answer is expected. Rising double buzz. */
    CLARIFY(longArrayOf(0, 60, 60, 110), intArrayOf(0, 150, 0, 220)),

    /** Cancelled. One long, flat, slightly dull buzz. */
    CANCELLED(longArrayOf(0, 220), intArrayOf(0, 160)),

    /** SOS raised. Three hard pulses. */
    SOS(longArrayOf(0, 120, 80, 120, 80, 120), intArrayOf(0, 255, 0, 255, 0, 255)),

    /** Something went wrong. Short stutter, distinct from every success pattern. */
    ERROR(longArrayOf(0, 30, 40, 30, 40, 30), intArrayOf(0, 180, 0, 180, 0, 180))
}

/**
 * Fires the named haptic patterns.
 *
 * Kept as a plain class rather than routed through Compose's `LocalHapticFeedback`, for a
 * concrete reason: `HapticFeedbackType` offers only two generic constants (`LongPress` and
 * `TextHandleMove`), which cannot express an arrival pattern that has to be recognisable on
 * its own. Amplitude-controlled waveforms via [VibrationEffect] can.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // API 31+ routes through VibratorManager; the old getSystemService(Vibrator)
            // path is deprecated and returns a shim with reduced capability.
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        }
        else -> {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** True when this device can actually vibrate. Some tablets cannot. */
    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    /**
     * Plays a pattern once.
     *
     * Silently does nothing when the device has no vibrator — a missing haptic must never
     * take down the surface, since speech and earcons still carry the message.
     */
    fun perform(pattern: HapticPattern) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        // Amplitude control is not universal. Where it is missing, createWaveform with
        // amplitudes throws, so fall back to the timing-only form, which every device
        // supports and which still preserves the rhythm that makes patterns distinguishable.
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(pattern.timings, -1)
        }
        v.vibrate(effect)
    }

    /** Stops any vibration in progress. */
    fun cancel() {
        vibrator?.cancel()
    }
}

/** Remembers a single [Haptics] instance for the lifetime of the composition. */
@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    return remember(context) { Haptics(context.applicationContext) }
}
