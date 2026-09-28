package com.cabeye.rider

import android.app.Application
import android.util.Log
import com.cabeye.rider.audio.AudioEngine
import com.cabeye.rider.audio.AudioSession
import com.cabeye.rider.auth.AccountInfo
import com.cabeye.rider.auth.AuthStore
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

    /** Sign-in tokens (encrypted) and cached accounts, per role. */
    lateinit var auth: AuthStore
        private set

    /** Next journeys the rider scheduled by voice, and the alarms that wake the app for them. */
    lateinit var scheduler: com.cabeye.rider.schedule.RideScheduler
        private set

    /**
     * The id of a scheduled ride that has come due and not yet been handled. A value that the
     * rider view model consumes and clears — not an event stream — so a ride that comes due
     * while no screen is up is still there when the rider opens the app from the notification,
     * and is never started twice.
     */
    val dueScheduledRide = MutableStateFlow<String?>(null)

    fun onScheduledRideDue(id: String) {
        dueScheduledRide.value = id
    }

    /** The signed-in rider's places and trips, cached for offline use. */
    lateinit var memory: com.cabeye.rider.memory.MemoryRepository
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

    // ---------------------------------------------------------------------------------
    //  Who is using the app
    //
    //  Null means "not through the door yet": MainActivity shows the sign-in surface for that
    //  role instead of the ride surface. These are set only by a completed sign-in, a
    //  fingerprint unlock of a stored session, or the rider choosing to continue as a guest —
    //  never merely because a token exists on disk, so a stolen unlocked phone still meets the
    //  fingerprint prompt after the process restarts.
    // ---------------------------------------------------------------------------------

    private val _riderAccount = MutableStateFlow<AccountInfo?>(null)
    val riderAccount: StateFlow<AccountInfo?> = _riderAccount.asStateFlow()

    private val _driverAccount = MutableStateFlow<AccountInfo?>(null)
    val driverAccount: StateFlow<AccountInfo?> = _driverAccount.asStateFlow()

    private val _role = MutableStateFlow(AppRole.RIDER)

    /** The active role. Watched by the Activity, which swaps the Composable root on a change. */
    val role: StateFlow<AppRole> = _role.asStateFlow()

    override fun onCreate() {
        super.onCreate()

        preferences = RiderPreferences(this)
        settings = AppSettings(this)

        // Restore the rider's city before anything can score against the wrong one.
        Gazetteer.activeCity = preferences.city

        auth = AuthStore(this)
        api = RideApi(settings, auth)
        memory = com.cabeye.rider.memory.MemoryRepository(this, api, appScope)
        scheduler = com.cabeye.rider.schedule.RideScheduler(this)
        // Alarms are lost when the app is updated or force-stopped; setting them again is cheap.
        runCatching { scheduler.rearmAll() }.getOrNull()?.let { missed ->
            // Came due while the phone was off: offer it at the next quiet moment.
            dueScheduledRide.value = missed.id
        }
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
    /**
     * The account for [role] is now in use: after OTP, after a fingerprint unlock, or as a guest.
     * Also re-stamps `X-User-Id` so the socket and the logs name the real person.
     */
    fun onSignedIn(role: AppRole, account: AccountInfo) {
        Log.i(TAG, "SIGNED_IN role=${role.name} id=${account.id} guest=${account.isGuest}")
        when (role) {
            AppRole.RIDER -> {
                _riderAccount.value = account
                memory.bind(account)
            }
            AppRole.DRIVER -> _driverAccount.value = account
        }
        if (!account.isGuest && _role.value == role) {
            appScope.launch { settings.setUserId(account.id) }
        }
    }

    /** A profile edit came back from the server; keep the cache and the live value in step. */
    fun onAccountUpdated(role: AppRole, account: AccountInfo) {
        auth.updateAccount(role, account)
        when (role) {
            AppRole.RIDER -> if (_riderAccount.value != null) _riderAccount.value = account
            AppRole.DRIVER -> if (_driverAccount.value != null) _driverAccount.value = account
        }
    }

    /**
     * Signs [role] out on this phone and tells the server to forget the token. The server call
     * is best-effort: being offline must never keep someone signed in against their wishes.
     */
    fun signOut(role: AppRole) {
        Log.i(TAG, "SIGN_OUT role=${role.name}")
        val token = auth.token(role)
        auth.clear(role)
        if (token != null) appScope.launch { api.logout(token) }
        when (role) {
            AppRole.RIDER -> {
                _riderAccount.value = null
                memory.bind(null)
            }
            AppRole.DRIVER -> _driverAccount.value = null
        }
    }

    fun switchRole(next: AppRole) {
        if (_role.value == next) return
        Log.i(TAG, "Role switch: ${_role.value.name} -> ${next.name}")

        // A role switch abandons any ride in progress on this device. The two roles keep
        // separate state holders and neither inherits the other's ride.
        socket.unsubscribe(reason = "role switch")

        audio.switchTo(next)
        _role.value = next

        appScope.launch {
            settings.setRole(next)
            // Requests from now on belong to the other role's account, if it has one.
            val account = if (next == AppRole.RIDER) _riderAccount.value else _driverAccount.value
            if (account != null && !account.isGuest) settings.setUserId(account.id)
        }
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
