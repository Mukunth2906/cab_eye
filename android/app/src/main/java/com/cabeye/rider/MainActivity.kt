package com.cabeye.rider

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cabeye.rider.audio.NarrationTier
import com.cabeye.rider.auth.RiderSignInViewModel
import com.cabeye.rider.driver.DriverAccountScreen
import com.cabeye.rider.driver.DriverAccountViewModel
import com.cabeye.rider.driver.DriverSurface
import com.cabeye.rider.driver.DriverViewModel
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.AppRole
import com.cabeye.rider.security.BiometricGate
import com.cabeye.rider.ui.DebugSettingsScreen
import com.cabeye.rider.ui.RiderSignInSurface
import com.cabeye.rider.ui.RiderSurface
import com.cabeye.rider.ui.theme.CabEyeTheme
import kotlinx.coroutines.launch

/**
 * The only Activity in the app.
 *
 * ## One Activity, three roots
 * Principle 1 — one surface, zero navigation — is still enforced structurally: there is no
 * `NavHost`, no back stack, and nothing the *rider* can navigate to. What changed is that the
 * single root is now chosen by role:
 *
 *  - [AppRole.RIDER] → [RiderSurface], the voice-first surface with narration and a live mic
 *  - [AppRole.DRIVER] → [DriverSurface], silent, with the boarding code and the presets
 *  - the settings screen, raised over either, and reachable only through a debug affordance
 *
 * The rider's experience is unchanged by this: from inside rider mode there is still exactly
 * one screen and nowhere to get lost. The debug entry point is a five-tap gesture rather than
 * a visible button, precisely so a rider cannot reach it by accident — a blind user landing on
 * a settings screen with no idea how they got there is the worst possible navigation failure.
 */
// FragmentActivity (a ComponentActivity subclass) only because Android's fingerprint prompt,
// BiometricPrompt, requires one. setContent, the permission launchers and everything else
// behave exactly as before.
class MainActivity : FragmentActivity() {

    private var viewModel: RiderViewModel? = null

