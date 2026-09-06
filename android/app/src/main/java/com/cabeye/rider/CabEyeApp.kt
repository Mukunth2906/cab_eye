package com.cabeye.rider

import android.app.Application
import android.util.Log
import com.cabeye.rider.audio.AudioEngine
import com.cabeye.rider.audio.AudioSession
import com.cabeye.rider.net.AppRole
import com.cabeye.rider.net.AppSettings
import com.cabeye.rider.net.RideApi
import com.cabeye.rider.net.RideSocket
import com.cabeye.rider.places.Gazetteer
import com.cabeye.rider.places.GooglePlaceResolver
import com.cabeye.rider.places.LocationProvider
import com.cabeye.rider.speech.SpeechInput
import com.google.android.libraries.places.api.Places
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped container.
 *
 * ## Why the socket lives here and not in a view model
 * The WebSocket must survive the Activity. A rider whose phone rotates, or who takes a call
 * mid-ride, must not have their ride's connection torn down and rebuilt — a reconnect costs a
 * "Connection lost / Connected" pair of announcements for an event that was not an outage at
 * all, and false alarms are how a real alarm stops being believed. So the socket is owned at
 * application scope, and the view models collect from it.
 *
 * ## Why the audio engine is behind [AudioSession]
 * The role toggle has to genuinely tear the rider's audio down, not hide it. See
 * [AudioSession] for what that means concretely. Everything downstream reads [audioEngine]
 * through this class rather than holding a reference, so a role switch actually reaches it.
 */
class CabEyeApp : Application() {

    private companion object {
        const val TAG = "CabEye.App"
    }

    /** Outlives every Activity and view model. Cancelled only with the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Runtime configuration: backend URL, role, per-install id. */
    lateinit var settings: AppSettings
        private set

    /** Persisted city and theme. Unchanged from step 1. */
    lateinit var preferences: RiderPreferences
        private set

    lateinit var api: RideApi
        private set

    lateinit var socket: RideSocket
        private set

    /** Live Google Places resolver. It is only a provider; MatchGate still owns safety. */
    lateinit var placeResolver: GooglePlaceResolver
        private set

    /** Fused GPS adapter used to bias place search toward the rider's current area. */
    lateinit var locationProvider: LocationProvider
        private set

    /** Owns the audio hardware and hands it over or takes it away on a role switch. */
    lateinit var audio: AudioSession
        private set

    /**
     * The active engine. **Always read through this, never captured.**
     *
     * A captured reference would survive a role switch and keep a released engine — or worse,
     * a live one — in the hands of code that is now running in driver mode.
     */
    val audioEngine: AudioEngine get() = audio.engine

    /** The recogniser, or null in driver mode where there is deliberately no microphone. */
    val speechInput: SpeechInput? get() = audio.speech

    /** True once TTS has finished initialising. Always false in driver mode. */
    val audioReady: Boolean get() = audio.ready

    private val _role = MutableStateFlow(AppRole.RIDER)

    /** The active role. Watched by the Activity, which swaps the Composable root on a change. */
    val role: StateFlow<AppRole> = _role.asStateFlow()

    override fun onCreate() {
        super.onCreate()

        preferences = RiderPreferences(this)
        settings = AppSettings(this)

        // Restore the rider's city before anything can score against the wrong one.
        Gazetteer.activeCity = preferences.city

        api = RideApi(settings)
        socket = RideSocket(appScope, settings)
        // Places SDK (New) is initialized once at application scope, BEFORE anything that can
        // create a PlacesClient. If no key has been supplied yet, keep the app usable: the
        // existing Gazetteer remains the fallback and the README explains where to add the key.
        //
        // Only whether a key exists is logged — never the key itself.
        val keyConfigured = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
        Log.i(TAG, "GOOGLE_MAPS_API_KEY configured=$keyConfigured")
        if (keyConfigured && !Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(this, BuildConfig.GOOGLE_MAPS_API_KEY)
            Log.i(TAG, "Google Places SDK initialized (New Places API enabled)")
        } else if (!keyConfigured) {
            Log.w(TAG, "Google Maps/Places API key missing; using local Gazetteer fallback")
        }
        Log.i(TAG, "Places.isInitialized=${Places.isInitialized()}")

        placeResolver = GooglePlaceResolver(this)
        locationProvider = LocationProvider(this)
        audio = AudioSession(this)

        // Read once, synchronously, so the very first frame renders the correct surface. A
        // driver whose app flashes the rider screen — with its narration and its open
        // microphone — for even one frame is a driver whose phone just spoke at them.
        val initial = settings.current()
        _role.value = initial.role
        Log.i(TAG, "Starting as ${initial.role.name}, backend=${initial.backendBaseUrl}")

        // Started here rather than on first use: a rider who presses the surface the moment
        // the app opens should not be met with silence while TTS warms up.
        audio.switchTo(initial.role)

        appScope.launch {
            settings.ensureUserId()
        }
    }

    /**
     * Switches role, tearing down or bringing up the audio session as required.
     *
     * The order matters. Audio is dealt with *before* the role flow updates, so that by the
     * time the Activity recomposes into the driver surface the microphone is already released.
     * Recomposing first would leave a window — short, but real, and in a car — in which the
     * driver's screen is up and the rider's microphone is still open.
     */
    fun switchRole(next: AppRole) {
        if (_role.value == next) return
        Log.i(TAG, "Role switch: ${_role.value.name} -> ${next.name}")

        // A role switch abandons any ride in progress on this device. The two roles keep
        // separate state holders and neither inherits the other's ride.
        socket.unsubscribe(reason = "role switch")

        audio.switchTo(next)
        _role.value = next

        appScope.launch { settings.setRole(next) }
    }

    /**
     * Applies a new backend address.
     *
     * Reopens any live socket, because the existing one points at the old host and would
     * otherwise keep working until it dropped — at which point it would retry an address the
     * user has already replaced, and retry it for as long as the ride lasted.
     */
    fun applyBackendUrl(raw: String, onDone: (String) -> Unit = {}) {
        appScope.launch {
            settings.setBackendUrl(raw)
            val applied = settings.current().backendBaseUrl
            socket.refreshEndpoint()
            Log.i(TAG, "Backend URL is now $applied")
            onDone(applied)
        }
    }

    override fun onTerminate() {
        socket.unsubscribe(reason = "process terminating")
        audio.release()
        super.onTerminate()
    }
}
