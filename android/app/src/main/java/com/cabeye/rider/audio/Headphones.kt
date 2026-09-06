package com.cabeye.rider.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Whether anything private is plugged in or paired.
 *
 * ## Why this decides whether a secret may be spoken
 * The boarding code is verification: the driver reads it aloud, and the rider's app confirms
 * what it heard matches what the server said it should be. For that to mean anything, the
 * rider's phone must not have already broadcast the code over its loudspeaker to a street
 * where the rider — who cannot look around — has no way to know who is standing close enough
 * to hear.
 *
 * So the expected code is spoken only into an earpiece, and only when one is actually
 * connected. With no headphones the app stays silent about the code and simply waits to hear
 * it. That silence is not a degradation; it is the correct behaviour, and the app says as much
 * out loud so it is never mistaken for a fault.
 *
 * ## Why not `isWiredHeadsetOn()`
 * It is deprecated, and it has never known about Bluetooth — which is what most riders
 * actually use. [AudioDeviceInfo] covers wired, USB and Bluetooth alike, and is API 23+.
 */
object Headphones {

    private const val TAG = "CabEye.Audio"

    /**
     * @return true when audio output is going somewhere only the rider can hear
     */
    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false

        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return false

        val privateOutput = devices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_HEARING_AID -> true

                else -> false
            }
        }

        Log.d(TAG, "Headphones connected=$privateOutput")
        return privateOutput
    }
}
