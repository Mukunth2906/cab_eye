package com.cabeye.rider.auth

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.cabeye.rider.MainActivity
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Reads the sign-in code out of the incoming text message, so nobody has to read it aloud.
 *
 * Uses Google's SMS User Consent API: when a message with a code arrives (from a sender not in
 * the rider's contacts), the system shows one sheet — "Allow Cab Eye to read this message?" —
 * and on Allow hands this app that one message. No SMS permission, no access to any other
 * message. The code is then filled in and checked exactly as if it had been said.
 *
 * @param launchConsent shows the system's consent sheet; the Activity owns the launcher and
 *   passes the result back to [onConsentResult]
 */
class SmsCodeReader(
    private val context: Context,
    private val launchConsent: (Intent) -> Unit
) {
    private var receiver: BroadcastReceiver? = null
    private var onCode: ((String) -> Unit)? = null

    /** Starts waiting (up to five minutes) for the next message carrying a code. */
    fun start(onCode: (String) -> Unit) {
        stop()
        this.onCode = onCode
        SmsRetriever.getClient(context).startSmsUserConsent(null)
            .addOnSuccessListener { Log.i(TAG, "waiting for the code message") }
            .addOnFailureListener { Log.w(TAG, "SMS consent unavailable: ${it.message}") }

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_STATUS, Status::class.java)
                when (status?.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        val consent = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                        if (consent != null) {
                            runCatching { launchConsent(consent) }
                                .onFailure { Log.w(TAG, "could not show the consent sheet: ${it.message}") }
                        }
                    }
                    CommonStatusCodes.TIMEOUT -> {
                        Log.i(TAG, "no code message within five minutes")
                        stop()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED
        )
        receiver = r
    }

    /** The rider answered the consent sheet. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        val callback = onCode
        stop()
        if (resultCode != Activity.RESULT_OK) {
            Log.i(TAG, "consent declined")
            return
        }
        val code = sixDigitCodeIn(data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE))
        if (code == null) {
            Log.w(TAG, "message had no six digit code")
            return
        }
        callback?.invoke(code)
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        onCode = null
    }

    private companion object {
        const val TAG = "CabEye.Sms"
    }
}

/** The first stand-alone six-digit number in [message]: "Your Cab Eye code is 482913." → "482913". */
fun sixDigitCodeIn(message: String?): String? =
    message?.let { Regex("(?<!\\d)(\\d{6})(?!\\d)").find(it)?.groupValues?.get(1) }

/**
 * Shows a sign-in code as an incoming message when the backend has no SMS provider.
 *
 * The rider and anyone helping see exactly what a real text would say, where a real text would
 * appear, instead of the code vanishing into the server log.
 */
object CodeNotifier {
    private const val CHANNEL_ID = "cabeye.codes"
    private const val NOTIFICATION_ID = 4_210

    fun show(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sign-in codes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The code that signs you in to Cab Eye"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("CABEYE")
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setCategory(android.app.Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
