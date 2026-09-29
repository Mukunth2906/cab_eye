package com.cabeye.rider.memory

import android.content.Context
import android.util.Log
import com.cabeye.rider.auth.AccountInfo
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.RideApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The phone's copy of the signed-in rider's memory.
 *
 * The backend is the source of truth (it records a visit when a ride completes); the phone keeps
 * the last copy it saw, per account, so the memory agent works with no signal and answers the
 * instant the app opens — a suggestion that waits on the network is a suggestion the rider has
 * already talked over.
 *
 * Guests have no memory at all, on the server or here. They were told so.
 */
class MemoryRepository(context: Context, private val api: RideApi, private val scope: CoroutineScope) {

    private val prefs = context.applicationContext.getSharedPreferences("cabeye.memory", Context.MODE_PRIVATE)

    @Volatile
    private var accountId: String? = null

    @Volatile
    var current: RiderMemory = RiderMemory.EMPTY
        private set

    /** True when memory may be used and written for the current rider. */
    val enabled: Boolean get() = accountId != null

    /** Switches to [account]'s memory: cached copy at once, fresh copy from the server behind it. */
    fun bind(account: AccountInfo?) {
        val id = account?.takeUnless { it.isGuest || it.rider?.memoryEnabled == false }?.id
        accountId = id
        current = id?.let { RiderMemory.parse(prefs.getString(key(it), null)) } ?: RiderMemory.EMPTY
        if (id != null) refresh()
    }

    /** Re-reads from the server. Called after sign-in and after every completed ride. */
    fun refresh(onDone: (() -> Unit)? = null) {
        val id = accountId ?: return
        scope.launch {
            when (val result = api.memory()) {
                is ApiResult.Ok -> if (accountId == id) {
                    current = result.value
                    prefs.edit().putString(key(id), result.value.toJson().toString()).apply()
                    Log.i(TAG, "MEMORY refreshed places=${result.value.places.size} trips=${result.value.trips.size}")
                }
                is ApiResult.Failed -> Log.i(TAG, "MEMORY refresh failed: ${result.detail}")
            }
            onDone?.invoke()
        }
    }

    /** Tells the server how a suggestion went; best-effort, never blocks the dialogue. */
    fun reportOutcome(placeKey: String, kind: SuggestionKind, accepted: Boolean, heard: String) {
        if (accountId == null) return
        // Keep the local statistics moving immediately, so the recalibration applies to the
        // very next suggestion even before the server round-trip lands.
        val s = current.stats
        current = current.copy(
            stats = when (kind) {
                SuggestionKind.PROACTIVE -> if (accepted) s.copy(proactiveAccepted = s.proactiveAccepted + 1)
                else s.copy(proactiveRejected = s.proactiveRejected + 1)
                SuggestionKind.REPAIR -> if (accepted) s.copy(repairAccepted = s.repairAccepted + 1)
                else s.copy(repairRejected = s.repairRejected + 1)
            },
            places = current.places.map { p ->
                if (p.placeKey != placeKey) p
                else if (accepted) p.copy(
                    accepted = p.accepted + 1,
                    aliases = if (kind == SuggestionKind.REPAIR) (p.aliases + normaliseHeard(heard))
                        .filter { it.isNotBlank() }.distinct().takeLast(8) else p.aliases
                )
                else p.copy(rejected = p.rejected + 1)
            }
        )
        scope.launch { api.memoryOutcome(placeKey, kind.name, accepted, heard) }
    }

    /** "Forget my history". Clears the phone at once and the server behind it. */
    fun forgetAll(onDone: (Boolean) -> Unit) {
        val id = accountId ?: run { onDone(false); return }
        current = RiderMemory.EMPTY
        prefs.edit().remove(key(id)).apply()
        scope.launch { onDone(api.forgetMemory() is ApiResult.Ok) }
    }

    private fun key(id: String) = "memory.$id"

    private fun normaliseHeard(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TAG = "CabEye.Memory"
    }
}

enum class SuggestionKind {
    /** Offered before the rider said anything: "PSG College, like usual?" */
    PROACTIVE,
    /** Offered to repair something the app could not understand: "Did you mean PSG College?" */
    REPAIR
}
