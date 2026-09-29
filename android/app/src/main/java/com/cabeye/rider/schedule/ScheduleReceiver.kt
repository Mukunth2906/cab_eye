package com.cabeye.rider.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cabeye.rider.CabEyeApp
import com.cabeye.rider.MainActivity

/**
 * A scheduled ride is due, or the phone just restarted.
 *
 * Due: tell the running app (which speaks and starts the booking with its cancel window) and
 * post a notification that opens the app — the path that works when the app was not running.
 * Nothing is booked without the app in front of the rider: the notification is the doorbell,
 * the booking still happens through the voice surface where "cancel" works.
 *
 * Boot: alarms do not survive a reboot, so they are set again.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CabEyeApp ?: return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                app.scheduler.rearmAll()
                return
            }
            RideScheduler.ACTION_DUE -> Unit
            else -> return
        }

        val id = intent.getStringExtra(RideScheduler.EXTRA_ID) ?: return
        val ride = app.scheduler.find(id) ?: return
        Log.i(RideScheduler.TAG, "SCHEDULE due id=$id to=\"${ride.spokenDestination}\"")

        app.onScheduledRideDue(id)
        notify(context, ride)
    }

    private fun notify(context: Context, ride: ScheduledRide) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scheduled rides", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when a ride you scheduled is due"
            }
        )
        val open = PendingIntent.getActivity(
            context,
            ride.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(RideScheduler.EXTRA_ID, ride.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Time for your ride")
            .setContentText("To ${ride.spokenDestination}. Tap to book.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()
        runCatching { manager.notify(ride.id.hashCode(), notification) }
    }

    private companion object {
        const val CHANNEL_ID = "cabeye.schedule"
    }
}
