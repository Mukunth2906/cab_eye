package com.cabeye.rider.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.places.Gazetteer
import com.cabeye.rider.state.CameraShare
import com.cabeye.rider.state.FeedbackStep
import com.cabeye.rider.state.NextStep
import com.cabeye.rider.state.PaymentPhase
import com.cabeye.rider.state.PaymentUi
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.RiderUiState
import com.cabeye.rider.ui.components.CabEyeHeader
import com.cabeye.rider.ui.components.DestinationCard
import com.cabeye.rider.ui.components.DriverCard
import com.cabeye.rider.ui.components.EmergencyOverlay
import com.cabeye.rider.ui.components.PrimaryAction
import com.cabeye.rider.ui.components.SecondaryAction
import com.cabeye.rider.ui.components.SosButton
import com.cabeye.rider.ui.components.StateHeadline
import com.cabeye.rider.ui.components.StatusIndicator
import com.cabeye.rider.ui.components.SupportingText
import com.cabeye.rider.ui.components.VoiceOrb
import com.cabeye.rider.ui.components.VoiceOrbState
import com.cabeye.rider.ui.components.announcementFor
import com.cabeye.rider.ui.components.buildCustomActions
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.CabEyeSpacing
import com.cabeye.rider.ui.theme.CabEyeType
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

/**
 * The redesigned Cab Eye Rider Surface.
 *
 * Implements a clean, dark, high-contrast, voice-first accessibility experience
 * for blind and low-vision users:
 * - Almost-black background (#050505)
 * - Large, bold typography (36-48sp headlines, 20-26sp body)
 * - Central animated VoiceOrb with glowing concentric rings
 * - Minimal cards and rounded controls
 * - Semantic accents: Blue (voice), Purple (processing), Yellow (finding/approaching),
 *   Green (success), Red (emergency/SOS)
 * - Whole screen hold-to-talk gesture preserved
 * - TalkBack semantics and custom actions preserved
 */
