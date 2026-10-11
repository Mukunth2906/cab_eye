package com.cabeye.rider.auth

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cabeye.rider.CabEyeApp
import com.cabeye.rider.audio.Earcon
import com.cabeye.rider.audio.NarrationTier
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.AppRole
import com.cabeye.rider.net.isUnauthorized
import com.cabeye.rider.security.BiometricGate
import com.cabeye.rider.speech.ListenOptions
import com.cabeye.rider.speech.SpeechError
import com.cabeye.rider.speech.SpeechInputListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the rider is in signing in. Each step has exactly one question it is waiting on. */
enum class SignInStep {
    /** Deciding between unlock, guest and first-time sign-in. Silent and brief. */
    CHECKING,
    /** A stored session exists; waiting on the fingerprint prompt. */
    UNLOCK,
    /** "Say your ten digit number." */
    ASK_PHONE,
    /** "I heard 9 8 7 6 5. 4 3 2 1 0. Is that right?" */
    CONFIRM_PHONE,
    /** Talking to the server. */
    SENDING,
    /** Waiting for the code: read from the text message, or said by the rider. */
    ASK_CODE,
    VERIFYING,
    /** First sign-in only: "What should I call you?" */
    ASK_NAME,
    CONFIRM_NAME,
    /** Through the door; MainActivity swaps to the ride surface. */
    DONE
}

/**
 * @param prompt the last question asked, shown large for a sighted helper — the same words that
 *   were spoken, so a helper and the rider are never looking at different instructions
 * @param heard the digits or name currently being confirmed, for the helper to check
 * @param typedFallback which kind of text the helper field accepts on this step, or null
 * @param phoneSoFar digits of the number collected so far, while it is still being said
 * @param phone the number the code was sent to
 * @param code the code as it is filled in, digit by digit
 * @param smsPreview the text message carrying the code, shown as it arrived
 * @param smsTexted true when a real text message was sent and is being waited for
 */
data class SignInUi(
    val step: SignInStep = SignInStep.CHECKING,
    val prompt: String = "",
    val heard: String = "",
    val micOpen: Boolean = false,
    val partial: String = "",
    val typedFallback: TypedField? = null,
    val phoneSoFar: String = "",
    val phone: String = "",
    val code: String = "",
    val smsPreview: String = "",
    val smsTexted: Boolean = false
)

enum class TypedField { PHONE, CODE, NAME }

/**
 * Voice-first sign-in for a blind rider.
 *
 * ## The whole flow
 * ```
 *  stored session?  ── yes ─▶ "Welcome back, Harshini" ─▶ fingerprint ─▶ DONE
 *        │ no
 *        ▼
 *  "Say your ten digit number" ─▶ read back in two groups ─▶ yes ─▶ code sent
 *        ▲      (short? "say the rest")       │ no                   │
 *        └────────────────────────────────────┘     read from the SMS, shown as an incoming
 *                                                    message (no SMS provider), or said
 *                                                                      ▼
 *                                   new account? ─▶ "What should I call you?" ─▶ DONE
 * ```
 * "Skip" is honoured at every step: a rider must never be trapped in sign-in to get a cab.
 *
 * ## Heard once, understood once
 *  - The microphone stays open through pauses (see [ListenOptions]): a number said in two
 *    breaths arrives as one answer, and the answer ends as soon as all ten digits are in.
 *  - A number that comes through short is never thrown away: "I have 9 8 7 6 5. 4 3. Say the
 *    last three." The rider only ever says the part that is missing.
 *  - The microphone is never open while the app is speaking; every question reopens it from
 *    the narrator's `onDone`.
 *  - Every failure is spoken and says what to do next. Three misses in a row and the app stops
 *    asking and says how to resume, rather than looping.
 *  - Numbers are read back in groups, never as one token a TTS engine would read as
 *    "nine billion…".
 */
class RiderSignInViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CabEyeApp
    private val engine get() = app.audioEngine
    private val stt get() = app.speechInput
    private val api get() = app.api
    private val store get() = app.auth

    var ui by mutableStateOf(SignInUi())
        private set

    /** Set by the Activity: shows the system fingerprint / screen-lock prompt. */
    var unlockRequest: ((String, (BiometricGate.Result) -> Unit) -> Unit)? = null

    /** Set by the Activity: waits for the code's text message and hands back the code. */
    var smsCodeRequest: (((String) -> Unit) -> Unit)? = null

    /** Set by the Activity: stops waiting for the text message. */
    var smsCodeCancel: (() -> Unit)? = null

    /**
     * Whether the microphone may be used. When it is granted while a question is waiting —
     * the permission dialog is usually still up when the first question finishes — the
     * question is asked again and the microphone opens, instead of the app going quiet.
     */
    var micPermissionGranted: Boolean = false
        set(value) {
            val was = field
            field = value
            if (value && !was && waitingForPermission) {
                waitingForPermission = false
                if (listensOn(ui.step)) speakThenListen(ui.prompt)
            }
        }

    private var waitingForPermission = false

    private var phone: String = ""
    private var phoneSoFar: String = ""
    private var pendingName: String = ""
    private var account: AccountInfo? = null
    private var misses = 0
    private var micOpenedAt = 0L
    private var reopenJob: Job? = null
    private var silenceJob: Job? = null
    private var startJob: Job? = null

    private companion object {
        const val TAG = "CabEye.SignIn"
        const val MAX_MISSES = 3
        const val MIN_CAPTURE_BEFORE_STOP_MS = 900L

        /**
         * Backstop only. The recogniser session itself waits [ListenOptions.startTimeoutMs] for
         * the rider to begin and reports NO_SPEECH; this catches a recogniser that never
         * reports anything at all.
         */
        const val SILENCE_TIMEOUT_MS = 18_000L
        const val AUDIO_WAIT_MS = 6_000L
        const val CODE_FILL_STEP_MS = 140L
    }

    // =================================================================================
    //  Entry
    // =================================================================================

    /** Called whenever the sign-in surface appears. Safe to call repeatedly. */
    fun start() {
        if (ui.step != SignInStep.CHECKING && ui.step != SignInStep.DONE) return
        reset()
        ui = ui.copy(prompt = "Starting Cab Eye…")
        // On a cold start the text-to-speech engine is still initialising, and a sentence
        // spoken before it is ready is dropped. For a blind rider the first sentence is the
        // only explanation of what is happening, so wait for the voice (up to 6 s) first.
        startJob?.cancel()
        startJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + AUDIO_WAIT_MS
            while (!app.audioReady && System.currentTimeMillis() < deadline) delay(100)
            begin()
        }
    }

    private fun begin() {
        if (store.riderIsGuest) {
            finishAsGuest(announce = false)
            return
        }

        val cached = store.account(AppRole.RIDER)
        if (store.hasSession(AppRole.RIDER) && cached != null) {
            account = cached
            val name = cached.spokenName
            val hello = if (name.isBlank()) "Welcome back." else "Welcome back, $name."
            ask(
                SignInStep.UNLOCK,
                "$hello Touch the fingerprint sensor, or use your screen lock, to open Cab Eye.",
                listen = false
            ) { requestUnlock() }
            return
        }

        ask(
            SignInStep.ASK_PHONE,
            "Welcome to Cab Eye. To sign in, hold the screen, or a volume button, and say your ten " +
                "digit mobile number. Keep holding until you finish. Or hold and say skip to continue " +
                "without signing in.",
            typed = TypedField.PHONE
        )
    }

    private fun reset() {
        phone = ""
        phoneSoFar = ""
        pendingName = ""
        account = null
        misses = 0
        ui = SignInUi()
    }

    // =================================================================================
    //  Input from the surface
    // =================================================================================

    fun onHoldStart() {
        reopenJob?.cancel()
        engine.stopSpeaking()
        when (ui.step) {
            // A press while waiting on the fingerprint means "show me the prompt again".
            SignInStep.UNLOCK -> requestUnlock()
            SignInStep.SENDING, SignInStep.VERIFYING, SignInStep.DONE, SignInStep.CHECKING -> Unit
            else -> openMic(byPress = true)
        }
    }

    fun onHoldEnd() {
        if (!ui.micOpen) return
        val openedAt = micOpenedAt
        val elapsed = System.currentTimeMillis() - openedAt
        if (elapsed >= MIN_CAPTURE_BEFORE_STOP_MS) {
            stt?.stop()
        } else {
            viewModelScope.launch {
                delay(MIN_CAPTURE_BEFORE_STOP_MS - elapsed)
                if (micOpenedAt == openedAt) stt?.stop()
            }
        }
    }

    /** The helper typed the answer instead. Treated exactly like the same words spoken. */
    fun onTyped(text: String) {
        if (text.isBlank()) return
        stt?.cancel()
        closeMic()
        engine.stopSpeaking()
        // A typed number is the whole number, never "the rest" of a half-heard one.
        if (ui.step == SignInStep.ASK_PHONE) setPhoneSoFar("")
        handle(text)
    }

    /** "Continue without signing in" button, for a helper. */
    fun onSkip() {
        stt?.cancel()
        closeMic()
        skip()
    }

    /** "Start the number again" button: clears the digits collected so far. */
    fun onClearNumber() {
        stt?.cancel()
        closeMic()
        engine.stopSpeaking()
        setPhoneSoFar("")
        misses = 0
        ask(SignInStep.ASK_PHONE, "Okay, cleared. Say your ten digit mobile number.", typed = TypedField.PHONE)
    }

    /** "Send the code again" button. */
    fun onResend() {
        if (phone.isBlank()) return
        stt?.cancel()
        closeMic()
        engine.stopSpeaking()
        sendCode()
    }

    /** The code was read from the incoming text message. */
    fun onSmsCode(code: String) {
        if (ui.step != SignInStep.ASK_CODE && ui.step != SignInStep.SENDING) return
        Log.i(TAG, "CODE read from text message")
        reopenJob?.cancel()
        stt?.cancel()
        closeMic()
        engine.stopSpeaking()
        viewModelScope.launch {
            fillCode(code)
            speak("Code received from your messages. Checking it.")
            verify(code)
        }
    }

    /**
     * Skipping the *name* question keeps the rider signed in — they already proved the number.
     * Skipping anything earlier means continuing as a guest.
     */
    private fun skip() {
        val signedIn = account
        when {
            signedIn != null && (ui.step == SignInStep.ASK_NAME || ui.step == SignInStep.CONFIRM_NAME) ->
                finish(signedIn, returning = false)
            // At the fingerprint step the account stays on the phone for next time; this one
            // session just runs as a guest.
            ui.step == SignInStep.UNLOCK -> finishAsGuest(announce = true, remember = false)
            else -> finishAsGuest(announce = true)
        }
    }

    // =================================================================================
    //  Understanding
    // =================================================================================

    private fun handle(text: String) {
        val kind = SignInCommands.classify(text)
        Log.i(TAG, "HEARD step=${ui.step} kind=$kind")

        if (kind == SignInCommands.Kind.SKIP) {
            skip()
            return
        }
        if (kind == SignInCommands.Kind.REPEAT) {
            speakThenListen(ui.prompt)
            return
        }
        if (kind == SignInCommands.Kind.HELP) {
            speakThenListen(helpFor(ui.step))
            return
        }

        when (ui.step) {
            SignInStep.ASK_PHONE -> when {
                kind == SignInCommands.Kind.BACK && SpokenNumbers.digits(text).isEmpty() -> onClearNumber()
                else -> hearPhone(text)
            }

            SignInStep.CONFIRM_PHONE -> {
                // A fresh ten-digit number here is a correction, not a yes or no.
                val corrected = SpokenNumbers.mobileNumber(text)
                when {
                    corrected != null && corrected != phone -> confirmPhone(corrected)
                    kind == SignInCommands.Kind.YES -> sendCode()
                    kind == SignInCommands.Kind.NO || kind == SignInCommands.Kind.BACK -> {
                        setPhoneSoFar("")
                        ask(
                            SignInStep.ASK_PHONE,
                            "Okay. Say your ten digit number again. You can pause between the digits; I'll wait.",
                            typed = TypedField.PHONE
                        )
                    }
                    else -> miss("Say yes if ${SpokenNumbers.speakable(phone)} is right, or no to say it again.")
                }
            }

            SignInStep.ASK_CODE -> {
                val code = SpokenNumbers.otp(text)
                when {
                    code != null -> viewModelScope.launch { fillCode(code); verify(code) }
                    kind == SignInCommands.Kind.RESEND -> sendCode()
                    kind == SignInCommands.Kind.BACK -> {
                        smsCodeCancel?.invoke()
                        setPhoneSoFar("")
                        ask(SignInStep.ASK_PHONE, "Okay. Say your ten digit number.", typed = TypedField.PHONE)
                    }
                    else -> {
                        val digits = SpokenNumbers.digits(text)
                        miss(
                            if (digits.isEmpty()) "Say the six digit code from the text message, or say send again."
                            else "I heard ${digits.length} digits. The code has six. Please say it again."
                        )
                    }
                }
            }

            SignInStep.ASK_NAME -> {
                val name = SignInCommands.name(text)
                if (name == null) {
                    miss("Just say your first name. Or say skip.")
                } else {
                    pendingName = name
                    ask(SignInStep.CONFIRM_NAME, "$name. Is that right?", heard = name)
                }
            }

            SignInStep.CONFIRM_NAME -> when (kind) {
                SignInCommands.Kind.YES -> saveName(pendingName)
                SignInCommands.Kind.NO, SignInCommands.Kind.BACK ->
                    ask(SignInStep.ASK_NAME, "Okay. What should I call you?", typed = TypedField.NAME)
                else -> {
                    // Saying a different name is a correction.
                    val name = SignInCommands.name(text)
                    if (name != null && name != pendingName) {
                        pendingName = name
                        ask(SignInStep.CONFIRM_NAME, "$name. Is that right?", heard = name)
                    } else {
                        miss("Say yes if $pendingName is right, or no to say it again.")
                    }
                }
            }

            SignInStep.UNLOCK -> when (kind) {
                SignInCommands.Kind.BACK -> {
                    // "Sign in with a different number" — the stored account is set aside.
                    store.clear(AppRole.RIDER)
                    account = null
                    ask(SignInStep.ASK_PHONE, "Okay. Say the ten digit number to sign in with.", typed = TypedField.PHONE)
                }
                else -> requestUnlock()
            }

            SignInStep.CHECKING, SignInStep.SENDING, SignInStep.VERIFYING, SignInStep.DONE -> Unit
        }
    }

    /**
     * A number, or part of one. Digits already collected are kept and the new ones added to
     * them, so a number that came through short only ever needs its missing part said.
     */
    private fun hearPhone(text: String) {
        val step = PhoneEntry.next(phoneSoFar, text)
        when (step) {
            is PhoneEntry.Step.Complete -> confirmPhone(step.number)
            is PhoneEntry.Step.Partial -> {
                setPhoneSoFar(step.digits)
                misses = 0
                val left = 10 - step.digits.length
                ask(
                    SignInStep.ASK_PHONE,
                    "I have ${SpokenNumbers.speakable(step.digits)} " +
                        "Say the last $left ${if (left == 1) "digit" else "digits"}.",
                    heard = step.digits,
                    typed = TypedField.PHONE
                )
            }
            PhoneEntry.Step.Nothing -> miss(
                if (phoneSoFar.isEmpty()) "I didn't hear a number. Say your ten digit mobile number."
                else "Say the last ${10 - phoneSoFar.length} digits of your number."
            )
            PhoneEntry.Step.TooLong -> {
                setPhoneSoFar("")
                miss("I heard more than ten digits. Let's start again. Say your ten digit mobile number.")
            }
            PhoneEntry.Step.NotMobile -> {
                setPhoneSoFar("")
                miss("That isn't a mobile number. Indian mobile numbers start with 6, 7, 8 or 9. Say it again.")
            }
        }
    }

    private fun setPhoneSoFar(digits: String) {
        phoneSoFar = digits
        ui = ui.copy(phoneSoFar = digits)
    }

    private fun confirmPhone(number: String) {
        phone = number
        misses = 0
        setPhoneSoFar("")
        ask(
            SignInStep.CONFIRM_PHONE,
            "I heard ${SpokenNumbers.speakable(number)} Is that right?",
            heard = number
        )
    }

    /**
     * The recogniser's guesses, best first; returns the first that fits the current question —
     * a whole mobile number, the rest of one, the six-digit code, or a yes/no.
     */
    private fun chooseTranscript(alternatives: List<String>): String {
        val best = alternatives.first()
        val yesNo: (String) -> Boolean = {
            val k = SignInCommands.classify(it)
            k == SignInCommands.Kind.YES || k == SignInCommands.Kind.NO
        }
        val chosen = when (ui.step) {
            SignInStep.ASK_PHONE -> PhoneEntry.bestGuess(phoneSoFar, alternatives)
            SignInStep.CONFIRM_PHONE -> alternatives.firstOrNull(yesNo)
                ?: alternatives.firstOrNull { SpokenNumbers.mobileNumber(it) != null }
            SignInStep.ASK_CODE -> alternatives.firstOrNull { SpokenNumbers.otp(it) != null }
            SignInStep.CONFIRM_NAME -> alternatives.firstOrNull(yesNo)
            else -> null
        }
        if (chosen != null && chosen != best) Log.i(TAG, "HEARD picked \"$chosen\" over \"$best\"")
        return chosen ?: best
    }

    /** How patient the microphone is for the current question. */
    private fun listenOptions(): ListenOptions {
        val command: (String) -> Boolean = {
            when (SignInCommands.classify(it)) {
                SignInCommands.Kind.SKIP, SignInCommands.Kind.HELP, SignInCommands.Kind.REPEAT,
                SignInCommands.Kind.RESEND -> true
                else -> false
            }
        }
        return when (ui.step) {
            SignInStep.ASK_PHONE -> {
                val needed = 10 - phoneSoFar.length
                ListenOptions(
                    startTimeoutMs = 12_000L,
                    endSilenceMs = 1_200L,
                    // Digits still missing: the rider is reading the number out. Wait for them.
                    incompleteSilenceMs = 4_500L,
                    isComplete = { command(it) || SpokenNumbers.digits(it).length >= needed }
                )
            }
            SignInStep.ASK_CODE -> ListenOptions(
                startTimeoutMs = 15_000L,
                endSilenceMs = 1_200L,
                incompleteSilenceMs = 4_500L,
                isComplete = {
                    command(it) || SignInCommands.classify(it) == SignInCommands.Kind.BACK ||
                        SpokenNumbers.digits(it).length >= 6
                }
            )
            SignInStep.CONFIRM_PHONE, SignInStep.CONFIRM_NAME -> ListenOptions(
                startTimeoutMs = 10_000L,
                endSilenceMs = 1_000L,
                isComplete = { SignInCommands.classify(it) != SignInCommands.Kind.NONE }
            )
            SignInStep.ASK_NAME -> ListenOptions(startTimeoutMs = 10_000L, endSilenceMs = 1_300L)
            else -> ListenOptions()
        }
    }

    // =================================================================================
    //  Server
    // =================================================================================

    private fun sendCode() {
        smsCodeCancel?.invoke()
        CodeNotifier.clear(getApplication())
        ui = ui.copy(phone = phone, code = "", smsPreview = "", smsTexted = false)
        setStep(SignInStep.SENDING, "Sending a code to ${SpokenNumbers.speakable(phone)}")
        speak("Sending a code to your phone.")
        viewModelScope.launch {
            when (val result = api.sendOtp(phone, AppRole.RIDER)) {
                is ApiResult.Ok -> {
                    val sent = result.value
                    val auto = sent.devCode
                    if (auto != null) {
                        // No SMS provider on the backend: the code arrives the way a text
                        // would — as a message notification and on screen — and is filled in.
                        val message = sent.devMessage.ifBlank { "Your Cab Eye code is $auto." }
                        CodeNotifier.show(getApplication(), message)
                        ui = ui.copy(smsPreview = message)
                        setStep(SignInStep.VERIFYING, "Code received. Checking it.")
                        fillCode(auto)
                        speak("Code received: ${SpokenNumbers.speakable(auto)} Checking it.")
                        verify(auto)
                    } else {
                        ui = ui.copy(smsTexted = sent.texted)
                        smsCodeRequest?.invoke { code -> onMain { onSmsCode(code) } }
                        ask(
                            SignInStep.ASK_CODE,
                            "I've sent a six digit code by text message. When it arrives, your phone " +
                                "will ask to let Cab Eye read it. Tap Allow and I'll fill it in. " +
                                "Or say the code yourself, or say send again.",
                            typed = TypedField.CODE
                        )
                    }
                }
                is ApiResult.Failed -> {
                    engine.earcon(Earcon.ERROR)
                    if (result.detail.startsWith("HTTP 429")) {
                        // A code is already on its way — go and wait for it.
                        smsCodeRequest?.invoke { code -> onMain { onSmsCode(code) } }
                        ask(SignInStep.ASK_CODE, "${result.spoken} Then say the code.", typed = TypedField.CODE)
                    } else {
                        ask(
                            SignInStep.CONFIRM_PHONE,
                            "${result.spoken} Say yes to try sending the code again, or no to change the number. " +
                                "Or say skip.",
                            heard = phone
                        )
                    }
                }
            }
        }
    }

    /** Shows the code arriving digit by digit, so a helper can watch it fill in. */
    private suspend fun fillCode(code: String) {
        for (i in 1..code.length) {
            ui = ui.copy(code = code.take(i))
            delay(CODE_FILL_STEP_MS)
        }
    }

    private fun verify(code: String) {
        smsCodeCancel?.invoke()
        ui = ui.copy(code = code)
        setStep(SignInStep.VERIFYING, "Checking the code.")
        viewModelScope.launch {
            when (val result = api.verifyOtp(phone, AppRole.RIDER, code)) {
                is ApiResult.Ok -> {
                    CodeNotifier.clear(getApplication())
                    val signIn = result.value
                    if (!store.save(AppRole.RIDER, signIn.token, signIn.account)) {
                        // The keystore refused. Carry on for this session; next launch asks again.
                        Log.w(TAG, "SIGN_IN token could not be stored")
                    }
                    account = signIn.account
                    engine.earcon(Earcon.UNDERSTOOD)
                    if (signIn.account.spokenName.isBlank()) {
                        ask(
                            SignInStep.ASK_NAME,
                            "You're signed in. What should I call you? Say your first name.",
                            typed = TypedField.NAME
                        )
                    } else {
                        finish(signIn.account, returning = !signIn.isNew)
                    }
                }
                is ApiResult.Failed -> {
                    engine.earcon(Earcon.ERROR)
                    ui = ui.copy(code = "")
                    val reason = reasonOf(result.detail)
                    when {
                        reason == "EXPIRED" || reason == "LOCKED" || reason == "NO_CODE" -> {
                            speak(result.spoken)
                            sendCode()
                        }
                        result.isUnauthorized ->
                            ask(SignInStep.ASK_CODE, "${result.spoken} Say the six digit code.", typed = TypedField.CODE)
                        else ->
                            ask(SignInStep.ASK_CODE, "${result.spoken} Say the code again, or say send again.",
                                typed = TypedField.CODE)
                    }
                }
            }
        }
    }

    private fun saveName(name: String) {
        val current = account ?: return
        setStep(SignInStep.VERIFYING, "Saving your name.")
        viewModelScope.launch {
            val updated = when (val result = api.updateProfile(JSONObject().put("name", name))) {
                is ApiResult.Ok -> result.value
                // Offline or refused: greet them by name anyway and keep it locally.
                is ApiResult.Failed -> current.copy(name = name)
            }
            store.updateAccount(AppRole.RIDER, updated)
            finish(updated, returning = false)
        }
    }

    // =================================================================================
    //  Unlock
    // =================================================================================

    private fun requestUnlock() {
        val cached = account ?: store.account(AppRole.RIDER) ?: run {
            start()
            return
        }
        setStep(SignInStep.UNLOCK, ui.prompt)
        val request = unlockRequest
        if (request == null) {
            unlocked(cached)
            return
        }
        request(cached.spokenName) { result ->
            onMain {
                when (result) {
                    BiometricGate.Result.Confirmed -> unlocked(cached)
                    BiometricGate.Result.Unavailable -> {
                        // No fingerprint or screen lock is set up on this phone at all.
                        // Refusing would lock the owner out of their own account.
                        speak("This phone has no fingerprint or screen lock, so I'll open without one.")
                        unlocked(cached)
                    }
                    BiometricGate.Result.Cancelled, is BiometricGate.Result.Failed -> {
                        engine.earcon(Earcon.NOT_UNDERSTOOD)
                        ask(
                            SignInStep.UNLOCK,
                            "Not unlocked. Hold the screen to try again, or say different number " +
                                "to sign in as someone else."
                        )
                    }
                }
            }
        }
    }

    /**
     * Opens straight away from the cached account — a rider with no signal still gets in —
     * then checks the token with the server in the background. Only a definite 401 signs out.
     */
    private fun unlocked(cached: AccountInfo) {
        finish(cached, returning = true)
        viewModelScope.launch {
            when (val result = api.me()) {
                is ApiResult.Ok -> app.onAccountUpdated(AppRole.RIDER, result.value)
                is ApiResult.Failed -> if (result.isUnauthorized) {
                    Log.i(TAG, "UNLOCK token rejected by server; signing out")
                    app.signOut(AppRole.RIDER)
                    speak("Your sign-in has expired. Please sign in again.", NarrationTier.INTERRUPT)
                }
            }
        }
    }

    // =================================================================================
    //  Endings
    // =================================================================================

    private fun finish(signedIn: AccountInfo, returning: Boolean) {
        reopenJob?.cancel()
        smsCodeCancel?.invoke()
        closeMic()
        val name = signedIn.spokenName
        val line = when {
            returning && name.isNotBlank() -> "Hello $name. Hold anywhere and say where you want to go."
            returning -> "Hello. Hold anywhere and say where you want to go."
            name.isNotBlank() -> "You're all set, $name. Hold anywhere and say where you want to go."
            else -> "You're all set. Hold anywhere and say where you want to go."
        }
        // The ride surface's own first-run welcome would repeat this; it has been said.
        app.preferences.hasHeardWelcome = true
        setStep(SignInStep.DONE, line)
        speak(line)
        app.onSignedIn(AppRole.RIDER, signedIn)
    }

    private fun finishAsGuest(announce: Boolean, remember: Boolean = true) {
        reopenJob?.cancel()
        smsCodeCancel?.invoke()
        closeMic()
        if (remember) store.riderIsGuest = true
        app.preferences.hasHeardWelcome = true
        val line = "Okay, continuing without signing in. I won't remember your places. " +
            "Say sign in any time. Hold anywhere and say where you want to go."
        setStep(SignInStep.DONE, line)
        if (announce) speak(line)
        app.onSignedIn(AppRole.RIDER, AccountInfo.guest())
    }

    // =================================================================================
    //  Speech plumbing
    // =================================================================================

    private fun listensOn(step: SignInStep): Boolean = when (step) {
        SignInStep.ASK_PHONE, SignInStep.CONFIRM_PHONE, SignInStep.ASK_CODE,
        SignInStep.ASK_NAME, SignInStep.CONFIRM_NAME -> true
        else -> false
    }

    /** Moves to [step], speaks [question], and listens for the answer unless told not to. */
    private fun ask(
        step: SignInStep,
        question: String,
        heard: String = "",
        typed: TypedField? = when (step) {
            SignInStep.ASK_PHONE, SignInStep.CONFIRM_PHONE -> TypedField.PHONE
            SignInStep.ASK_CODE -> TypedField.CODE
            SignInStep.ASK_NAME, SignInStep.CONFIRM_NAME -> TypedField.NAME
            else -> null
        },
        listen: Boolean = true,
        then: (() -> Unit)? = null
    ) {
        ui = ui.copy(step = step, prompt = question, heard = heard, typedFallback = typed, partial = "")
        speak(question, NarrationTier.QUEUED) {
            if (listen) reopenMicAfterGap()
            then?.invoke()
        }
    }

    /** Re-asks after a miss, and after [MAX_MISSES] in a row stops asking and says how to resume. */
    private fun miss(reprompt: String) {
        misses++
        engine.earcon(Earcon.NOT_UNDERSTOOD)
        if (misses >= MAX_MISSES) {
            misses = 0
            ui = ui.copy(prompt = reprompt)
            speak("I'm having trouble hearing that. Hold the screen and speak when you're ready, or type it below, or say skip.")
            return
        }
        ui = ui.copy(prompt = reprompt)
        speakThenListen(reprompt)
    }

    private fun speakThenListen(text: String) {
        speak(text, NarrationTier.QUEUED) { reopenMicAfterGap() }
    }

    private fun setStep(step: SignInStep, prompt: String) {
        ui = ui.copy(step = step, prompt = prompt, typedFallback = null, partial = "")
    }

    private fun speak(text: String, tier: NarrationTier = NarrationTier.QUEUED, onDone: (() -> Unit)? = null) {
        engine.speak(text, tier, onDone = onDone?.let { cb -> { onMain { cb() } } })
    }

    /**
     * After a question: nothing opens by itself. Rider mode listens only while the rider holds
     * the screen or a volume key — an app that listened on its own heard its own voice and the
     * room around it.
     */
    private fun reopenMicAfterGap() {
        reopenJob?.cancel()
    }

    private fun openMic(byPress: Boolean) {
        reopenJob?.cancel()
        if (!byPress) return
        if (!listensOn(ui.step)) return
        if (!micPermissionGranted) {
            waitingForPermission = true
            speak("Please allow the microphone so I can hear you. You can also type your answer below.")
            return
        }
        val recogniser = stt ?: return
        if (!recogniser.isAvailable) {
            speak(SpeechError.UNAVAILABLE.spokenExplanation + " Ask someone to type the answer on screen.")
            return
        }
        if (recogniser.isListening) return
        ui = ui.copy(micOpen = true, partial = "")
        micOpenedAt = System.currentTimeMillis()
        recogniser.start(listener, listenOptions())
        // Held: a pause does not end the answer; letting go does.
        recogniser.setHeld(true)
        armSilenceWatch()
    }

    private fun closeMic() {
        silenceJob?.cancel()
        if (ui.micOpen) ui = ui.copy(micOpen = false)
    }

    private fun armSilenceWatch() {
        silenceJob?.cancel()
        silenceJob = viewModelScope.launch {
            delay(SILENCE_TIMEOUT_MS)
            if (!ui.micOpen) return@launch
            stt?.cancel()
            closeMic()
            onHeardNothing()
        }
    }

    private fun onHeardNothing() {
        misses++
        if (misses >= MAX_MISSES) {
            misses = 0
            speak("I'll wait. Hold the screen when you're ready, or type your answer below.")
        } else {
            speakThenListen(ui.prompt)
        }
    }

    private val listener = object : SpeechInputListener {
        override fun onReadyForSpeech() = onMain { engine.earcon(Earcon.LISTENING_START) }

        override fun onPartial(text: String) = onMain {
            if (text.isNotBlank()) armSilenceWatch()
            ui = ui.copy(partial = text)
        }

        override fun onLevel(level: Float) = Unit

        override fun onFinal(text: String) = onMain {
            closeMic()
            engine.earcon(Earcon.LISTENING_END)
            handle(text)
        }

        override fun onFinalAlternatives(alternatives: List<String>) = onMain {
            if (alternatives.isEmpty()) return@onMain
            closeMic()
            engine.earcon(Earcon.LISTENING_END)
            val chosen = chooseTranscript(alternatives)
            ui = ui.copy(partial = chosen)
            handle(chosen)
        }

        override fun onError(error: SpeechError) = onMain {
            closeMic()
            when (error) {
                SpeechError.PERMISSION, SpeechError.UNAVAILABLE, SpeechError.LANGUAGE ->
                    speak(error.spokenExplanation + " You can also type your answer.")
                else -> onHeardNothing()
            }
        }
    }

    private fun helpFor(step: SignInStep): String = when (step) {
        SignInStep.ASK_PHONE, SignInStep.CONFIRM_PHONE ->
            "Say your ten digit mobile number. You can say it in groups and pause between them; " +
                "I'll wait. Say start again to clear it, or skip to use the app without signing in."
        SignInStep.ASK_CODE ->
            "Say the six digit code from the text message. Say send again for a new code, or go back to change the number."
        SignInStep.ASK_NAME, SignInStep.CONFIRM_NAME ->
            "Say the name you'd like me to call you. Say skip to leave it."
        SignInStep.UNLOCK ->
            "Touch the fingerprint sensor or use your screen lock. Say different number to sign in as someone else."
        else -> "One moment."
    }

    private fun reasonOf(detail: String): String =
        runCatching { JSONObject(detail.substringAfter(": ", "")).optString("reason", "") }.getOrDefault("")

    private inline fun onMain(crossinline block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onCleared() {
        reopenJob?.cancel()
        silenceJob?.cancel()
        smsCodeCancel?.invoke()
        super.onCleared()
    }
}

