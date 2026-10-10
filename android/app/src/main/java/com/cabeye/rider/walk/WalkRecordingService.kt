package com.cabeye.rider.walk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cabeye.rider.MainActivity

/**
 * Records one walk: GPS fixes, steps and compass heading, kept in memory on this phone.
 *
 * A location foreground service, so recording carries on with the screen off — the normal
 * case for a blind rider walking with the phone in a pocket — and a partial wake lock (capped
 * at [MAX_RECORDING_MS]) so the sensors keep reporting. Nothing is sent anywhere; the events
 * are handed to [WalkRecorder] and turned into a route on the phone.
 */
class WalkRecordingService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "CabEye.Walk"
        private const val CHANNEL_ID = "cabeye_walk"
        private const val NOTIFICATION_ID = 1002
        const val MAX_RECORDING_MS = 30 * 60 * 1000L

        fun start(context: Context): Boolean {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            return runCatching {
                // Without location the walk is still recorded from steps and compass (indoor mode).
                context.startForegroundService(Intent(context, WalkRecordingService::class.java).putExtra("gps", granted))
                true
            }.getOrElse {
                Log.w(TAG, "Could not start walk recording: ${it.message}")
                false
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WalkRecordingService::class.java)) }
        }
    }

    private var sensors: SensorManager? = null
    private var locations: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val steps = StepDetector()
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastHeadingAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gps = intent?.getBooleanExtra("gps", false) == true
        createChannel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification(), type)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure {
            Log.w(TAG, "startForeground failed: ${it.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        begin(gps)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun begin(gps: Boolean) {
        if (sensors != null) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cabeye:walk").apply { acquire(MAX_RECORDING_MS) }

        sensors = (getSystemService(Context.SENSOR_SERVICE) as SensorManager).also { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR))
                ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        if (gps) {
            locations = (getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { lm ->
                runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper()) }
                    .onFailure { Log.w(TAG, "GPS unavailable: ${it.message}") }
            }
        }
        Log.i(TAG, "WALK recording started gps=$gps")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val v = event.values
                if (steps.onSample(now, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())) {
                    WalkRecorder.add(WalkEvent.Step(now))
                }
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                if (now - lastHeadingAt < 200) return
                lastHeadingAt = now
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val deg = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
                WalkRecorder.add(WalkEvent.Heading(now, deg))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        WalkRecorder.add(
            WalkEvent.Fix(System.currentTimeMillis(), location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy else 50f)
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        sensors?.unregisterListener(this)
        sensors = null
        runCatching { locations?.removeUpdates(this) }
        locations = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        Log.i(TAG, "WALK recording stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Walk recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Cab Eye records a walking route on this phone"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording your walk")
            .setContentText("Kept on this phone only. Say I'm there to finish.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }
}

/**
 * The walk being recorded, in memory. The service adds sensor events; the view model adds the
 * rider's spoken marks and turns, and takes the whole list when the walk ends.
 */
object WalkRecorder {
    private val events = mutableListOf<WalkEvent>()

    @Volatile
    var active = false
        private set

    @Volatile
    var startedAt = 0L
        private set

    @Synchronized
    fun begin(now: Long) {
        events.clear()
        active = true
        startedAt = now
    }

    @Synchronized
    fun add(e: WalkEvent) {
        if (active && events.size < 20_000) events += e
    }

    @Synchronized
    fun steps(): Int = events.count { it is WalkEvent.Step }

    /** Ends the walk and hands back what was recorded. */
    @Synchronized
    fun finish(): List<WalkEvent> {
        active = false
        return events.toList().also { events.clear() }
    }
}
