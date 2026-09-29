package com.cabeye.rider

import android.content.Context
import android.content.SharedPreferences
import com.cabeye.rider.places.City
import com.cabeye.rider.state.ThemeChoice

/**
 * The two settings the rider changes by voice, kept across restarts.
 *
 * ## Why these two persist and nothing else does
 * Both are chosen for a reason that does not change between sessions. A rider who switched to
 * the high-contrast yellow theme did so because of their eyes, and being handed the default
 * again on next launch would mean re-discovering the command every single time — by voice,
 * from a screen they may not be able to read. The same goes for the city: it is where they
 * live, not what they are doing right now.
 *
 * Everything else in this app is deliberately session-scoped, because ride state that outlives
 * the process would be a lie about a cab that is not coming.
 *
 * ## Why this is separate from `Gazetteer.activeCity`
 * So the scoring path stays a pure JVM object with no Android dependency and can be proved by
 * unit test. This class is the only thing that knows about storage; it reads at startup and
 * writes on change, and the resolution logic never learns that either happened.
 */
class RiderPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cabeye.rider", Context.MODE_PRIVATE)

    /**
     * The gazetteer's city.
     *
     * Defaults to Coimbatore: this build is demonstrated there, and defaulting to the wrong
     * city would make every first-run utterance fail in the one way the recovery ladder cannot
     * fix by asking again.
     */
    var city: City
        get() = runCatching { City.valueOf(prefs.getString(KEY_CITY, null) ?: DEFAULT_CITY.name) }
            .getOrDefault(DEFAULT_CITY)
        set(value) = prefs.edit().putString(KEY_CITY, value.name).apply()

    /** The visual theme. Deep dark by default — least glare, and free on OLED. */
    var theme: ThemeChoice
        get() = runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, null) ?: DEFAULT_THEME.name) }
            .getOrDefault(DEFAULT_THEME)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /**
     * Whether the rider has been told which city this build covers.
     *
     * Said once on first run rather than on every launch. A blind rider hearing the same
     * preamble every time they open the app is being charged the same seconds repeatedly for
     * information they already have — and speech time is task time.
     */
    var hasHeardWelcome: Boolean
        get() = prefs.getBoolean(KEY_WELCOMED, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOMED, value).apply()

    /**
     * Whether the camera starts without asking when the driver requests it.
     *
     * Off by default: it is the rider's camera, and a blind rider cannot see what it shows.
     * Turned on only by the rider saying "always" to the question, or by a helper in Settings.
     */
    var alwaysShareCamera: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_CAMERA, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_CAMERA, value).apply()

    /** The narrator's speed, 1.0 = normal. Changed by "speak slower" / "speak faster". */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value).apply()

    private companion object {
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_ALWAYS_CAMERA = "always_share_camera"
        const val KEY_CITY = "active_city"
        const val KEY_THEME = "theme_choice"
        const val KEY_WELCOMED = "has_heard_welcome"

        val DEFAULT_CITY = City.COIMBATORE
        val DEFAULT_THEME = ThemeChoice.DEEP_DARK
    }
}