/**
 * Collecting a mobile number across answers. Pure, so every path is a JVM test.
 */
object PhoneEntry {

    sealed interface Step {
        data class Complete(val number: String) : Step
        /** Fewer than ten digits so far; [digits] is everything collected. */
        data class Partial(val digits: String) : Step
        object Nothing : Step
        object TooLong : Step
        object NotMobile : Step
    }

    /** What [heard] adds to the [soFar] digits. A whole number on its own always wins. */
    fun next(soFar: String, heard: String): Step {
        SpokenNumbers.mobileNumber(heard)?.let { return Step.Complete(it) }
        val digits = SpokenNumbers.digits(heard)
        if (digits.isEmpty()) return Step.Nothing
        val combined = soFar + digits
        SpokenNumbers.mobileNumber(combined)?.let { return Step.Complete(it) }
        // ("+91" or a leading 0 ahead of the number was already accepted by mobileNumber.)
        return when {
            combined.length < 10 -> Step.Partial(combined)
            combined.length == 10 -> Step.NotMobile
            else -> Step.TooLong
        }
    }

    /**
     * Of the recogniser's guesses, the first that completes the number; else the first that
     * keeps it within ten digits; else null (the best guess stands).
     */
    fun bestGuess(soFar: String, alternatives: List<String>): String? =
        alternatives.firstOrNull { next(soFar, it) is Step.Complete }
            ?: alternatives.firstOrNull { next(soFar, it) is Step.Partial }
}
