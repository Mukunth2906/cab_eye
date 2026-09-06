package com.cabeye.rider.net

/**
 * Turns whatever a human pasted into the two URLs the app actually needs.
 *
 * ## Why this is a pure object with no Android dependency
 * It is the piece most likely to be wrong, and the wrongness is invisible: a trailing space on
 * a pasted ngrok URL produces a socket that never connects, and a socket that never connects
 * presents to a blind rider as an app that has gone quiet. So it is plain Kotlin, and it is
 * covered by unit tests that run on the JVM without a device.
 *
 * ## What it is defending against
 * Nobody types an ngrok URL. They copy it out of a terminal, and what lands on the clipboard
 * routinely carries a trailing slash, a leading space, a trailing newline, or all three. Every
 * one of those produces a URL that looks correct in a text field and fails at runtime.
 *
 * ## Deriving `wss://` from `https://`
 * The socket URL is **never** typed. It is derived, because the two must agree — an
 * `https://` REST base with a `ws://` socket is a mixed-content failure that shows up only
 * once a ride is already in progress. Asking someone to type both is asking them to keep two
 * strings in sync by hand, and the failure mode of getting that wrong is a silent phone.
 */
object BackendUrl {

    /**
     * Normalises a base URL.
     *
     * Handles, in order:
     *  - surrounding whitespace, including the newline a terminal copy brings along
     *  - a missing scheme (`abc123.ngrok-free.app` becomes `https://abc123.ngrok-free.app`) —
     *    defaulting to https rather than http because every remote host this app will meet is
     *    TLS-terminated, and guessing http would produce a cleartext failure on Android 9+
     *  - one or more trailing slashes
     *  - a `ws://` / `wss://` scheme pasted by mistake, converted back to its http equivalent
     *
     * @return a base URL with a scheme and no trailing slash, or null when [raw] is blank
     */
    fun normaliseBase(raw: String?): String? {
        var value = raw?.trim() ?: return null
        if (value.isEmpty()) return null

        // Strip any stray internal whitespace a wrapped terminal line may have introduced.
        value = value.filterNot { it.isWhitespace() }
        if (value.isEmpty()) return null

        // Someone pasted the socket URL into the base field. Accept it rather than failing:
        // the intent is unambiguous and refusing would be pedantry at the user's expense.
        value = when {
            value.startsWith("wss://", ignoreCase = true) -> "https://" + value.substring(6)
            value.startsWith("ws://", ignoreCase = true) -> "http://" + value.substring(5)
            else -> value
        }

        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "https://$value"
        }

        // Trailing slashes only — an interior one is a real path segment and must survive,
        // in case the backend is ever mounted under a prefix by a proxy.
        while (value.endsWith("/")) {
            value = value.dropLast(1)
        }

        return value.ifEmpty { null }
    }

    /**
     * Derives the WebSocket URL from a normalised base.
     *
     * `https` maps to `wss`, `http` maps to `ws`. Never the other way round: downgrading a
     * secure base to a plaintext socket would leak the ride, and upgrading a plaintext base
     * would simply fail to connect against a server with no TLS.
     */
    fun toSocketBase(normalisedBase: String): String = when {
        normalisedBase.startsWith("https://", ignoreCase = true) ->
            "wss://" + normalisedBase.substring(8)
        normalisedBase.startsWith("http://", ignoreCase = true) ->
            "ws://" + normalisedBase.substring(7)
        else -> normalisedBase
    }

    /** `{base}/health` — what the "Test connection" button calls and speaks the result of. */
    fun healthUrl(normalisedBase: String): String = "$normalisedBase/health"

    /**
     * The full ride socket URL, with identity and the client's replay position.
     *
     * @param lastSeq sequence number of the last event this client actually processed. The
     *   server replays everything after it; passing 0 asks for the whole retained log.
     */
    fun rideSocketUrl(
        normalisedBase: String,
        rideId: String,
        userId: String,
        role: String,
        lastSeq: Long
    ): String = buildString {
        append(toSocketBase(normalisedBase))
        append("/ws/ride?rideId=").append(encode(rideId))
        append("&userId=").append(encode(userId))
        append("&role=").append(encode(role))
        append("&lastSeq=").append(lastSeq)
    }

    /**
     * A short form for reading aloud.
     *
     * The narrator says the host, never the scheme or the path: "connected to abc123 dot
     * ngrok dash free dot app" is already a long sentence, and prefixing it with "h t t p s
     * colon slash slash" costs the rider several more seconds for nothing.
     */
    fun spokenHost(normalisedBase: String?): String {
        val base = normalisedBase ?: return "no address set"
        return base
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
