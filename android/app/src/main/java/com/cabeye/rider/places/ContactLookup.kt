package com.cabeye.rider.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/** One person from the rider's phonebook. */
data class ContactMatch(
    val name: String,
    val phone: String
) {
    /**
     * What the driver's screen is allowed to show.
     *
     * The driver's app holds the whole number so the dialler can be pre-filled, but a number
     * on screen is a number a stranger can copy, photograph or keep. The last four digits are
     * enough for the driver to confirm they dialled the right person and useless for anything
     * else.
     */
    val maskedPhone: String
        get() {
            val digits = phone.filter { it.isDigit() }
            return if (digits.length >= 4) "ending ${digits.takeLast(4)}" else "number saved"
        }

    /** Spoken back to the rider for confirmation: "Ravi, ending four two one zero." */
    val spokenConfirmation: String
        get() {
            val digits = phone.filter { it.isDigit() }
            if (digits.length < 4) return name
            // Spaced so TTS reads "four two one zero" rather than "four thousand two hundred".
            val spoken = digits.takeLast(4).toCharArray().joinToString(" ")
            return "$name, ending $spoken"
        }
}

/**
 * Finds a person in the rider's phonebook by the name they spoke.
 *
 * ## Why this reads contacts at all
 * "Thadagam Road" is five kilometres long. A rider who cannot see cannot wave the car down or
 * describe which gate they mean, but the friend waiting for them can do both. Giving the
 * driver someone to ring turns an unusable destination into a usable one.
 *
 * ## What leaves the phone
 * Exactly one contact — the one the rider named and then confirmed out loud. The phonebook is
 * queried on-device and never uploaded, never cached, and never sent in bulk. The single
 * chosen contact travels with that one booking and nowhere else.
 *
 * ## On failure
 * Every path returns an empty list rather than throwing. A denied permission, an empty
 * phonebook or a name that matches nothing must all degrade to "book the ride without a
 * contact" — the contact is an enhancement, and a rider standing at a kerb must never lose
 * their cab because of it.
 */
class ContactLookup(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param spokenName what the rider said, filler words already stripped ("ravi")
     * @return matches ranked best-first; empty when permission is absent or nothing matched
     */
    @SuppressLint("MissingPermission")
    fun find(spokenName: String): List<ContactMatch> {
        val needle = normalise(spokenName)
        if (needle.isBlank()) return emptyList()

        if (!hasPermission()) {
            Log.i(TAG, "CONTACT_LOOKUP skipped reason=no-permission")
            return emptyList()
        }

        val matches = mutableListOf<ContactMatch>()
        try {
            // Queried against the phone-number table directly rather than the contact table,
            // because a contact with no number is no use here — the entire point is to give
            // the driver something to dial.
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val nameColumn =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberColumn =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameColumn < 0 || numberColumn < 0) return emptyList()

                val seen = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn).orEmpty()
                    val number = cursor.getString(numberColumn).orEmpty()
                    if (name.isBlank() || number.isBlank()) continue

                    if (!nameMatches(needle, normalise(name))) continue

                    // One entry per person. A contact with a mobile and a landline would
                    // otherwise be read back to the rider twice as two identical choices.
                    val key = number.filter { it.isDigit() }.takeLast(10)
                    if (!seen.add(key)) continue

                    matches += ContactMatch(name.trim(), number.trim())
                }
            }
        } catch (t: Throwable) {
            // A revoked permission mid-query, a locked provider, an OEM contacts app that
            // does something unusual. None of these is worth losing the booking over.
            Log.w(TAG, "CONTACT_LOOKUP failed: ${t.javaClass.simpleName}: ${t.message}")
            return emptyList()
        }

        // Whole-name matches first, then names that merely start with what was said. "Ravi"
        // should reach Ravi before Ravikumar.
        val ranked = matches.sortedBy { match ->
            val n = normalise(match.name)
            when {
                n == needle -> 0
                n.split(' ').any { it == needle } -> 1
                else -> 2
            }
        }

        Log.i(TAG, "CONTACT_LOOKUP query=\"$needle\" matches=${ranked.size}")
        return ranked.take(MAX_MATCHES)
    }

    /**
     * Matches on whole words, never on a bare substring.
     *
     * A substring match would let "ravi" reach "Travis" and, worse, a short spoken name would
     * match half the phonebook — then the app reads a stranger's name back to the rider and
     * offers to send that person's number to a driver.
     */
    private fun nameMatches(needle: String, candidate: String): Boolean {
        if (candidate == needle) return true
        val words = candidate.split(' ').filter { it.isNotBlank() }
        return words.any { it == needle || (needle.length >= MIN_PREFIX && it.startsWith(needle)) }
    }

    private fun normalise(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val TAG = "CabEye.Contacts"

        /** Read back to the rider as choices, so this stays small enough to listen to. */
        const val MAX_MATCHES = 3

        /** Below this, a prefix match is too loose to be anyone in particular. */
        const val MIN_PREFIX = 3
    }
}
