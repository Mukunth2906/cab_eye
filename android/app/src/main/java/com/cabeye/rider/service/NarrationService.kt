package com.cabeye.rider.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cabeye.rider.MainActivity

/**
 * Keeps narration alive while the app is backgrounded or the screen is off.
 *
 * ## Why this exists
 * The app has to be fully operable with the screen off — that is the normal case, not an
 * edge case. Without a foreground service Android is free to freeze the process during a
 * ride, and the rider would simply stop being told anything, with no way to notice why.
 *
 * ## Why it doesn't own the AudioEngine
 * The engine lives in [com.cabeye.rider.CabEyeApp] at application scope. This service exists
 * to keep the *process* alive and to declare the `mediaPlayback` type; putting the engine
 * behind a service binding would add an asynchronous connection step in front of every
 * utterance, for no benefit — the engine already outlives the Activity.
 */
class NarrationService : Service() {

    companion object {
        private const val TAG = "CabEye.Narration"
        private const val CHANNEL_ID = "cabeye_narration"
        private const val NOTIFICATION_ID = 1001

        /**
         * Starts the service.
         *
         * **Must be called while the app is in the foreground.** Android 12+ throws
         * `ForegroundServiceStartNotAllowedException` otherwise, so every call is guarded —
         * a failure to start narration must never take down a ride in progress.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, NarrationService::class.java)
                context.startForegroundService(intent)
            }.onFailure {
                Log.w(TAG, "Could not start narration service: ${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, NarrationService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.onFailure {
            Log.w(TAG, "startForeground failed: ${it.message}")
            stopSelf()
        }

        // START_STICKY: if the system kills the process mid-ride, bring narration back.
        return START_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ride narration",
            // LOW keeps it silent and unobtrusive. The notification is a legal requirement
            // for a foreground service, not a communication channel — everything the rider
            // needs to know arrives as speech, and a notification chime would collide with it.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Cab Eye speaking while the screen is off"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cab Eye")
            .setContentText("Ride in progress")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            // No setSilent() here: it is API 29+ and minSdk is 26. Silence already comes
            // from the channel (IMPORTANCE_LOW with a null sound), which is the correct
            // place to configure it from API 26 onward anyway.
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "Narration service stopped")
        super.onDestroy()
    }
}
