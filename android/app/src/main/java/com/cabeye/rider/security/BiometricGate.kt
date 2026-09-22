package com.cabeye.rider.security

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks the phone's owner to confirm a payment with fingerprint, face, or — if the phone has
 * neither enrolled — the phone's own screen-lock PIN, pattern or password.
 *
 * Uses Android's system BiometricPrompt, not a screen of our own:
 *  - the fingerprint never reaches this app; Android only reports "the owner confirmed";
 *  - the system prompt is already accessible — TalkBack announces it and guides the finger
 *    to the sensor — which a custom screen for a blind customer would have to reinvent;
 *  - DEVICE_CREDENTIAL is allowed alongside biometrics, so a phone without a fingerprint
 *    still gets a real check (its screen lock) instead of none.
 *
 * BIOMETRIC_WEAK (not STRONG) is deliberate: STRONG | DEVICE_CREDENTIAL is unsupported on
 * Android 9–10, and WEAK also admits face unlock, which is far easier to use without sight
 * than finding a sensor.
 */
object BiometricGate {

    sealed interface Result {
        /** The owner confirmed. Safe to pay. */
        data object Confirmed : Result
        /** The customer backed out of the prompt. Nothing should be charged. */
        data object Cancelled : Result
        /** Locked out, or a hardware error. Nothing should be charged. */
        data class Failed(val reason: String) : Result
        /** No fingerprint, no face, and no screen lock on this phone: there is nothing to check with. */
        data object Unavailable : Result
    }

    private const val TAG = "CabEye.Biometric"
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun authenticate(activity: FragmentActivity, amountRupees: Int, onResult: (Result) -> Unit) {
        val can = BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.i(TAG, "BIOMETRIC unavailable code=$can")
            onResult(Result.Unavailable)
            return
        }

        // Exactly one result is delivered, whatever the prompt does.
        var delivered = false
        fun deliver(result: Result) {
            if (delivered) return
            delivered = true
            onResult(result)
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(TAG, "BIOMETRIC confirmed")
                    deliver(Result.Confirmed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.i(TAG, "BIOMETRIC error code=$errorCode msg=$errString")
                    deliver(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT -> Result.Unavailable
                            else -> Result.Failed(errString.toString())
                        }
                    )
                }

                // One unrecognised finger is not a result: the system prompt stays open and
                // lets the customer try again, and says so itself.
                override fun onAuthenticationFailed() {
                    Log.i(TAG, "BIOMETRIC attempt not recognised")
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm payment of ₹$amountRupees")
            .setSubtitle("Cab Eye · test mode")
            .setDescription("Use your fingerprint, face or screen lock to pay $amountRupees rupees.")
            // No negative button: it is not allowed together with DEVICE_CREDENTIAL, and the
            // system prompt already offers "Use PIN" and can be dismissed with Back.
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        try {
            prompt.authenticate(info)
        } catch (t: Throwable) {
            Log.w(TAG, "BIOMETRIC prompt failed to open", t)
            deliver(Result.Failed("The fingerprint check could not start"))
        }
    }
}