@Composable
fun RiderSurface(
    uiState: RiderUiState,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onCancel: () -> Unit,
    onClarifyChoice: (PlaceOption) -> Unit,
    onNearMissAnswer: (Boolean) -> Unit,
    onCodeConfirmed: () -> Unit,
    onSos: () -> Unit,
    onDismissSos: () -> Unit,
    onPaymentResult: (String?) -> Unit,
    onPay: () -> Unit,
    onPaymentMethod: (String) -> Unit,
    onDeclinePayment: () -> Unit,
    onOpenSettings: () -> Unit,
    onPostRideAction: (String) -> Unit = {},
    onCameraAnswer: (Boolean) -> Unit = {},
    onStopCamera: () -> Unit = {},
    onCallDriver: () -> Unit = {},
    onHelp: () -> Unit = {},
    onQuickCommand: (String) -> Unit = {},
    explore: com.cabeye.rider.explore.ExploreUi = com.cabeye.rider.explore.ExploreUi(),
    mindMap: MindMapActions? = null,
    exploreActions: ExploreActions? = null,
    onCameraPreview: (androidx.camera.core.Preview.SurfaceProvider?) -> Unit = {},
    mindMapOpen: Boolean = false,
    onMindMapOpen: (Boolean) -> Unit = {},
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val announcement = announcementFor(uiState)

    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val idle by rememberUpdatedState(uiState.ride is RiderState.Idle)

    // Trigger distinctive haptic on state change
    LaunchedEffect(uiState.ride::class, uiState.sosActive) {
        haptics.perform(hapticFor(uiState))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar: title, connection, and the Settings button. Deliberately OUTSIDE the
            // hold-to-talk area below, so touching Settings never opens the microphone.
            CabEyeHeader(
                cityName = uiState.activeCityName,
                connected = uiState.connected,
                onOpenSettings = onOpenSettings,
                onOpenFeatures = if (uiState.ride is RiderState.Idle) ({ onMindMapOpen(true) }) else null,
                onOpenExplore = if (uiState.ride is RiderState.Idle && exploreActions != null) ({ exploreActions.open() }) else null
            )

            // Ride module: the driver asking to see the camera, or the camera shared. Above the
            // hold-to-talk area, so its buttons are plain buttons.
            if (uiState.camera != CameraShare.OFF) {
                RideCameraCard(uiState, onCameraAnswer, onStopCamera, onCameraPreview)
            }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()

            // Everything below the top bar is one tap-and-hold target for voice input, and,
            // from the ready screen, a double tap opens explore. The first tap of a double tap
            // is still an ordinary press (holding must open the mic at once, never after a
            // wait); the view model drops that press when the second tap lands.
            .pointerInput(Unit) {
                var quickTapUpAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (quickTapUpAt != 0L && down.uptimeMillis - quickTapUpAt < viewConfiguration.doubleTapTimeoutMillis) {
                        quickTapUpAt = 0L
                        waitForUpOrCancellation()
                        currentDoubleTap()
                        return@awaitEachGesture
                    }
                    val fromIdle = idle
                    currentHoldStart()
                    val up = waitForUpOrCancellation()
                    currentHoldEnd()
                    quickTapUpAt = if (fromIdle && up != null && up.uptimeMillis - down.uptimeMillis < QUICK_TAP_MS) up.uptimeMillis else 0L
                }
            }

            // Merged accessibility semantics for screen readers
            .semantics(mergeDescendants = true) {
                contentDescription = announcement
                customActions = buildCustomActions(
                    uiState, onCancel, onClarifyChoice, onNearMissAnswer, onSos,
                    onPay = onPay,
                    onPaymentMethod = onPaymentMethod,
                    onDeclinePayment = onDeclinePayment,
                    onPostRideAction = onPostRideAction,
                    onCameraAnswer = onCameraAnswer,
                    onStopCamera = onStopCamera,
                    onCodeConfirmed = onCodeConfirmed,
                    onCallDriver = onCallDriver,
                    onHelp = onHelp,
                    onOpenExplore = exploreActions?.let { a -> { a.open() } },
                    onOpenMindMap = mindMap?.let { { onMindMapOpen(true) } }
                )
            }
        ) {

            // Main scrollable content column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = CabEyeSpacing.screenPadding, vertical = CabEyeSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (val ride = uiState.ride) {
                    is RiderState.Idle -> IdleContent()
                    is RiderState.Listening -> ListeningContent(ride, uiState.micOpen, onCancel)
                    is RiderState.Resolving -> ResolvingContent(ride)
                    is RiderState.Clarify -> ClarifyContent(ride, onNearMissAnswer, onClarifyChoice)
                    is RiderState.Confirming -> ConfirmingContent(ride, uiState, onCancel)
                    is RiderState.Finding -> FindingContent(ride, onCancel)
                    is RiderState.Assigned -> AssignedContent(ride, onCancel)
                    is RiderState.Approaching -> ApproachingContent(ride, onCancel)
                    is RiderState.Arrived -> ArrivedContent(ride, uiState.micOpen, onCodeConfirmed, onCallDriver, onSos)
                    is RiderState.InTrip -> InTripContent(ride, onCodeConfirmed, onPostRideAction)
                    is RiderState.Walking -> WalkingContent(ride, onPostRideAction)
                    is RiderState.Planning -> PlanningContent(ride, onCancel, onPostRideAction)
                    is RiderState.Done -> DoneContent(ride, uiState.payment, onPay, onPaymentMethod, onDeclinePayment, onPostRideAction)
                    is RiderState.Feedback -> FeedbackContent(ride, onPostRideAction)
                    is RiderState.NextJourney -> NextJourneyContent(ride, onPostRideAction)
                }
            }

            if (uiState.holdToAnswer && !uiState.micOpen) {
                Text(
                    text = "Hold the screen or a volume button to answer",
                    style = CabEyeType.secondaryInfo.copy(fontWeight = FontWeight.Bold),
                    color = palette.listening,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CabEyeSpacing.screenPadding)
                        .background(palette.surface, CabEyeShapes.small)
                        .border(1.5.dp, palette.listening, CabEyeShapes.small)
                        .padding(12.dp)
                )
            } else if (uiState.micOpen) {
                Text(
                    text = "Listening while you hold. Let go when you've finished.",
                    style = CabEyeType.secondaryInfo,
                    color = palette.listening,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CabEyeSpacing.screenPadding)
                )
            }

            // Bottom bar: Help (on the ready screen) and SOS, side by side, never overlapping.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CabEyeSpacing.screenPadding, vertical = CabEyeSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.ride is RiderState.Idle) {
                    SecondaryAction(
                        label = "HELP",
                        onClick = onHelp,
                        accentColor = palette.muted,
                        modifier = Modifier.weight(1f)
                    )
                }
                SosButton(onSos = onSos)
            }
        }
        }


        // Walking routes and the mind map, as buttons. On top of everything, so a tap here can
        // never reach the hold-to-talk area underneath.
        if (mindMapOpen && mindMap != null) {
            MindMapScreen(
                catalog = mindMap.catalog(),
                answer = mindMap.answer(),
                onLoad = mindMap::load,
                onClose = { onMindMapOpen(false) },
                onBook = { if (mindMap.book(it)) onMindMapOpen(false) },
                onExplain = mindMap::explain,
                onForget = mindMap::forget,
                onGuide = { if (mindMap.guide(it)) onMindMapOpen(false) },
                onReadWalk = mindMap::readWalk,
                onForgetWalk = mindMap::forgetWalk,
                onBookRoute = { if (mindMap.bookRoute(it)) onMindMapOpen(false) },
                onExplore = {
                    onMindMapOpen(false)
                    exploreActions?.open()
                },
                onQuick = { command ->
                    onMindMapOpen(false)
                    onQuickCommand(command)
                },
                onSos = onSos
            )
        }

        if (explore.open && exploreActions != null) {
            ExploreScreen(
                ui = explore,
                onPreview = onCameraPreview,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onDescribe = exploreActions::describe,
                onRead = exploreActions::read,
                onKeepTelling = exploreActions::keepTelling,
                onFind = exploreActions::find,
                onStop = exploreActions::stop,
                onName = exploreActions::name,
                onAskName = exploreActions::askName,
                onFinish = exploreActions::finish,
                micOpen = uiState.micOpen,
                onSos = onSos
            )
        }

        // Emergency Overlay
        if (uiState.sosActive) {
            EmergencyOverlay(
                onDismiss = onDismissSos,
                modifier = Modifier.fillMaxSize(),
                status = uiState.sosStatus
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
//  One composable per ride state. Kept out of RiderSurface on purpose: as a single function
//  it compiled to bytecode that ART's verifier rejected (VerifyError), crashing at launch.
// ---------------------------------------------------------------------------------------

@Composable
private fun IdleContent() {
    val palette = LocalPalette.current
    VoiceOrb(
        state = VoiceOrbState.READY,
        customAccent = palette.listening
    )
    Spacer(Modifier.height(CabEyeSpacing.lg))
    StateHeadline(
        text = "Hold anywhere\nand speak",
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SupportingText(
        text = "“Take me to ${firstExample()}”",
        color = palette.listening
    )
}

@Composable
private fun ListeningContent(ride: RiderState.Listening, micOpen: Boolean, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    VoiceOrb(
        state = if (micOpen) VoiceOrbState.LISTENING else VoiceOrbState.READY,
        inputLevel = ride.inputLevel,
        customAccent = palette.listening
    )
    Spacer(Modifier.height(CabEyeSpacing.lg))
    StateHeadline(
        text = when {
            !micOpen -> "Hold to\nanswer"
            ride.isFollowUp -> "Listening for\nyour answer"
            else -> "Listening..."
        },
        color = palette.listening
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SupportingText(
        text = if (ride.partialTranscript.isNotBlank())
            "“${ride.partialTranscript}”"
        else
            "Say where you want to go. Take your time; pauses are fine.",
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.xl))
    SecondaryAction(
        label = "CANCEL",
        onClick = onCancel,
        accentColor = palette.danger
    )
}

@Composable
private fun ResolvingContent(ride: RiderState.Resolving) {
    val palette = LocalPalette.current
    VoiceOrb(
        state = VoiceOrbState.PROCESSING,
        customAccent = palette.processing
    )
    Spacer(Modifier.height(CabEyeSpacing.lg))
    StateHeadline(
        text = "Understanding\nyour request",
        color = palette.processing
    )
    if (ride.transcript.isNotBlank()) {
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(
            text = "“${ride.transcript}”",
            color = palette.muted
        )
    }
}

@Composable
private fun ClarifyContent(ride: RiderState.Clarify, onNearMissAnswer: (Boolean) -> Unit, onClarifyChoice: (PlaceOption) -> Unit) {
    val palette = LocalPalette.current
    if (ride.isNearMiss) {
        StatusIndicator(
            label = "CONFIRM DESTINATION",
            icon = "?",
            accent = palette.clarify
        )
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(
            text = "Did you mean\n${ride.optionA.name}?",
            color = palette.clarify
        )
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(
            text = "Say yes or no",
            color = palette.muted
        )
        Spacer(Modifier.height(CabEyeSpacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrimaryAction(
                label = "YES",
                onClick = { onNearMissAnswer(true) },
                accentColor = palette.confirm,
                modifier = Modifier.weight(1f)
            )
            SecondaryAction(
                label = "NO",
                onClick = { onNearMissAnswer(false) },
                accentColor = palette.danger,
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        StatusIndicator(
            label = "CHOOSE DESTINATION",
            icon = "?",
            accent = palette.clarify
        )
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(
            text = "Which place\ndid you mean?",
            color = palette.clarify
        )
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(
            text = "Say the name you want",
            color = palette.muted
        )
        Spacer(Modifier.height(CabEyeSpacing.lg))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PrimaryAction(
                label = ride.optionA.name,
                onClick = { onClarifyChoice(ride.optionA) },
                accentColor = palette.clarify,
                containerColor = palette.surface
            )
            Text(
                text = "or",
                style = CabEyeType.secondaryInfo.copy(fontWeight = FontWeight.Bold),
                color = palette.muted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            PrimaryAction(
                label = ride.optionB.name,
                onClick = { onClarifyChoice(ride.optionB) },
                accentColor = palette.clarify,
                containerColor = palette.surface
            )
        }
    }
}

@Composable
private fun ConfirmingContent(ride: RiderState.Confirming, uiState: RiderUiState, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    val secondsLeft = (ride.cancelWindowMillisRemaining / 1000.0).roundToInt()
    StatusIndicator(
        label = "BOOKING ${ride.rideType.spokenName.uppercase()}",
        icon = "✓",
        accent = palette.confirm
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    StateHeadline(
        text = "CONFIRM YOUR RIDE",
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    DestinationCard(
        destinationName = ride.destination,
        address = uiState.selectedDestination?.formattedAddress,
        rideType = ride.rideType,
        place = uiState.selectedDestination
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    SupportingText(
        text = "Say “cancel” to abort (${secondsLeft}s remaining)",
        color = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    SecondaryAction(
        label = "CANCEL BOOKING",
        onClick = onCancel,
        accentColor = palette.danger
    )
}

@Composable
private fun FindingContent(ride: RiderState.Finding, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    VoiceOrb(
        state = VoiceOrbState.READY,
        customGlyph = "🚕",
        customLabel = "SEARCHING",
        customAccent = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.lg))
    StateHeadline(
        text = "FINDING DRIVER",
        color = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    val seconds = (ride.elapsedMillis / 1000).toInt()
    SupportingText(
        text = "Searching for %d:%02d".format(seconds / 60, seconds % 60),
        color = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SupportingText(
        text = if (seconds < 30) "Your request has gone to drivers who are online nearby."
        else "No driver has accepted yet. Drivers see your request in the Cab Eye driver app; " +
            "you'll hear the moment one accepts.",
        color = palette.muted
    )
    Spacer(Modifier.height(CabEyeSpacing.xl))
    SecondaryAction(
        label = "CANCEL BOOKING",
        onClick = onCancel,
        accentColor = palette.danger
    )
}

@Composable
private fun AssignedContent(ride: RiderState.Assigned, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    StatusIndicator(
        label = "DRIVER ASSIGNED",
        icon = "✓",
        accent = palette.confirm
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    StateHeadline(
        text = "${ride.driver.name}\nis coming",
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    DriverCard(
        driver = ride.driver,
        etaMinutes = ride.etaMinutes,
        statusText = "Heading to pickup"
    )
    Spacer(Modifier.height(CabEyeSpacing.lg))
    SecondaryAction(
        label = "CANCEL RIDE",
        onClick = onCancel,
        accentColor = palette.danger
    )
}

@Composable
private fun ApproachingContent(ride: RiderState.Approaching, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    StatusIndicator(
        label = "DRIVER APPROACHING",
        icon = "→",
        accent = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    ProximityBeaconVisualization(
        distanceMeters = ride.distanceMeters,
        bearingDegrees = ride.bearingDegrees,
        accent = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    StateHeadline(
        // Null until the driver's first position arrives: "on the way",
        // never a placeholder number.
        text = ride.distanceMeters?.let { "${com.cabeye.rider.ui.components.formatDistance(it)}\naway" }
            ?: "${ride.driver.name}\nis on the way",
        color = palette.clarify
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    DriverCard(
        driver = ride.driver,
        distanceMeters = ride.distanceMeters,
        bearingDegrees = ride.bearingDegrees
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    SecondaryAction(
        label = "CANCEL RIDE",
        onClick = onCancel,
        accentColor = palette.danger
    )
}

@Composable
private fun ArrivedContent(ride: RiderState.Arrived, micOpen: Boolean, onCodeConfirmed: () -> Unit, onCallDriver: () -> Unit, onSos: () -> Unit) {
    val palette = LocalPalette.current
    when {
        ride.codeVerified -> {
            VoiceOrb(
                state = VoiceOrbState.SUCCESS,
                customGlyph = "✓",
                customLabel = "CODE VERIFIED",
                customAccent = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.lg))
            StateHeadline(
                text = "CODE VERIFIED",
                color = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SupportingText(
                text = "This is your driver. You may enter the vehicle.",
                color = palette.onBackground
            )
            Spacer(Modifier.height(CabEyeSpacing.md))
            DriverCard(
                driver = ride.driver,
                statusText = "Verified & Ready"
            )
        }
        ride.verificationFailed -> {
            VoiceOrb(
                state = VoiceOrbState.ERROR,
                customGlyph = "!",
                customLabel = "CODE INCORRECT",
                customAccent = palette.danger
            )
            Spacer(Modifier.height(CabEyeSpacing.lg))
            StateHeadline(
                text = "CODE INCORRECT",
                color = palette.danger
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SupportingText(
                text = "Please do not enter the vehicle.",
                color = palette.danger
            )
            Spacer(Modifier.height(CabEyeSpacing.lg))
            PrimaryAction(
                label = "REPEAT CODE",
                onClick = onCodeConfirmed,
                accentColor = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SecondaryAction(
                label = "NOT MY DRIVER (SOS)",
                onClick = onSos,
                accentColor = palette.danger
            )
        }
        micOpen -> {
            VoiceOrb(
                state = VoiceOrbState.LISTENING,
                customGlyph = "◉",
                customLabel = "LISTENING...",
                customAccent = palette.listening
            )
            Spacer(Modifier.height(CabEyeSpacing.lg))
            StateHeadline(
                text = "VERIFY CODE",
                color = palette.listening
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SupportingText(
                text = "Ask the driver to say the three-digit code.",
                color = palette.onBackground
            )
            Spacer(Modifier.height(CabEyeSpacing.md))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(palette.listening, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(CabEyeSpacing.lg))
            DriverCard(
                driver = ride.driver,
                statusText = "Waiting at pickup"
            )
        }
        else -> {
            StatusIndicator(
                label = "DRIVER ARRIVED",
                icon = "🚕",
                accent = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.md))
            StateHeadline(
                text = "YOUR DRIVER\nHAS ARRIVED",
                color = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SupportingText(
                text = if (ride.headphonesConnected)
                    "${ride.driver.name} is waiting nearby. (Expected: ${ride.expectedCode})"
                else
                    "${ride.driver.name} is waiting nearby. Ask for the three-digit boarding code.",
                color = palette.onBackground
            )
            Spacer(Modifier.height(CabEyeSpacing.md))
            DriverCard(
                driver = ride.driver,
                statusText = "Waiting at pickup"
            )
            Spacer(Modifier.height(CabEyeSpacing.lg))
            PrimaryAction(
                label = "VERIFY BOARDING CODE",
                onClick = onCodeConfirmed,
                accentColor = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SecondaryAction(
                label = "CALL DRIVER",
                onClick = onCallDriver,
                accentColor = palette.clarify
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SecondaryAction(
                label = "NOT MY DRIVER (SOS)",
                onClick = onSos,
                accentColor = palette.danger
            )
        }
    }
}

@Composable
private fun InTripContent(ride: RiderState.InTrip, onCodeConfirmed: () -> Unit, onPostRideAction: (String) -> Unit) {
    val palette = LocalPalette.current
    StatusIndicator(
        label = "ON TRIP",
        icon = "↗",
        accent = palette.listening
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    StateHeadline(
        text = "YOU'RE ON YOUR WAY",
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    DestinationCard(
        destinationName = ride.destination,
        showMapPreview = false
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    SupportingText(
        text = if (ride.etaMinutes > 0) "About ${ride.etaMinutes} minutes remaining to destination."
            else "On the way to ${ride.destination}.",
        color = palette.listening
    )
    if (ride.stops.isNotEmpty()) {
        val current = ride.currentStop
        Spacer(Modifier.height(CabEyeSpacing.md))
        StopList(ride.stops, ride.destination)
        if (current != null && current.status == "WAITING" && !current.riderBack) {
            Spacer(Modifier.height(CabEyeSpacing.md))
            PrimaryAction(
                label = "I'M BACK — CHECK CODE",
                onClick = { onPostRideAction("im-back") }
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SecondaryAction(
                label = "CODE IS RIGHT",
                onClick = onCodeConfirmed
            )
        }
    }
    Spacer(Modifier.height(CabEyeSpacing.md))
    DriverCard(driver = ride.driver)
}

@Composable
private fun WalkingContent(ride: RiderState.Walking, onPostRideAction: (String) -> Unit) {
    val palette = LocalPalette.current
    StatusIndicator(
        label = if (ride.recording) "RECORDING WALK" else "GUIDING",
        icon = if (ride.recording) "●" else "➜",
        accent = if (ride.recording) palette.danger else palette.listening
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    StateHeadline(text = ride.prompt, color = palette.onBackground)
    Spacer(Modifier.height(CabEyeSpacing.md))
    LineCard(ride.lines)
    Spacer(Modifier.height(CabEyeSpacing.xl))
    if (ride.recording) {
        PrimaryAction(label = "MARK A LANDMARK", onClick = { onPostRideAction("walk-mark") })
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "TURNING LEFT", onClick = { onPostRideAction("walk-left") })
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "TURNING RIGHT", onClick = { onPostRideAction("walk-right") })
        Spacer(Modifier.height(CabEyeSpacing.sm))
        PrimaryAction(label = "I'M THERE", onClick = { onPostRideAction("walk-done") })
    } else {
        PrimaryAction(label = "NEXT", onClick = { onPostRideAction("walk-next") })
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "REPEAT", onClick = { onPostRideAction("walk-repeat") })
        Spacer(Modifier.height(CabEyeSpacing.sm))
        PrimaryAction(label = "I'M THERE", onClick = { onPostRideAction("walk-done") })
    }
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SecondaryAction(
        label = if (ride.recording) "DISCARD" else "STOP GUIDING",
        onClick = { onPostRideAction("walk-stop") },
        accentColor = palette.danger
    )
}

@Composable
private fun PlanningContent(ride: RiderState.Planning, onCancel: () -> Unit, onPostRideAction: (String) -> Unit) {
    val palette = LocalPalette.current
    StatusIndicator(
        label = "PLANNING STOPS",
        icon = "⋯",
        accent = palette.processing
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    StateHeadline(
        text = ride.prompt,
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.md))
    LineCard(ride.lines)
    Spacer(Modifier.height(CabEyeSpacing.xl))
    PrimaryAction(
        label = "BOOK",
        onClick = { onPostRideAction("plan-yes") }
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SecondaryAction(
        label = "CHANGE",
        onClick = { onPostRideAction("plan-no") }
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SecondaryAction(
        label = "CANCEL",
        onClick = onCancel,
        accentColor = palette.danger
    )
}

@Composable
private fun DoneContent(ride: RiderState.Done, payment: PaymentUi?, onPay: () -> Unit, onPaymentMethod: (String) -> Unit, onDeclinePayment: () -> Unit, onPostRideAction: (String) -> Unit) {
    val palette = LocalPalette.current
    val paid = payment?.phase == PaymentPhase.PAID

    VoiceOrb(
        state = VoiceOrbState.SUCCESS,
        customGlyph = "✓",
        customLabel = if (paid) "PAID" else "COMPLETED",
        customAccent = palette.confirm
    )
    Spacer(Modifier.height(CabEyeSpacing.lg))
    StateHeadline(
        text = "TRIP COMPLETED",
        color = palette.confirm
    )
    Spacer(Modifier.height(CabEyeSpacing.sm))
    SupportingText(
        text = "You have arrived at ${ride.destination}",
        color = palette.onBackground
    )
    Spacer(Modifier.height(CabEyeSpacing.md))

    // Fare & Duration Card
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CabEyeShapes.card)
            .background(palette.surface)
            .border(2.dp, palette.outline, CabEyeShapes.card)
            .padding(CabEyeSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "FARE", style = CabEyeType.badgeText, color = palette.muted)
                Text(
                    text = "₹${ride.fareRupees}",
                    style = CabEyeType.stateTitleLarge.copy(fontSize = 32.sp),
                    color = palette.confirm
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(50.dp)
                    .background(palette.outline)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "DURATION", style = CabEyeType.badgeText, color = palette.muted)
                Text(
                    text = "${ride.durationMinutes} min",
                    style = CabEyeType.stateTitleLarge.copy(fontSize = 32.sp),
                    color = palette.onBackground
                )
            }
        }
    }

    if (payment != null) {
        Spacer(Modifier.height(CabEyeSpacing.md))
        PaymentPanel(payment, onPaymentMethod)
    }

    Spacer(Modifier.height(CabEyeSpacing.lg))

    // Payment & Next steps actions
    when (payment?.phase) {
        null, PaymentPhase.ERROR -> PrimaryAction(
            label = "PAY ₹${ride.fareRupees}",
            onClick = onPay,
            accentColor = palette.confirm
        )
        PaymentPhase.AWAITING -> {
            PrimaryAction(
                label = "PAY ₹${payment.amountRupees}",
                onClick = onPay,
                accentColor = palette.confirm
            )
            Spacer(Modifier.height(CabEyeSpacing.sm))
            SecondaryAction(
                label = "DECLINE",
                onClick = onDeclinePayment,
                accentColor = palette.danger
            )
        }
        PaymentPhase.STARTING, PaymentPhase.PROCESSING -> {
            SupportingText(text = "Processing payment with gateway...", color = palette.muted)
        }
        PaymentPhase.FAILED -> PrimaryAction(
            label = "TRY PAYMENT AGAIN",
            onClick = onPay,
            accentColor = palette.confirm
        )
        PaymentPhase.PAID -> PrimaryAction(
            label = "HEAR RECEIPT",
            onClick = onPay,
            accentColor = palette.onBackground
        )
    }

    Spacer(Modifier.height(CabEyeSpacing.md))
    SecondaryAction(
        label = "BOOK ANOTHER RIDE",
        onClick = { onPostRideAction("now") },
        accentColor = palette.onBackground
    )
}

@Composable
private fun FeedbackContent(ride: RiderState.Feedback, onPostRideAction: (String) -> Unit) {
    val palette = LocalPalette.current
    when (ride.step) {
    FeedbackStep.RATING -> {
        StatusIndicator(label = "RIDE FEEDBACK", icon = "★", accent = palette.clarify)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "How was\nyour ride?", color = palette.onBackground)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = "Say 1 to 5, “report a problem”, or “skip”", color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        PrimaryAction(label = "SKIP", onClick = { onPostRideAction("skip") }, accentColor = palette.onBackground)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "REPORT A PROBLEM", onClick = { onPostRideAction("report") }, accentColor = palette.danger)
    }
    FeedbackStep.RATING_CHECK -> {
        StatusIndicator(label = "CHECK RATING", icon = "?", accent = palette.clarify)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "${ride.rating ?: "?"} out of 5?", color = palette.clarify)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = "Say yes, no, or the correct number", color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryAction(label = "YES", onClick = { onPostRideAction("rating-yes") }, accentColor = palette.confirm, modifier = Modifier.weight(1f))
            SecondaryAction(label = "NO", onClick = { onPostRideAction("rating-no") }, accentColor = palette.onBackground, modifier = Modifier.weight(1f))
        }
    }
    FeedbackStep.REPORT -> {
        StatusIndicator(label = "REPORT PROBLEM", icon = "!", accent = palette.danger)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "What went wrong?", color = palette.danger)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = ride.rating?.let { "Rating $it of 5 · say it or “skip”" } ?: "Say what went wrong, or “skip”", color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        SecondaryAction(label = "SKIP", onClick = { onPostRideAction("skip") }, accentColor = palette.onBackground)
    }
    FeedbackStep.CONFIRM -> {
        StatusIndicator(label = "SEND REPORT", icon = "?", accent = palette.clarify)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "“${ride.report}”", color = palette.onBackground)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = "Category: ${ride.category}", color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        PrimaryAction(label = "SEND REPORT", onClick = { onPostRideAction("send") }, accentColor = palette.confirm)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "SKIP", onClick = { onPostRideAction("skip") }, accentColor = palette.onBackground)
    }
    FeedbackStep.SENT -> {
        VoiceOrb(state = VoiceOrbState.SUCCESS, customLabel = "THANK YOU", customAccent = palette.confirm)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        StateHeadline(text = "Thank You", color = palette.confirm)
    }
}
}

@Composable
private fun NextJourneyContent(ride: RiderState.NextJourney, onPostRideAction: (String) -> Unit) {
    val palette = LocalPalette.current
    when (ride.step) {
    NextStep.CHOOSE -> {
        StatusIndicator(label = "NEXT JOURNEY", icon = "↻", accent = palette.onBackground)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "Another ride?", color = palette.onBackground)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = "“book now” · “schedule for later” · “done”", color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        PrimaryAction(label = "BOOK ANOTHER RIDE", onClick = { onPostRideAction("now") }, accentColor = palette.confirm)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "I'M DONE", onClick = { onPostRideAction("done") }, accentColor = palette.onBackground)
    }
    NextStep.WHEN -> {
        StatusIndicator(label = "SCHEDULE RIDE", icon = "◷", accent = palette.listening)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "When?", color = palette.listening)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = "“tomorrow at 8:30” · “in two hours”", color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        SecondaryAction(label = "CANCEL", onClick = { onPostRideAction("done") }, accentColor = palette.onBackground)
    }
    NextStep.WHERE -> {
        StatusIndicator(label = "SCHEDULE RIDE", icon = "◷", accent = palette.listening)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = "Where to?", color = palette.listening)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = ride.scheduledAtText, color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        SecondaryAction(label = "CANCEL", onClick = { onPostRideAction("done") }, accentColor = palette.onBackground)
    }
    NextStep.CONFIRM -> {
        StatusIndicator(label = "CONFIRM SCHEDULE", icon = "?", accent = palette.clarify)
        Spacer(Modifier.height(CabEyeSpacing.md))
        StateHeadline(text = ride.scheduledTo, color = palette.clarify)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = ride.scheduledAtText, color = palette.muted)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        PrimaryAction(label = "SCHEDULE IT", onClick = { onPostRideAction("confirm") }, accentColor = palette.confirm)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SecondaryAction(label = "CANCEL", onClick = { onPostRideAction("done") }, accentColor = palette.onBackground)
    }
    NextStep.SCHEDULED -> {
        VoiceOrb(state = VoiceOrbState.SUCCESS, customLabel = "SCHEDULED", customAccent = palette.confirm)
        Spacer(Modifier.height(CabEyeSpacing.lg))
        StateHeadline(text = "Ride Scheduled", color = palette.confirm)
        Spacer(Modifier.height(CabEyeSpacing.sm))
        SupportingText(text = "${ride.scheduledTo} · ${ride.scheduledAtText}", color = palette.muted)
    }
}
}

/**
 * Visual proximity radar animation for Driver Approaching state.
 *
 * Renders expanding sound/proximity rings and vehicle heading,
 * visually reinforcing the audio beacon without requiring a complex map.
 */
@Composable
private fun ProximityBeaconVisualization(
    distanceMeters: Int?,
    bearingDegrees: Float?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "proximityTransition")

    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "proximityRingScale"
    )

    val pulseAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "proximityRingAlpha"
    )

    Box(
        modifier = modifier
            .size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!reducedMotion) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale)
                    .alpha(pulseAlpha)
                    .border(3.dp, accent, CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(110.dp)
                .background(accent.copy(alpha = 0.15f), CircleShape)
                .border(3.dp, accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🚕",
                fontSize = 38.sp
            )
        }
    }
}

/**
 * Payment controls on Done screen.
 */
@Composable
private fun PaymentPanel(payment: PaymentUi, onPaymentMethod: (String) -> Unit) {
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CabEyeShapes.card)
            .background(palette.surface)
            .border(2.dp, palette.outline, CabEyeShapes.card)
            .padding(CabEyeSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (payment.phase) {
                PaymentPhase.PAID -> "✓ PAID ₹${payment.amountRupees}"
                PaymentPhase.FAILED -> "✕ NOT PAID"
                PaymentPhase.ERROR -> "PAYMENT UNAVAILABLE"
                PaymentPhase.STARTING -> "PREPARING…"
                PaymentPhase.PROCESSING -> "PROCESSING…"
                PaymentPhase.AWAITING -> "₹${payment.amountRupees}"
            },
            style = CabEyeType.stateTitleLarge.copy(fontSize = 28.sp),
            color = when (payment.phase) {
                PaymentPhase.PAID -> palette.confirm
                PaymentPhase.FAILED, PaymentPhase.ERROR -> palette.danger
                else -> palette.onBackground
            },
            textAlign = TextAlign.Center
        )

        if (payment.phase == PaymentPhase.AWAITING) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Pay by",
                style = CabEyeType.secondaryInfo,
                color = palette.muted
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                PAYMENT_METHODS.forEachIndexed { index, (code, label) ->
                    if (index > 0) Spacer(Modifier.width(8.dp))
                    MethodChip(
                        label = label,
                        selected = payment.method == code,
                        modifier = Modifier.weight(1f),
                        onClick = { onPaymentMethod(code) }
                    )
                }
            }
        }

        if (payment.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = payment.message,
                style = CabEyeType.secondaryInfo,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }

        if (payment.phase == PaymentPhase.PAID && payment.bankRef.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ref ${payment.bankRef.chunked(4).joinToString(" ")}" +
                    if (payment.method.isNotBlank()) " · ${payment.method}" else "",
                style = CabEyeType.secondaryInfo.copy(fontWeight = FontWeight.SemiBold),
                color = palette.onBackground,
                textAlign = TextAlign.Center
            )
        }

        if (payment.phase == PaymentPhase.AWAITING || payment.phase == PaymentPhase.PROCESSING) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "TEST MODE · no real money",
                style = CabEyeType.secondaryInfo.copy(fontSize = 12.sp),
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MethodChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = if (selected) palette.confirm else palette.muted
    Button(
        onClick = onClick,
        shape = CabEyeShapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) palette.surfaceElevated else palette.surface,
            contentColor = accent
        ),
        modifier = modifier
            .defaultMinSize(minHeight = CabEyeSpacing.preferredTouchTarget)
            .border(if (selected) 2.5.dp else 1.dp, accent, CabEyeShapes.small)
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            style = CabEyeType.buttonText.copy(fontSize = 15.sp),
            textAlign = TextAlign.Center
        )
    }
}

