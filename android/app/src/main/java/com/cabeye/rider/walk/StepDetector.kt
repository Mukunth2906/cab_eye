package com.cabeye.rider.walk

import kotlin.math.sqrt

/**
 * Steps from the accelerometer, without the step-counter sensor (which needs the
 * activity-recognition permission and is missing on some phones).
 *
 * The acceleration magnitude is smoothed and centred on a slow running mean (gravity); a step
 * is a peak above [threshold] at least [minIntervalMs] after the last one — about 3 steps a
 * second at most, faster than anyone walks with a cane.
 *
 * Pure: feed samples, read steps. Tested with synthetic gait.
 */
class StepDetector(
    private val threshold: Double = 1.2,
    private val minIntervalMs: Long = 300
) {
    private var mean = Double.NaN
    private var smooth = 0.0
    private var prev = 0.0
    private var rising = false
    private var lastStepAt = Long.MIN_VALUE / 2

    /** @return true when this sample completes a step */
    fun onSample(t: Long, x: Double, y: Double, z: Double): Boolean {
        val mag = sqrt(x * x + y * y + z * z)
        mean = if (mean.isNaN()) mag else mean + (mag - mean) * 0.02     // ~gravity, slow
        smooth += (mag - mean - smooth) * 0.35                            // light low-pass
        var step = false
        if (smooth < prev && rising && prev > threshold && t - lastStepAt >= minIntervalMs) {
            step = true
            lastStepAt = t
        }
        rising = smooth > prev
        prev = smooth
        return step
    }
}
