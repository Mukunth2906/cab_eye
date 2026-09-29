package com.cabeye.rider.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cabeye.rider.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Which of the two surfaces the app is currently presenting. */
enum class AppRole {

    /** The blind rider's voice-first surface. Speech, earcons and the microphone are live. */
    RIDER,

    /**
     * The driver's surface. **Silent, and structurally so.**
     *
     * A driver is driving. Stray TTS is a distraction and an open microphone is a live
     * recording in a vehicle carrying a passenger who cannot see that it is happening. Both
     * are hazards, so switching to this role tears the audio session down rather than merely
     * navigating away from the screen that uses it.
     */
    DRIVER;

    /** The value sent as the `X-Role` header and the socket's `role` query parameter. */
    val wireName: String get() = name

    companion object {
        fun parse(raw: String?): AppRole =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RIDER
    }
}

/** One immutable read of everything that is configurable at runtime. */
data class Settings(
    /** Normalised, no trailing slash. Never null — falls back to the BuildConfig default. */
    val backendBaseUrl: String,
    val role: AppRole,
    /** Stable per-install identity, sent as `X-User-Id`. Generated once. */
    val userId: String
) {
    /** Derived, never stored and never typed. See [BackendUrl.toSocketBase]. */
    val socketBaseUrl: String get() = BackendUrl.toSocketBase(backendBaseUrl)
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "cabeye_settings")

/**
 * Runtime configuration, persisted in DataStore.
 *
 * ## Why DataStore rather than the existing SharedPreferences
 * [com.cabeye.rider.RiderPreferences] stays where it is — it holds the rider's city and theme,
 * which are read synchronously during composition and are tiny. This holds the backend URL and
 * the role, which are read by the networking layer from coroutines and must be observable: a
 * URL change has to reach a live WebSocket client and make it reconnect, and a `Flow` is how
 * that happens without a callback registry.
 *
 * ## Why there is a default at all
 * [BuildConfig.DEFAULT_BACKEND_URL] is baked in at build time, which is what makes the APK
 * shareable. A friend installs it and it already points at the right ngrok tunnel — they never
 * open settings, never paste anything, and the debug screen exists only for when the tunnel
 * URL changes. An app that required configuration before its first run would be unusable by
 * exactly the person it is built for.
 */
class AppSettings(context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val BASE_URL = stringPreferencesKey("backend_base_url")
        val ROLE = stringPreferencesKey("app_role")
        val USER_ID = stringPreferencesKey("user_id")
    }

    /**
     * The current settings, re-emitted on every change.
     *
     * A stored URL that fails to normalise (someone saved whitespace, or an old build wrote
     * something odd) falls back to the compiled-in default rather than propagating null.
     * There is no useful "no backend configured" state for this app: without a backend it
     * cannot book anything, and a rider deserves a working default over an error.
     */
    val flow: Flow<Settings> = appContext.settingsStore.data.map { prefs ->
        Settings(
            backendBaseUrl = BackendUrl.normaliseBase(prefs[Keys.BASE_URL])
                ?: BackendUrl.normaliseBase(BuildConfig.DEFAULT_BACKEND_URL)
                ?: FALLBACK_URL,
            role = AppRole.parse(prefs[Keys.ROLE]),
            userId = prefs[Keys.USER_ID] ?: FALLBACK_USER_ID
        )
    }

    /**
     * A synchronous read, for the two places that genuinely cannot suspend.
     *
     * Used by `Application.onCreate` and by the OkHttp header interceptor. It blocks, briefly,
     * on a local file read — a few milliseconds — and the alternative is worse: the app
     * launching with no known backend and then reconfiguring itself mid-flight, which for the
     * rider means the first thing they say goes nowhere.
     */
    fun current(): Settings = runBlocking { flow.first() }

    suspend fun setBackendUrl(raw: String) {
        val normalised = BackendUrl.normaliseBase(raw) ?: return
        appContext.settingsStore.edit { it[Keys.BASE_URL] = normalised }
    }

    suspend fun setRole(role: AppRole) {
        appContext.settingsStore.edit { it[Keys.ROLE] = role.name }
    }

    /**
     * Ensures a stable per-install id exists.
     *
     * Called once at startup. Identity matters here even without auth: the backend re-stamps
     * every event with the sender it saw at connect time, so two phones sharing an id would be
     * able to speak as each other — and the boarding-code flow depends on the rider's app and
     * the driver's app being genuinely distinguishable.
     */
    /**
     * Replaces the per-install id with the signed-in account's id, so `X-User-Id` on REST and on
     * the socket names the real rider or driver. Called on sign-in and on role switch.
     */
    suspend fun setUserId(id: String) {
        appContext.settingsStore.edit { it[Keys.USER_ID] = id }
    }

    suspend fun ensureUserId(): String {
        val existing = appContext.settingsStore.data.first()[Keys.USER_ID]
        if (existing != null) return existing

        val generated = "user-" + java.util.UUID.randomUUID().toString().take(8)
        appContext.settingsStore.edit { it[Keys.USER_ID] = generated }
        return generated
    }

    private companion object {
        /** Only reachable if BuildConfig's default is itself unusable, which is a build error. */
        const val FALLBACK_URL = "http://10.0.2.2:8080"
        const val FALLBACK_USER_ID = "user-unset"
    }
}
