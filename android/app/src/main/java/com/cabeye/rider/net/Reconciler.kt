package com.cabeye.rider.net

import com.cabeye.rider.audio.Earcon
import com.cabeye.rider.audio.NarrationTier

/**
 * What to say after a reconnect: one sentence, or nothing at all.
 *
 * @param spoken the sentence, or null to stay silent because nothing the rider cares about
 *   changed while the app was deaf
 * @param tier how it reaches them
 * @param earcon a sound to play alongside, or instead
 */
data class Reconciliation(
    val spoken: String?,
    val tier: NarrationTier = NarrationTier.QUEUED,
    val earcon: Earcon? = null
)

/**
 * Works out what a rider missed while the socket was down.
 *
 * ## The rule this exists to enforce
 * On reconnect, **do not resume the stream — announce the delta.** If the driver arrived while
 * the app was offline, the rider hears "Your car has arrived", not the three events that led
 * there. Replaying the backlog would mean a rider standing on a pavement listening to a
 * narration of the recent past while the car they are being told about is already waiting.
 *
 * ## Why this is a pure function
 * It is the piece with the most branches and the least observable behaviour: getting it wrong
 * produces an app that says a slightly wrong thing under conditions that are awkward to
 * reproduce on a device (you have to actually kill wifi at the right moment). Being pure means
 * every branch is covered by a JVM test in seconds.
 *
 * ## The comparison is against what was SPOKEN, not against UI state
 * The input is the last phase the app actually said out loud. Those differ: the screen can show
 * a state that was never narrated (tier 2 events change the display and say nothing), and a
 * rider who cannot see the screen has been told only what was spoken. Reconciling against the
 * screen would announce changes the rider already knew about and skip ones they did not.
 */
object Reconciler {

    /**
     * @param lastSpokenPhase the last phase the app announced aloud; null if it has said nothing
     *   about this ride yet
     * @param server the authoritative snapshot just fetched over REST
     * @return what to say now
     */
    fun reconcile(lastSpokenPhase: RidePhase?, server: RideSnapshot): Reconciliation {

        // Nothing moved. This is the common case after a short blip, and the correct output is
        // silence — the "Connected." acknowledgement has already been spoken by the socket
        // layer, and adding "your ride is still being found" on top of it would be noise.
        if (lastSpokenPhase == server.phase) {
            return Reconciliation(spoken = null)
        }

        return when (server.phase) {

            // Tier 0. The whole reason this class exists: a rider who missed ASSIGNED, ENROUTE
            // and a dozen location updates needs exactly this one sentence, immediately.
            RidePhase.ARRIVED -> Reconciliation(
                spoken = "Your car has arrived." + driverClause(server),
                tier = NarrationTier.INTERRUPT,
                earcon = Earcon.ARRIVED
            )

            RidePhase.ASSIGNED -> Reconciliation(
                spoken = buildString {
                    append(server.driverName.ifBlank { "A driver" })
                    append(" is coming")
                    if (server.etaMinutes > 0) append(". ${spokenMinutes(server.etaMinutes)}")
                    append(".")
                },
                earcon = Earcon.BOOKING_CONFIRMED
            )

            // Deliberately not mentioning distance. The approach phase is wordless by design —
            // the panned, rising earcon carries it — and a reconnect is not a reason to abandon
            // that. Saying "your driver is on the way" once is the whole update.
            RidePhase.ENROUTE -> Reconciliation(
                spoken = "${server.driverName.ifBlank { "Your driver" }} is on the way."
            )

            RidePhase.SEATED -> Reconciliation(
                spoken = "You're in the car."
            )

            RidePhase.IN_TRIP -> Reconciliation(
                spoken = buildString {
                    append("On the way to ${server.destination}")
                    if (server.etaMinutes > 0) append(", ${spokenMinutes(server.etaMinutes)}")
                    append(".")
                },
                earcon = Earcon.BOOKING_CONFIRMED
            )

            RidePhase.COMPLETED -> Reconciliation(
                spoken = buildString {
                    append("Your ride is finished")
                    if (server.fareRupees > 0) append(". ${server.fareRupees} rupees")
                    append(".")
                },
                earcon = Earcon.BOOKING_CONFIRMED
            )

            // Tier 0. A rider standing on a street waiting for a car that is not coming is the
            // worst outcome in this whole system, and it is what happens if this is queued
            // behind anything else.
            RidePhase.CANCELLED -> Reconciliation(
                spoken = "Your ride was cancelled.",
                tier = NarrationTier.INTERRUPT,
                earcon = Earcon.CANCELLED
            )

            // Still looking. Only worth saying if the app had previously announced something
            // further along, which would mean the ride went backwards — a server bug, but one
            // the rider should not be left to infer from silence.
            RidePhase.REQUESTED -> Reconciliation(
                spoken = if (lastSpokenPhase == null) null else "Still finding a driver."
            )

            RidePhase.UNKNOWN -> Reconciliation(spoken = null)
        }
    }

    /**
     * The vehicle description, appended to the arrival announcement.
     *
     * The plate number is deliberately omitted. It is a long string of letters and digits that
     * costs several seconds to speak and helps a blind rider not at all — they cannot check it.
     * The model and colour are worth saying because a companion or passer-by can confirm them,
     * and because the boarding code is what actually verifies the car.
     */
    private fun driverClause(server: RideSnapshot): String = buildString {
        if (server.driverName.isNotBlank()) {
            append(" ${server.driverName}")
            if (server.vehicleModel.isNotBlank()) append(", ${server.vehicleModel}")
            append(".")
        } else if (server.vehicleModel.isNotBlank()) {
            append(" ${server.vehicleModel}.")
        }
    }

    /** "one minute" / "four minutes" — written as it should sound, never as "4 min". */
    private fun spokenMinutes(minutes: Int): String =
        if (minutes == 1) "one minute" else "$minutes minutes"
}
