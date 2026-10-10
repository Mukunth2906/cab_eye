package com.cabeye.rider.ui.components

import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.cabeye.rider.state.CameraShare
import com.cabeye.rider.state.FeedbackStep
import com.cabeye.rider.state.NextStep
import com.cabeye.rider.state.PaymentPhase
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.RiderUiState

/**
 * Spoken accessibility announcement for the merged TalkBack node.
 * Kept concise and readable for screen reader speech.
 */
fun announcementFor(uiState: RiderUiState): String {
    if (uiState.sosActive) return "S O S active. Sharing your location."

    return when (val ride = uiState.ride) {
        is RiderState.Idle ->
            "Ready. Hold anywhere and say where you want to go in ${uiState.activeCityName}."

        is RiderState.Listening ->
            if (ride.isFollowUp) "Listening for your answer." else "Listening."

        is RiderState.Resolving ->
            "Understanding your request."

        is RiderState.Clarify ->
            if (ride.isNearMiss) "Did you mean ${ride.optionA.name}? Say yes or no."
            else "Did you mean ${ride.optionA.name} or ${ride.optionB.name}?"

        is RiderState.Confirming ->
            "Booking ${ride.rideType.spokenName} to ${ride.destination}. Say cancel to stop."

        is RiderState.Finding ->
            "Finding a driver for you."

        is RiderState.Assigned ->
            "${ride.driver.name} is your driver, ${ride.etaMinutes} minutes away."

        is RiderState.Approaching ->
            "Your driver is approaching."

        is RiderState.Arrived -> when {
            ride.codeVerified ->
                "Boarding code verified. This is your driver ${ride.driver.name} with ${ride.driver.vehicleModel}."
            ride.verificationFailed ->
                "Boarding code incorrect. Please do not enter the vehicle. Ask driver to repeat, or say S O S."
            else ->
                "Your driver has arrived. Ask the driver to say the four-digit boarding code."
        }

        is RiderState.InTrip -> {
            val stop = ride.currentStop
            when {
                stop == null ->
                    "On the way to ${ride.destination}, approximately ${ride.etaMinutes} minutes remaining."
                stop.status == "WAITING" && !stop.riderBack ->
                    "Stopped at ${stop.name}. Your driver is waiting. When you are back, press I'm back and let the driver say the code."
                else -> "Next stop ${stop.index}, ${stop.name}. Then ${ride.destination}."
            }
        }

        is RiderState.Walking ->
            (if (ride.recording) "Recording your walk. " else "Guiding your walk. ") + ride.prompt +
                ". Press anywhere to speak."

        is RiderState.Planning ->
            "Planning your stops. ${ride.lines.joinToString(". ")}. ${ride.prompt}"

        is RiderState.Done -> {
            val payment = uiState.payment
            when (payment?.phase) {
                PaymentPhase.PAID ->
                    "Paid ${payment.amountRupees} rupees. Action: hear receipt."
                PaymentPhase.AWAITING ->
                    "Pay ${payment.amountRupees} rupees by " +
                        "${payment.method}. Actions: pay, change method, or decline."
                PaymentPhase.PROCESSING ->
                    "Processing payment."
                PaymentPhase.FAILED ->
                    "Payment not completed. ${payment.message}. Action: try again."
                else ->
                    "You have arrived at ${ride.destination}. Fare ${ride.fareRupees} rupees. Duration ${ride.durationMinutes} minutes."
            }
        }

        is RiderState.Feedback -> when (ride.step) {
            FeedbackStep.RATING -> "How was your ride with ${ride.driverName}? Say a number from one to five, report a problem, or skip."
            FeedbackStep.RATING_CHECK -> "${ride.rating} out of 5. Is that right? Say yes or no."
            FeedbackStep.REPORT -> "What went wrong? Say it, or skip."
            FeedbackStep.CONFIRM -> "Send this report: ${ride.report}? Say yes or no."
            FeedbackStep.SENT -> "Thank you."
        }

        is RiderState.NextJourney -> when (ride.step) {
            NextStep.CHOOSE -> "Book another ride now, schedule one for later, or done?"
            NextStep.WHEN -> "When should I book it?"
            NextStep.WHERE -> "${ride.scheduledAtText}. Where should the ride go?"
            NextStep.CONFIRM -> "A ride to ${ride.scheduledTo}, ${ride.scheduledAtText}. Schedule it?"
            NextStep.SCHEDULED -> "Scheduled: ${ride.scheduledTo}, ${ride.scheduledAtText}."
        }
    }
}

/**
 * Builds custom accessibility actions for TalkBack users on the merged root node.
 */
