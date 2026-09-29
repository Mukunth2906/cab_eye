package com.cabeye.rider.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import java.util.UUID

/**
 * Keeps the rider's scheduled next journeys and wakes the app when one is due.
 *
 * Stored on the phone (it is the phone that has to wake up and speak), and set as an exact
 * alarm where Android allows it — a ride "at 8 30" that arrives at 8 41 has missed the bus the
 * rider was planning around. Where exact alarms are not permitted, it falls back to the
 * inexact kind and the narrator says the time when it fires.
 */
class RideScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cabeye.schedule", Context.MODE_PRIVATE)
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun all(): List<ScheduledRide> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { ScheduledRide.parse(array.getJSONObject(it)) }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    fun find(id: String): ScheduledRide? = all().firstOrNull { it.id == id }

    /** Upcoming only; anything whose time has passed by more than an hour is dropped. */
    fun upcoming(now: Long = System.currentTimeMillis()): List<ScheduledRide> =
        all().filter { it.at > now - STALE_AFTER_MS }

    fun add(ride: ScheduledRide): ScheduledRide {
        val withId = if (ride.id.isBlank()) ride.copy(id = "sched-" + UUID.randomUUID().toString().take(8)) else ride
        save(all().filter { it.id != withId.id } + withId)
        arm(withId)
        Log.i(TAG, "SCHEDULE add id=${withId.id} at=${withId.at} to=\"${withId.spokenDestination}\"")
        return withId
    }

    fun remove(id: String) {
        find(id)?.let { disarm(it) }
        save(all().filter { it.id != id })
    }

    fun clear() {
        all().forEach { disarm(it) }
        save(emptyList())
    }

    /**
     * Re-arms every upcoming ride — after a reboot or an app update wipes alarms.
     *
     * @return the earliest ride that came due while the alarm could not fire (phone off,
     *   app force-stopped) within the last hour, so the caller can still offer it
     */
    fun rearmAll(): ScheduledRide? {
        val now = System.currentTimeMillis()
        val keep = upcoming(now)
        save(keep)
        keep.filter { it.at > now }.forEach { arm(it) }
        return keep.filter { it.at <= now }.minByOrNull { it.at }
    }

    private fun save(list: List<ScheduledRide>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun arm(ride: ScheduledRide) {
        val pending = pendingIntent(ride.id)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exactAllowed) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ride.at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ride.at, pending)
            }
        }.onFailure {
            // SecurityException if exact alarms were revoked between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ride.at, pending)
        }
    }

    private fun disarm(ride: ScheduledRide) {
        alarms.cancel(pendingIntent(ride.id))
    }

    private fun pendingIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        id.hashCode(),
        Intent(appContext, ScheduleReceiver::class.java).setAction(ACTION_DUE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val TAG = "CabEye.Schedule"
        const val KEY = "rides"
        const val ACTION_DUE = "com.cabeye.rider.SCHEDULED_RIDE_DUE"
        const val EXTRA_ID = "cabeye.scheduledRideId"
        const val STALE_AFTER_MS = 60 * 60_000L
    }
}
