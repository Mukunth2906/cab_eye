package com.cabeye.rider.auth

import android.content.Context
import android.content.SharedPreferences
import com.cabeye.rider.net.AppRole
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is signed in on this phone, per role.
 *
 * One phone can be both a rider's and (in a demo) a driver's, so everything is kept per
 * [AppRole]: switching role never signs the other one out, and never sends the rider's token
 * with a driver request.
 *
 * The token is stored encrypted ([TokenCipher]); the account is cached in the clear because it
 * holds nothing a lock screen does not already show (a name and a vehicle) and must be readable
 * offline so a rider without signal still hears their own name.
 *
 * Decrypted tokens are kept in memory after the first read — the HTTP interceptor asks on every
 * request, and a Keystore round-trip per request would add latency a rider can hear.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cabeye.auth", Context.MODE_PRIVATE)

    private val tokens = ConcurrentHashMap<AppRole, String>()

    fun token(role: AppRole): String? {
        tokens[role]?.let { return it }
        val sealed = prefs.getString(key(role, "token"), null) ?: return null
        val token = TokenCipher.decrypt(sealed) ?: return null
        tokens[role] = token
        return token
    }

    fun account(role: AppRole): AccountInfo? =
        AccountInfo.parse(prefs.getString(key(role, "account"), null))

    fun hasSession(role: AppRole): Boolean = prefs.contains(key(role, "token"))

    /** @return false when the keystore refused, in which case nothing was saved */
    fun save(role: AppRole, token: String, account: AccountInfo): Boolean {
        val sealed = TokenCipher.encrypt(token) ?: return false
        tokens[role] = token
        prefs.edit()
            .putString(key(role, "token"), sealed)
            .putString(key(role, "account"), account.toJson().toString())
            .putBoolean(KEY_GUEST, if (role == AppRole.RIDER) false else prefs.getBoolean(KEY_GUEST, false))
            .apply()
        return true
    }

    fun updateAccount(role: AppRole, account: AccountInfo) {
        prefs.edit().putString(key(role, "account"), account.toJson().toString()).apply()
    }

    fun clear(role: AppRole) {
        tokens.remove(role)
        prefs.edit()
            .remove(key(role, "token"))
            .remove(key(role, "account"))
            .apply {
                if (role == AppRole.RIDER) remove(KEY_GUEST)
            }
            .apply()
    }

    /** The rider chose "continue without signing in". Remembered so they are not asked daily. */
    var riderIsGuest: Boolean
        get() = prefs.getBoolean(KEY_GUEST, false)
        set(value) = prefs.edit().putBoolean(KEY_GUEST, value).apply()

    private fun key(role: AppRole, field: String) = "${role.name.lowercase()}.$field"

    private companion object {
        const val KEY_GUEST = "rider.guest"
    }
}