fun buildCustomActions(
    uiState: RiderUiState,
    onCancel: () -> Unit,
    onClarifyChoice: (PlaceOption) -> Unit,
    onNearMissAnswer: (Boolean) -> Unit,
    onSos: () -> Unit,
    onPay: () -> Unit = {},
    onPaymentMethod: (String) -> Unit = {},
    onDeclinePayment: () -> Unit = {},
    onPostRideAction: (String) -> Unit = {},
    onCameraAnswer: (Boolean) -> Unit = {},
    onStopCamera: () -> Unit = {},
    onCodeConfirmed: () -> Unit = {},
    onCallDriver: () -> Unit = {},
    onHelp: () -> Unit = {}
): List<CustomAccessibilityAction> {
    val actions = mutableListOf<CustomAccessibilityAction>()

    // Camera action priority
    when (uiState.camera) {
        CameraShare.ASKING -> {
            actions += CustomAccessibilityAction("Share camera with driver") { onCameraAnswer(true); true }
            actions += CustomAccessibilityAction("Don't share camera") { onCameraAnswer(false); true }
        }
        CameraShare.LIVE -> actions += CustomAccessibilityAction("Stop camera") { onStopCamera(); true }
        CameraShare.OFF -> Unit
    }

    when (val ride = uiState.ride) {
        is RiderState.Idle -> {
            actions += CustomAccessibilityAction("Help and spoken instructions") { onHelp(); true }
        }
        is RiderState.Clarify -> {
            if (ride.isNearMiss) {
                actions += CustomAccessibilityAction("Yes, ${ride.optionA.name}") {
                    onNearMissAnswer(true); true
                }
                actions += CustomAccessibilityAction("No") { onNearMissAnswer(false); true }
            } else {
                actions += CustomAccessibilityAction(ride.optionA.name) {
                    onClarifyChoice(ride.optionA); true
                }
                actions += CustomAccessibilityAction(ride.optionB.name) {
                    onClarifyChoice(ride.optionB); true
                }
            }
        }
        is RiderState.Confirming -> {
            actions += CustomAccessibilityAction("Cancel booking") { onCancel(); true }
        }
        is RiderState.Assigned, is RiderState.Approaching -> {
            actions += CustomAccessibilityAction("Cancel ride") { onCancel(); true }
            actions += CustomAccessibilityAction("Driver info") { onCallDriver(); true }
        }
        is RiderState.Arrived -> {
            if (!ride.codeVerified) {
                actions += CustomAccessibilityAction("Verify boarding code") { onCodeConfirmed(); true }
                actions += CustomAccessibilityAction("Call driver") { onCallDriver(); true }
            }
            actions += CustomAccessibilityAction("Emergency SOS") { onSos(); true }
        }
        is RiderState.Done -> {
            val payment = uiState.payment
            when (payment?.phase) {
                PaymentPhase.AWAITING -> {
                    actions += CustomAccessibilityAction("Pay ${payment.amountRupees} rupees") {
                        onPay(); true
                    }
                    actions += CustomAccessibilityAction("Pay by UPI") { onPaymentMethod("UPI"); true }
                    actions += CustomAccessibilityAction("Pay by card") { onPaymentMethod("CARD"); true }
                    actions += CustomAccessibilityAction("Pay by net banking") {
                        onPaymentMethod("NETBANKING"); true
                    }
                    actions += CustomAccessibilityAction("Decline payment") { onDeclinePayment(); true }
                }
                PaymentPhase.PAID ->
                    actions += CustomAccessibilityAction("Hear receipt") { onPay(); true }
                PaymentPhase.STARTING, PaymentPhase.PROCESSING -> Unit
                PaymentPhase.FAILED ->
                    actions += CustomAccessibilityAction("Try payment again") { onPay(); true }
                null, PaymentPhase.ERROR ->
                    actions += CustomAccessibilityAction("Pay ${ride.fareRupees} rupees") { onPay(); true }
            }
        }
        is RiderState.Feedback -> {
            if (ride.step == FeedbackStep.CONFIRM) {
                actions += CustomAccessibilityAction("Send report") { onPostRideAction("send"); true }
            } else if (ride.step == FeedbackStep.RATING_CHECK) {
                actions += CustomAccessibilityAction("Rating is right") { onPostRideAction("rating-yes"); true }
                actions += CustomAccessibilityAction("Rating is wrong") { onPostRideAction("rating-no"); true }
            } else if (ride.step == FeedbackStep.RATING) {
                actions += CustomAccessibilityAction("Report a problem") { onPostRideAction("report"); true }
            }
            actions += CustomAccessibilityAction("Skip feedback") { onPostRideAction("skip"); true }
        }
        is RiderState.NextJourney -> {
            if (ride.step == NextStep.CHOOSE) {
                actions += CustomAccessibilityAction("Book another ride now") { onPostRideAction("now"); true }
                actions += CustomAccessibilityAction("Schedule a ride for later") { onPostRideAction("schedule"); true }
            }
            if (ride.step == NextStep.CONFIRM) {
                actions += CustomAccessibilityAction("Schedule it") { onPostRideAction("confirm"); true }
            }
            actions += CustomAccessibilityAction("Done") { onPostRideAction("done"); true }
        }
        is RiderState.Walking -> {
            if (ride.recording) {
                actions += CustomAccessibilityAction("Mark a landmark") { onPostRideAction("walk-mark"); true }
                actions += CustomAccessibilityAction("Turning left") { onPostRideAction("walk-left"); true }
                actions += CustomAccessibilityAction("Turning right") { onPostRideAction("walk-right"); true }
            } else {
                actions += CustomAccessibilityAction("Next part") { onPostRideAction("walk-next"); true }
                actions += CustomAccessibilityAction("Repeat") { onPostRideAction("walk-repeat"); true }
            }
            actions += CustomAccessibilityAction("I'm there") { onPostRideAction("walk-done"); true }
        }
        is RiderState.Planning -> {
            actions += CustomAccessibilityAction("Book this trip") { onPostRideAction("plan-yes"); true }
            actions += CustomAccessibilityAction("Change the stops") { onPostRideAction("plan-no"); true }
            actions += CustomAccessibilityAction("Cancel") { onCancel(); true }
        }
        is RiderState.InTrip -> {
            val stop = ride.currentStop
            if (stop != null && stop.status == "WAITING" && !stop.riderBack) {
                actions += CustomAccessibilityAction("I'm back, check the code") { onPostRideAction("im-back"); true }
            }
        }
        else -> Unit
    }

    actions += CustomAccessibilityAction("Emergency S O S") { onSos(); true }
    return actions
}
