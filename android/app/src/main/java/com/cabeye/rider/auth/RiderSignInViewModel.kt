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
    /** "Say the six digit code." Only reached when the code could not be read automatically. */
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
 */
data class SignInUi(
    val step: SignInStep = SignInStep.CHECKING,
    val prompt: String = "",
    val heard: String = "",
    val micOpen: Boolean = false,
    val partial: String = "",
    val typedFallback: TypedField? = null
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
 *        ▲                                   │ no                      │
 *        └───────────────────────────────────┘          read automatically (mock / SMS)
 *                                                        or "say the code"
 *                                                                      ▼
 *                                   new account? ─▶ "What should I call you?" ─▶ DONE
 * ```
 * "Skip" is honoured at every step: a rider must never be trapped in sign-in to get a cab.
 *
 * ## Rules carried over from the ride surface
 *  - The microphone is never open while the app is speaking; every question reopens it from
 *    the narrator's `onDone`.
 *  - Every failure is spoken and says what to do next. Three misses in a row and the app stops
 *    asking and says how to resume, rather than looping.
 *  - Numbers are read back in groups of five, never as one token a TTS engine would read as
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

    var micPermissionGranted: Boolean = false

    private var phone: String = ""
    private var pendingName: String = ""
    private var account: AccountInfo? = null
    private var misses = 0

    /**
     * Two-part number entry. After a full number is misheard, the rider says the first five
     * digits and then the last five: short groups are recognised far more reliably than ten
     * digits in one breath.
     */
    private var phoneInParts = false
    private var phoneFirstPart: String? = null
    private var micOpenedAt = 0L
    private var reopenJob: Job? = null
    private var silenceJob: Job? = null

    private companion object {
        const val TAG = "CabEye.SignIn"
        const val MAX_MISSES = 3
        const val MIC_REOPEN_GAP_MS = 900L
        const val MIN_CAPTURE_BEFORE_STOP_MS = 900L
        const val SILENCE_TIMEOUT_MS = 9_000L
        const val AUDIO_WAIT_MS = 6_000L
    }

    // =================================================================================
    //  Entry
    // =================================================================================

    /** Called whenever the sign-in surface appears. Safe to call repeatedly. */
    fun start() {
        if (ui.step != SignInStep.CHECKING && ui.step != SignInStep.DONE) return
        reset()
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

    private var startJob: Job? = null

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
            "Welcome to Cab Eye. I'll sign you in with your mobile number, so your trips and " +
                "favourite places are remembered. Say your ten digit number now. " +
                "Or say skip to continue without signing in.",
            typed = TypedField.PHONE
        )
    }

    private fun reset() {
        phoneInParts = false
        phoneFirstPart = null
        phone = ""
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
            else -> openMic()
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
        handle(text)
    }

    /** "Continue without signing in" button, for a helper. */
    fun onSkip() {
        stt?.cancel()
        closeMic()
        skip()
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
            SignInStep.ASK_PHONE -> hearPhone(text)

            SignInStep.CONFIRM_PHONE -> {
                // A fresh ten-digit number here is a correction, not a yes or no.
                val corrected = SpokenNumbers.mobileNumber(text)
                when {
                    corrected != null && corrected != phone -> hearPhone(text)
                    kind == SignInCommands.Kind.YES -> sendCode()
                    kind == SignInCommands.Kind.NO || kind == SignInCommands.Kind.BACK -> {
                        // Misheard once already: go straight to the easier two-part entry.
                        phoneInParts = true
                        phoneFirstPart = null
                        ask(SignInStep.ASK_PHONE, "Okay. Let's do it in two parts. Say the first five digits.", typed = TypedField.PHONE)
                    }
                    else -> miss("Say yes if ${SpokenNumbers.speakable(phone)} is right, or no to say it again.")
                }
            }

            SignInStep.ASK_CODE -> {
                val code = SpokenNumbers.otp(text)
                when {
                    code != null -> verify(code)
                    kind == SignInCommands.Kind.RESEND -> sendCode()
                    kind == SignInCommands.Kind.BACK ->
                        ask(SignInStep.ASK_PHONE, "Okay. Say your ten digit number.", typed = TypedField.PHONE)
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

    private fun hearPhone(text: String) {
        // A whole number is always welcome, even halfway through two-part entry.
        SpokenNumbers.mobileNumber(text)?.let { confirmPhone(it); return }

        val digits = SpokenNumbers.digits(text)

        if (phoneInParts) {
            val first = phoneFirstPart
            when {
                digits.length != 5 -> miss(
                    if (first == null) "Say just the first five digits of your number."
                    else "Say the last five digits."
                )
                first == null -> {
                    phoneFirstPart = digits
                    misses = 0
                    ask(
                        SignInStep.ASK_PHONE,
                        "${SpokenNumbers.speakable(digits)} Now say the last five digits.",
                        heard = digits
                    )
                }
                else -> {
                    val number = SpokenNumbers.mobileNumber(first + digits)
                    if (number != null) {
                        confirmPhone(number)
                    } else {
                        phoneFirstPart = null
                        ask(
                            SignInStep.ASK_PHONE,
                            "That doesn't make a mobile number. Let's start again. Say the first five digits."
                        )
                    }
                }
            }
            return
        }

        // The full number did not come through: switch to two parts rather than asking the
        // rider to repeat ten digits that were just misheard.
        phoneInParts = true
        phoneFirstPart = null
        misses = 0
        val heardPart = when {
            digits.isEmpty() -> "I didn't catch a number. "
            else -> "I heard ${digits.length} digits. "
        }
        ask(SignInStep.ASK_PHONE, heardPart + "Let's do it in two parts. Say the first five digits.")
    }

    private fun confirmPhone(number: String) {
        phone = number
        misses = 0
        phoneInParts = false
        phoneFirstPart = null
        ask(
            SignInStep.CONFIRM_PHONE,
            "I heard ${SpokenNumbers.speakable(number)} Is that right?",
            heard = number
        )
    }

    /**
     * The recogniser's guesses, best first; returns the first that fits the current question —
     * a whole mobile number, a five-digit half, the six-digit code, or a yes/no.
     */
    private fun chooseTranscript(alternatives: List<String>): String {
        val best = alternatives.first()
        val yesNo: (String) -> Boolean = {
            val k = SignInCommands.classify(it)
            k == SignInCommands.Kind.YES || k == SignInCommands.Kind.NO
        }
        val chosen = when (ui.step) {
            SignInStep.ASK_PHONE ->
                alternatives.firstOrNull { SpokenNumbers.mobileNumber(it) != null }
                    ?: if (phoneInParts) alternatives.firstOrNull { SpokenNumbers.digits(it).length == 5 } else null
            SignInStep.CONFIRM_PHONE -> alternatives.firstOrNull(yesNo)
            SignInStep.ASK_CODE -> alternatives.firstOrNull { SpokenNumbers.otp(it) != null }
            SignInStep.CONFIRM_NAME -> alternatives.firstOrNull(yesNo)
            else -> null
        }
        if (chosen != null && chosen != best) Log.i(TAG, "HEARD picked \"$chosen\" over \"$best\"")
        return chosen ?: best
    }

    // =================================================================================
    //  Server
    // =================================================================================

    private fun sendCode() {
        setStep(SignInStep.SENDING, "Sending a code to your phone.")
        speak("Sending a code to your phone.")
        viewModelScope.launch {
            when (val result = api.sendOtp(phone, AppRole.RIDER)) {
                is ApiResult.Ok -> {
                    val sent = result.value
                    val auto = sent.devCode
                    if (auto != null) {
                        // Mock delivery: the code came back in the reply. This is the same
                        // zero-touch path the SMS Retriever gives once a real SMS provider is
                        // connected — the rider never has to hear, hold or repeat the code.
                        speak("Code received. Checking it.")
                        verify(auto)
                    } else {
                        ask(
                            SignInStep.ASK_CODE,
                            "I've sent a six digit code by text message. Say the code when you have it, " +
                                "or say send again.",
                            typed = TypedField.CODE
                        )
                    }
                }
                is ApiResult.Failed -> {
                    engine.earcon(Earcon.ERROR)
                    if (result.detail.startsWith("HTTP 429")) {
                        // A code is already on its way — go and wait for it.
                        ask(SignInStep.ASK_CODE, "${result.spoken} Then say the code.", typed = TypedField.CODE)
                    } else {
                        ask(SignInStep.ASK_PHONE, "${result.spoken} Say your number again, or say skip.",
                            typed = TypedField.PHONE)
                    }
                }
            }
        }
    }

    private fun verify(code: String) {
        setStep(SignInStep.VERIFYING, "Checking the code.")
        viewModelScope.launch {
            when (val result = api.verifyOtp(phone, AppRole.RIDER, code)) {
                is ApiResult.Ok -> {
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
            speak("I'm having trouble hearing that. Hold the screen and speak when you're ready, or say skip.")
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

    private fun reopenMicAfterGap() {
        reopenJob?.cancel()
        reopenJob = viewModelScope.launch {
            delay(MIC_REOPEN_GAP_MS)
            openMic()
        }
    }

    private fun openMic() {
        reopenJob?.cancel()
        if (!micPermissionGranted) {
            speak("I need microphone permission to hear you. You can also type your answer.")
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
        recogniser.start(listener)
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
            speak("I'll wait. Hold the screen when you're ready.")
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
            handle(chooseTranscript(alternatives))
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
            "Say your ten digit mobile number, one digit at a time if you like. Say skip to use the app without signing in."
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
        super.onCleared()
    }
}