private val PAYMENT_METHODS = listOf(
    "UPI" to "UPI",
    "CARD" to "Card",
    "NETBANKING" to "Net banking"
)

private fun firstExample(): String =
    Gazetteer.activeCity.examples.firstOrNull() ?: "the airport"

private fun hapticFor(uiState: RiderUiState): HapticPattern {
    if (uiState.sosActive) return HapticPattern.SOS

    return when (val ride = uiState.ride) {
        is RiderState.Idle -> HapticPattern.LISTENING_END
        is RiderState.Listening -> HapticPattern.LISTENING_START
        is RiderState.Resolving -> HapticPattern.LISTENING_END
        is RiderState.Clarify -> HapticPattern.CLARIFY
        is RiderState.Confirming -> HapticPattern.CONFIRMED
        is RiderState.Finding -> HapticPattern.LISTENING_END
        is RiderState.Assigned -> HapticPattern.DRIVER_ASSIGNED
        is RiderState.Approaching -> HapticPattern.LISTENING_END
        is RiderState.Arrived -> when {
            ride.codeVerified -> HapticPattern.BOARDING_VERIFIED
            ride.verificationFailed -> HapticPattern.WRONG_CODE
            else -> HapticPattern.ARRIVED
        }
        is RiderState.InTrip -> HapticPattern.CONFIRMED
        is RiderState.Done -> HapticPattern.TRIP_COMPLETED
        is RiderState.Feedback -> HapticPattern.CLARIFY
        is RiderState.NextJourney -> HapticPattern.CLARIFY
        is RiderState.Planning -> HapticPattern.CLARIFY
        is RiderState.Walking -> HapticPattern.LISTENING_END
    }
}


