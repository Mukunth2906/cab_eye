package com.cabeye.rider.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Small adapter around Fused Location so the ViewModel stays testable and provider-agnostic. */
class LocationProvider(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission(context = context)) return null

        return suspendCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { last ->
                    if (last != null) {
                        continuation.resume(last)
                    } else {
                        val token = CancellationTokenSource()
                        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resume(null) }
                    }
                }
                .addOnFailureListener { continuation.resume(null) }
        }
    }

    /**
     * A stream of GPS fixes, for the driver's trip meter. Empty when location permission has
     * not been granted — the server then falls back to a straight-line estimate, so a missing
     * permission never blocks finishing a trip.
     *
     * Stops (and releases GPS) as soon as the collector is cancelled.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long): Flow<Location> {
        if (!hasPermission(context = context)) return emptyFlow()
        return callbackFlow {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) trySend(location)
                }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close(it) }
            awaitClose { client.removeLocationUpdates(callback) }
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

}