    /** Non-null while the rider sign-in screen is showing, so volume keys and permissions reach it. */
    private var signInViewModel: RiderSignInViewModel? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val micGranted = granted[Manifest.permission.RECORD_AUDIO] == true
        viewModel?.micPermissionGranted = micGranted
        signInViewModel?.micPermissionGranted = micGranted
    }

    /**
     * Location for the DRIVER's trip meter: the server measures the trip's km from the driver
     * phone's GPS and prices it. Kept apart from [permissionLauncher] so asking a driver never
     * touches the rider's microphone state. A "no" is fine — the trip still completes and the
     * server falls back to a straight-line estimate.
     */
    private val driverLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private fun requestDriverLocationIfNeeded() {
        if (!hasLocationPermission()) {
            driverLocationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    /**
     * READ_CONTACTS, asked only at the moment the rider has already agreed to name someone.
     *
     * Kept apart from [permissionLauncher] on purpose. That one runs at launch for the
     * permissions the app cannot work without; this one must never fire then. A cab app that
     * asks for the whole phonebook on first open looks exactly like a cab app that should not
     * be trusted with it — and here the rider has just been told out loud why it is needed.
     *
     * Either answer continues the booking: granted, the ladder asks for the name; refused, it
     * falls through to the landmark question. Nobody loses a ride over a declined permission.
     */
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel?.onContactPermissionResult(granted)
    }

    /**
     * CAMERA, asked only at the moment the rider has just said yes to sharing it with their
     * driver — never at launch. The app says out loud what the dialog is before it appears.
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel?.onCameraPermissionResult(granted)
    }

    /** The system's "let Cab Eye read this message?" sheet for the sign-in code. */
    private val smsConsentLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            smsReader.onConsentResult(result.resultCode, result.data)
        }

    private val smsReader: com.cabeye.rider.auth.SmsCodeReader by lazy {
        com.cabeye.rider.auth.SmsCodeReader(this) { consent: android.content.Intent ->
            smsConsentLauncher.launch(consent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as CabEyeApp
        handleScheduledIntent(intent)

        setContent {
            val role by app.role.collectAsStateWithLifecycle()
            val riderAccount by app.riderAccount.collectAsStateWithLifecycle()
            val driverAccount by app.driverAccount.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            // Re-read each time Settings opens: the rider may have said "always" by voice since.
            var cameraAlways by remember(showSettings) { mutableStateOf(app.preferences.alwaysShareCamera) }
            var settingsUrl by remember { mutableStateOf(app.settings.current().backendBaseUrl) }

            // Read from the view model in rider mode; falls back to the stored default in
            // driver mode, where there is no rider view model to ask.
            val themeChoice = viewModel?.themeChoice ?: app.preferences.theme

            CabEyeTheme(choice = themeChoice) {
                // Android 15 draws apps edge to edge: keep every screen clear of the status bar
                // (the clock was sitting on top of the title) and of the gesture bar.
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(com.cabeye.rider.ui.theme.LocalPalette.current.background)
                        .safeDrawingPadding()
                ) {
                when {
                    showSettings -> DebugSettingsScreen(
                        currentUrl = settingsUrl,
                        currentRole = role,
                        debugBuild = BuildConfig.DEBUG,
                        onSave = { url ->
                            app.applyBackendUrl(url) { applied -> settingsUrl = applied }
                        },
                        onTestConnection = { report -> testConnection(app, report) },
                        onRoleChange = { next ->
                            // Tears the rider's audio session down before the root swaps —
                            // see CabEyeApp.switchRole for why that order matters.
                            app.switchRole(next)
                            // Straight to the chosen mode's screen: no extra "Done" to find.
                            showSettings = false
                        },
                        onClose = { showSettings = false },
                        signedInAs = (if (role == AppRole.DRIVER) driverAccount else riderAccount)?.let {
                            when {
                                it.isGuest -> "Using the app as a guest"
                                it.name.isNotBlank() -> "Signed in as ${it.name} (${it.phone})"
                                else -> "Signed in (${it.phone})"
                            }
                        }.orEmpty(),
                        cameraAlwaysShare = if (role == AppRole.RIDER) cameraAlways else null,
                        onLoadDemo = if (role == AppRole.RIDER && BuildConfig.DEBUG) { report ->
                            val loaded = com.cabeye.rider.demo.DemoData.load(this@MainActivity)
                            // Ride memory (the cab destinations) comes from the server: fetch it now.
                            app.memory.refresh()
                            val who = riderAccount?.phone.orEmpty()
                            report(
                                loaded + if (who.endsWith("9000011111")) " Signed in as the demo rider: ride history is on the server too."
                                else " Sign in as the demo rider (90000 11111) to also see the ride history."
                            )
                        } else null,
                        onCameraAlwaysShare = { on ->
                            app.preferences.alwaysShareCamera = on
                            cameraAlways = on
                        },
                        onSignOut = {
                            app.signOut(role)
                            showSettings = false
                        }
                    )

                    // -------------------------------------------------------------------
                    //  Driver: sign in, then complete the profile, before any ride screen.
                    // -------------------------------------------------------------------
                    role == AppRole.DRIVER && (driverAccount?.profileComplete != true) -> {
                        val accountVm: DriverAccountViewModel = viewModel()
                        accountVm.smsCodeRequest = { onCode -> smsReader.start(onCode) }
                        LaunchedEffect(driverAccount) {
                            accountVm.start()
                            if (driverAccount != null && accountVm.ui.step != com.cabeye.rider.driver.DriverAccountStep.PROFILE) {
                                accountVm.editProfile()
                            }
                        }
                        DriverAccountScreen(
                            ui = accountVm.ui,
                            canCancel = false,
                            onPhoneChange = accountVm::onPhoneChange,
                            onSendCode = accountVm::sendCode,
                            onCodeChange = accountVm::onCodeChange,
                            onVerify = accountVm::verify,
                            onChangeNumber = accountVm::changeNumber,
                            onField = accountVm::onField,
                            onSave = accountVm::saveProfile,
                            onCancel = accountVm::cancelEdit,
                            onSignOut = accountVm::signOut,
                            onSettings = { showSettings = true }
                        )
                    }

                    role == AppRole.DRIVER -> {
                        val accountVm: DriverAccountViewModel = viewModel()
                        if (accountVm.ui.editing) {
                            DriverAccountScreen(
                                ui = accountVm.ui,
                                canCancel = true,
                                onPhoneChange = accountVm::onPhoneChange,
                                onSendCode = accountVm::sendCode,
                                onCodeChange = accountVm::onCodeChange,
                                onVerify = accountVm::verify,
                                onChangeNumber = accountVm::changeNumber,
                                onField = accountVm::onField,
                                onSave = accountVm::saveProfile,
                                onCancel = accountVm::cancelEdit,
                                onSignOut = accountVm::signOut,
                                onSettings = { showSettings = true }
                            )
                        } else {
                        val driverVm: DriverViewModel = viewModel()
                        LaunchedEffect(Unit) { requestDriverLocationIfNeeded() }
                        DriverSurface(
                            uiState = driverVm.uiState,
                            onGoOnline = driverVm::goOnline,
                            onGoOffline = driverVm::goOffline,
                            onAccept = driverVm::acceptRequest,
                            onDecline = driverVm::declineRequest,
                            onPreset = driverVm::sendPreset,
                            onBeacon = driverVm::fireBeacon,
                            onLocation = driverVm::sendLocation,
                            onArrived = driverVm::markArrived,
                            onConfirmSeated = driverVm::confirmSeated,
                            onStartTrip = driverVm::startTrip,
                            onComplete = driverVm::completeTrip,
                            onFinish = driverVm::finishAndGoOnline,
                            onCancel = driverVm::cancelRide,
                            onMessage = driverVm::sendMessage,
                            onSettings = { showSettings = true },
                            driverName = driverAccount?.name.orEmpty(),
                            onProfile = accountVm::editProfile,
                            cameraFrame = driverVm.cameraFrame,
                            cameraLabels = driverVm.cameraLabels,
                            onRequestCamera = driverVm::requestCamera,
                            onStopCamera = driverVm::stopCamera,
                            onStopArrived = driverVm::arrivedAtStop,
                            onStopDone = driverVm::finishStop,
                            onRateRider = driverVm::sendRiderFeedback
                        )
                        }
                    }

                    // -------------------------------------------------------------------
                    //  Rider: voice sign-in (or fingerprint unlock) before the ride surface.
                    // -------------------------------------------------------------------
                    riderAccount == null -> {
                        val signIn: RiderSignInViewModel = viewModel()
                        signInViewModel = signIn
                        viewModel = null
                        signIn.micPermissionGranted = hasMicPermission()
                        signIn.unlockRequest = { name, onResult ->
                            BiometricGate.unlock(this@MainActivity, name, onResult)
                        }
                        signIn.smsCodeRequest = { onCode -> smsReader.start(onCode) }
                        signIn.smsCodeCancel = { smsReader.stop() }
                        LaunchedEffect(Unit) {
                            requestPermissionsIfNeeded()
                            signIn.start()
                        }
                        RiderSignInSurface(
                            ui = signIn.ui,
                            onHoldStart = signIn::onHoldStart,
                            onHoldEnd = signIn::onHoldEnd,
                            onTyped = signIn::onTyped,
                            onSkip = signIn::onSkip,
                            onClearNumber = signIn::onClearNumber,
                            onResend = signIn::onResend,
                            onOpenSettings = { showSettings = true }
                        )
                    }

                    else -> {
                        signInViewModel = null
                        val vm: RiderViewModel = viewModel()
                        viewModel = vm
                        vm.micPermissionGranted = hasMicPermission()
                        vm.contactPermissionRequest = {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                        vm.cameraPermissionRequest = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        vm.paymentAuthRequest = { amount, onResult ->
                            BiometricGate.authenticate(this@MainActivity, amount, onResult)
                        }

                        // Permission is requested from inside rider mode only. Asking a driver
                        // for microphone access would be asking for something the driver
                        // surface is built never to use.
                        LaunchedEffect(Unit) { requestPermissionsIfNeeded() }

                        RiderSurface(
                            uiState = vm.uiState,
                            onHoldStart = vm::onHoldStart,
                            onHoldEnd = vm::onHoldEnd,
                            onCancel = vm::onCancel,
                            onClarifyChoice = vm::onClarifyChoice,
                            onNearMissAnswer = vm::onNearMissAnswer,
                            onCodeConfirmed = vm::onCodeConfirmed,
                            onSos = vm::onSos,
                            onDismissSos = vm::onDismissSos,
                            onPaymentResult = vm::onPaymentResult,
                            onPay = vm::onPayTapped,
                            onPaymentMethod = vm::onPaymentMethod,
                            onDeclinePayment = vm::onDeclinePayment,
                            onOpenSettings = { showSettings = true },
                            onPostRideAction = vm::onPostRideAction,
                            onCameraAnswer = vm::onCameraAnswer,
                            onStopCamera = vm::onStopCamera,
                            onCallDriver = vm::onCallDriver,
                            onHelp = vm::onHelp,
                            onQuickCommand = vm::onQuickCommand,
                            explore = vm.explore.ui,
                            mindMap = remember(vm) {
                                object : com.cabeye.rider.ui.MindMapActions {
                                    override fun catalog() = vm.catalog
                                    override fun answer() = vm.mapAnswer
                                    override fun load() = vm.loadMindMap()
                                    override fun book(card: com.cabeye.rider.mindmap.MindMapCatalog.PlaceCard) = vm.bookFromMap(card)
                                    override fun explain(nodeId: String) = vm.explainNode(nodeId)
                                    override fun forget(nodeId: String) = vm.forgetNode(nodeId)
                                    override fun guide(routeId: String) = vm.guideWalkFromMap(routeId)
                                    override fun readWalk(routeId: String) = vm.readWalkFromMap(routeId)
                                    override fun forgetWalk(routeId: String) = vm.forgetWalkFromMap(routeId)
                                    override fun bookRoute(name: String) = vm.bookRouteFromMap(name)
                                }
                            },
                            exploreActions = remember(vm) {
                                object : com.cabeye.rider.ui.ExploreActions {
                                    override fun open() = vm.openExplore()
                                    override fun describe() = vm.exploreDescribe()
                                    override fun read() = vm.exploreRead()
                                    override fun keepTelling() = vm.exploreKeepTelling()
                                    override fun find(target: String) = vm.exploreFind(target)
                                    override fun stop() = vm.exploreStop()
                                    override fun name(name: String) = vm.exploreName(name)
                                    override fun askName() = vm.exploreAskName()
                                    override fun finish(save: Boolean) = vm.closeExplore(save)
                                }
                            },
                            onCameraPreview = vm::attachCameraPreview,
                            mindMapOpen = vm.mindMapOpen,
                            onMindMapOpen = { open -> if (open) vm.openMindMap() else vm.closeMindMap() },
                            onDoubleTap = vm::onDoubleTap
                        )
                    }
                }
                }
            }
        }
    }

    /**
     * Pings `/health` and **speaks** the result, as well as returning it for the screen.
     *
     * Spoken, not merely displayed, for two reasons. The person who pasted the URL is standing
     * next to the phone and may not be looking at it; and hearing a sentence come out of the
     * speaker proves the TTS path works, which is the other thing that can leave this app
     * silent. Diagnosing that here is far better than discovering it mid-ride.
     *
     * In driver mode the engine is a `SilentAudioEngine`, so this logs and shows the result
     * without making a sound — which is correct, and is exactly the guarantee the role toggle
     * is supposed to provide.
     */
    private fun testConnection(app: CabEyeApp, report: (String) -> Unit) {
        lifecycleScope.launch {
            val spoken = when (val result = app.api.health()) {
                is ApiResult.Ok -> result.value
                is ApiResult.Failed -> "${result.spoken} (${result.detail})"
            }
            app.audioEngine.speak(spoken, NarrationTier.INTERRUPT)
            report(spoken)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf<String>()

        if (!hasMicPermission()) {
            wanted += Manifest.permission.RECORD_AUDIO
        }

        if (!hasLocationPermission()) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
            wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        // POST_NOTIFICATIONS is only a runtime permission from API 33. It is needed for the
        // foreground narration service's notification; without it the service still runs, so
        // this is requested but never treated as fatal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }

        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    /** Opened from a "time for your ride" notification while the app was already running. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScheduledIntent(intent)
    }

    private fun handleScheduledIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(com.cabeye.rider.schedule.RideScheduler.EXTRA_ID) ?: return
        intent.removeExtra(com.cabeye.rider.schedule.RideScheduler.EXTRA_ID)
        // Handed to the app, not straight to a view model: the rider view model may not exist
        // yet (sign-in screen), and picks the id up the moment it reaches idle.
        (application as CabEyeApp).onScheduledRideDue(id)
    }

    override fun onResume() {
        super.onResume()
        // Permission can be revoked from Settings while the app is backgrounded.
        viewModel?.micPermissionGranted = hasMicPermission()
        signInViewModel?.micPermissionGranted = hasMicPermission()
        // Opening the app is the natural moment for "PSG College, like usual?".
        viewModel?.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // A backgrounded app cannot keep the camera; the view model turns it off and says so.
        viewModel?.onBackground()
    }

    override fun onDestroy() {
        // The message receiver is registered against this Activity; never leak it.
        smsReader.stop()
        super.onDestroy()
    }

    /**
     * Volume keys as a booking trigger.
     *
     * A physical key is findable by touch without looking. Returning `true` consumes the press
     * so the system volume UI does not appear over the surface.
     *
     * Only active in rider mode: a driver reaching for the volume keys wants the volume, and
     * hijacking them in a moving car would be a genuinely bad idea.
     */
    // ---------------------------------------------------------------------------------
    //  Volume keys: a short press changes the volume, holding either key talks, and
    //  volume down pressed three times quickly opens (or closes) the mind map.
    //
    //  Both keys used to be hold-to-talk only, which left a blind rider no way to turn the
    //  narrator up or down while Cab Eye was open — found in the demo. Now a press shorter
    //  than HOLD_TO_TALK_MS is an ordinary volume step (with the system click, so the level
    //  can be heard), and holding past it opens the microphone as before.
    //
    //  The three presses still step the volume as they land (no waiting to see whether a
    //  third is coming); when the third arrives, the volume goes back to where it was before
    //  the first. Only volume down counts, so turning the narrator UP in a hurry can never
    //  open anything. The double tap on the screen (explore) is in RiderSurface.
    // ---------------------------------------------------------------------------------

    private val keyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var volumeKeyTalking = false
    private var pendingTalk: Runnable? = null
    private var volumeDownPresses = 0
    private var lastVolumeDownAt = 0L
    private var volumeBeforePresses = 0

    private fun holdStart() {
        signInViewModel?.onHoldStart() ?: viewModel?.onHoldStart()
    }

    private fun holdEnd() {
        signInViewModel?.onHoldEnd() ?: viewModel?.onHoldEnd()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!volumeKey || (application as CabEyeApp).role.value != AppRole.RIDER ||
            (signInViewModel == null && viewModel == null)
        ) {
            return super.onKeyDown(keyCode, event)
        }
        if (event?.repeatCount == 0) {
            volumeKeyTalking = false
            pendingTalk?.let { keyHandler.removeCallbacks(it) }
            val talk = Runnable {
                volumeKeyTalking = true
                holdStart()
            }
            pendingTalk = talk
            keyHandler.postDelayed(talk, HOLD_TO_TALK_MS)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!volumeKey || (application as CabEyeApp).role.value != AppRole.RIDER ||
            (signInViewModel == null && viewModel == null)
        ) {
            return super.onKeyUp(keyCode, event)
        }
        pendingTalk?.let { keyHandler.removeCallbacks(it) }
        pendingTalk = null
        if (volumeKeyTalking) {
            volumeKeyTalking = false
            holdEnd()
        } else {
            val audio = getSystemService(android.media.AudioManager::class.java)
            val vm = viewModel
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && vm != null && signInViewModel == null && audio != null) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastVolumeDownAt > MULTI_PRESS_GAP_MS) {
                    volumeDownPresses = 0
                    volumeBeforePresses = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                }
                lastVolumeDownAt = now
                if (++volumeDownPresses == MIND_MAP_PRESSES) {
                    volumeDownPresses = 0
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volumeBeforePresses, 0)
                    vm.toggleMindMap()
                    return true
                }
            } else {
                volumeDownPresses = 0
            }
            // A short press: an ordinary volume step on whatever is playing.
            audio?.adjustSuggestedStreamVolume(
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE
                else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.FLAG_SHOW_UI or android.media.AudioManager.FLAG_PLAY_SOUND
            )
        }
        return true
    }

    private companion object {
        /** Holding a volume key longer than this talks; anything shorter changes the volume. */
        const val HOLD_TO_TALK_MS = 350L

        /** Volume down pressed this many times, each within [MULTI_PRESS_GAP_MS], toggles the mind map. */
        const val MIND_MAP_PRESSES = 3
        const val MULTI_PRESS_GAP_MS = 600L
    }
}