/** What the mind map screen can ask of the ride screen's view model. */
interface MindMapActions {
    fun catalog(): com.cabeye.rider.mindmap.MindMapCatalog.Catalog?
    fun answer(): String
    fun load()
    fun book(card: com.cabeye.rider.mindmap.MindMapCatalog.PlaceCard): Boolean
    fun explain(nodeId: String)
    fun forget(nodeId: String)
    fun guide(routeId: String): Boolean
    fun readWalk(routeId: String)
    fun forgetWalk(routeId: String)
    fun bookRoute(name: String): Boolean
}

/** What the explore screen can ask of the ride screen's view model. */
interface ExploreActions {
    fun open()
    fun describe()
    fun read()
    fun keepTelling()
    fun find(target: String)
    fun stop()
    fun name(name: String)
    fun askName()
    fun finish(save: Boolean)
}

/**
 * Ride module: the driver asks to see the rider's camera (Share / No), or the camera is shared
 * (live preview with what it sees drawn on it, and Stop). Full width, large, impossible to miss.
 */
@Composable
private fun RideCameraCard(
    uiState: RiderUiState,
    onAnswer: (Boolean) -> Unit,
    onStop: () -> Unit,
    onPreview: (androidx.camera.core.Preview.SurfaceProvider?) -> Unit
) {
    val palette = LocalPalette.current
    val who = uiState.cameraFrom.ifBlank { "Your driver" }
    val live = uiState.camera == CameraShare.LIVE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CabEyeSpacing.screenPadding, vertical = 8.dp)
            .background(palette.surface, CabEyeShapes.card)
            .border(3.dp, if (live) palette.danger else palette.clarify, CabEyeShapes.card)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!live) {
            Text("📷 $who asks to see your camera", style = CabEyeType.stateTitle.copy(fontSize = 22.sp), color = palette.clarify, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                "So they can find you and guide you to the car. Only they see it, nothing is recorded, " +
                    "and it turns off once you're in. I'll tell you what's around you too.",
                style = CabEyeType.secondaryInfo, color = palette.onBackground, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            PrimaryAction(label = "SHARE MY CAMERA", onClick = { onAnswer(true) }, accentColor = palette.confirm)
            Spacer(Modifier.height(10.dp))
            SecondaryAction(label = "DON'T SHARE", onClick = { onAnswer(false) }, accentColor = palette.onBackground)
        } else {
            Text("● CAMERA SHARED WITH ${who.uppercase()}", style = CabEyeType.badgeText, color = palette.danger)
            Spacer(Modifier.height(8.dp))
            val context = androidx.compose.ui.platform.LocalContext.current
            val previewView = androidx.compose.runtime.remember {
                androidx.camera.view.PreviewView(context).apply {
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
                    implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            androidx.compose.runtime.DisposableEffect(previewView) {
                onPreview(previewView.surfaceProvider)
                onDispose { onPreview(null) }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(CabEyeShapes.medium)
                    .background(Color.Black)
            ) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                com.cabeye.rider.ui.components.DetectionOverlay(
                    objects = uiState.cameraSeen, imageAspect = 3f / 4f, modifier = Modifier.fillMaxSize()
                )
            }
            if (uiState.cameraSeen.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Around you: " + uiState.cameraSeen.take(4).joinToString(", ") { "${it.label} (${it.clock})" },
                    style = CabEyeType.secondaryInfo, color = palette.listening, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            SecondaryAction(label = "STOP CAMERA", onClick = onStop, accentColor = palette.danger)
        }
    }
}

/** A press shorter than this can be the first half of a double tap; a longer one was a hold. */
private const val QUICK_TAP_MS = 300L

/** A multi-stop ride's stops, in order, with what each one is and where the ride is. */
@Composable
private fun StopList(stops: List<com.cabeye.rider.net.StopInfo>, destination: String) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CabEyeShapes.card)
            .background(palette.surface)
            .border(2.dp, palette.outline, CabEyeShapes.card)
            .padding(CabEyeSpacing.md)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        stops.forEach { s ->
            val (mark, accent) = when (s.status) {
                "DONE" -> "✓" to palette.confirm
                "SKIPPED" -> "–" to palette.muted
                "WAITING", "ARRIVED" -> "●" to palette.clarify
                else -> "○" to palette.muted
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(mark, style = CabEyeType.buttonText.copy(fontSize = 20.sp), color = accent, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${s.index}. ${s.name}",
                        style = CabEyeType.buttonText.copy(fontSize = 19.sp),
                        color = if (s.status == "DONE" || s.status == "SKIPPED") palette.muted else palette.onBackground
                    )
                    val detail = listOfNotNull(
                        if (s.isWait) "driver waits" else when (s.kind) { "DROP" -> "drop-off"; "PICKUP" -> "pick-up"; else -> null },
                        s.note.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, style = CabEyeType.secondaryInfo.copy(fontSize = 15.sp), color = palette.muted)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("⚑", style = CabEyeType.buttonText.copy(fontSize = 20.sp), color = palette.listening, modifier = Modifier.width(28.dp))
            Text("Then $destination", style = CabEyeType.buttonText.copy(fontSize = 19.sp), color = palette.onBackground)
        }
    }
}

/**
 * A short list (a route being planned, the parts of a walk), left-aligned in a card: centred,
 * wrapped lines were hard to follow, and "▶" marks where the rider is.
 */
@Composable
private fun LineCard(lines: List<String>) {
    if (lines.isEmpty()) return
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CabEyeShapes.card)
            .background(palette.surface)
            .border(2.dp, palette.outline, CabEyeShapes.card)
            .padding(CabEyeSpacing.md),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        lines.forEach { line ->
            val current = line.startsWith("▶")
            Text(
                text = line,
                style = CabEyeType.buttonText.copy(fontSize = 19.sp, lineHeight = 25.sp),
                color = if (current) palette.listening else palette.onBackground
            )
        }
    }
}
