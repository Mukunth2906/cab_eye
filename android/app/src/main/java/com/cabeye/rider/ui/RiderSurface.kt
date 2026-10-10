package com.cabeye.rider.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val announcement = announcementFor(uiState)

    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)

    // Trigger distinctive haptic on state change
    LaunchedEffect(uiState.ride::class, uiState.sosActive) {
        haptics.perform(hapticFor(uiState))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)

            // Massive whole-screen tap-and-hold target for voice input
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        currentHoldStart()
                        tryAwaitRelease()
                        currentHoldEnd()
                    }
                )
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
                    onHelp = onHelp
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar Header with title, active city, and 5-tap connection chip
            CabEyeHeader(
                cityName = uiState.activeCityName,
                connected = uiState.connected,
                onOpenSettings = onOpenSettings
            )

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

                    // -----------------------------------------------------------------
                    // 1. Ready State
                    // -----------------------------------------------------------------
                    is RiderState.Idle -> {
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

                    // -----------------------------------------------------------------
                    // 2. Listening State
                    // -----------------------------------------------------------------
                    is RiderState.Listening -> {
                        VoiceOrb(
                            state = VoiceOrbState.LISTENING,
                            inputLevel = ride.inputLevel,
                            customAccent = palette.listening
                        )
                        Spacer(Modifier.height(CabEyeSpacing.lg))
                        StateHeadline(
                            text = if (ride.isFollowUp) "Listening for\nyour answer" else "Listening...",
                            color = palette.listening
                        )
                        Spacer(Modifier.height(CabEyeSpacing.sm))
                        SupportingText(
                            text = if (ride.partialTranscript.isNotBlank())
                                "“${ride.partialTranscript}”"
                            else
                                "Speak your destination",
                            color = palette.onBackground
                        )
                        Spacer(Modifier.height(CabEyeSpacing.xl))
                        SecondaryAction(
                            label = "CANCEL",
                            onClick = onCancel,
                            accentColor = palette.danger
                        )
                    }

                    // -----------------------------------------------------------------
                    // 3. Processing State (Resolving)
                    // -----------------------------------------------------------------
                    is RiderState.Resolving -> {
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

                    // -----------------------------------------------------------------
                    // 4. Clarification State
                    // -----------------------------------------------------------------
                    is RiderState.Clarify -> {
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

                    // -----------------------------------------------------------------
                    // 5. Confirm Ride State (Confirming)
                    // -----------------------------------------------------------------
                    is RiderState.Confirming -> {
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

                    // -----------------------------------------------------------------
                    // 6. Finding Driver State (Finding)
                    // -----------------------------------------------------------------
                    is RiderState.Finding -> {
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
                        SupportingText(
                            text = "We're finding the nearest driver for you.",
                            color = palette.muted
                        )
                        Spacer(Modifier.height(CabEyeSpacing.xl))
                        SecondaryAction(
                            label = "CANCEL BOOKING",
                            onClick = onCancel,
                            accentColor = palette.danger
                        )
                    }

                    // -----------------------------------------------------------------
                    // 7. Driver Assigned State (Assigned)
                    // -----------------------------------------------------------------
                    is RiderState.Assigned -> {
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

                    // -----------------------------------------------------------------
                    // 8. Driver Approaching State (Approaching)
                    // -----------------------------------------------------------------
                    is RiderState.Approaching -> {
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
                            text = "${ride.distanceMeters} meters away",
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

                    // -----------------------------------------------------------------
                    // 9. Driver Arrived State & Boarding Verification (Arrived)
                    // -----------------------------------------------------------------
                    is RiderState.Arrived -> {
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
                            uiState.micOpen -> {
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
                                    text = "Ask the driver to say the 4-digit code.",
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
                                        "${ride.driver.name} is waiting nearby. Ask for the four-digit boarding code.",
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

                    // -----------------------------------------------------------------
                    // 10. In Trip State (InTrip)
                    // -----------------------------------------------------------------
                    is RiderState.InTrip -> {
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
                            text = "About ${ride.etaMinutes} minutes remaining to destination.",
                            color = palette.listening
                        )
                        if (ride.stops.isNotEmpty()) {
                            val current = ride.currentStop
                            Spacer(Modifier.height(CabEyeSpacing.md))
                            SupportingText(
                                text = ride.stops.joinToString("\n") { s ->
                                    val mark = when (s.status) {
                                        "DONE" -> "✓"
                                        "SKIPPED" -> "–"
                                        "WAITING", "ARRIVED" -> "●"
                                        else -> "○"
                                    }
                                    "$mark ${s.index}. ${s.name}" + if (s.isWait) " (driver waits)" else ""
                                } + "\nThen ${ride.destination}",
                                color = palette.onBackground
                            )
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

                    // -----------------------------------------------------------------
                    // Walking route: recording it, or being guided along it
                    // -----------------------------------------------------------------
                    is RiderState.Walking -> {
                        StatusIndicator(
                            label = if (ride.recording) "RECORDING WALK" else "GUIDING",
                            icon = if (ride.recording) "●" else "➜",
                            accent = if (ride.recording) palette.danger else palette.listening
                        )
                        Spacer(Modifier.height(CabEyeSpacing.md))
                        StateHeadline(text = ride.prompt, color = palette.onBackground)
                        Spacer(Modifier.height(CabEyeSpacing.md))
                        SupportingText(text = ride.lines.joinToString("\n"), color = palette.listening)
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

                    // -----------------------------------------------------------------
                    // Multi-stop planning: the route as read back, for a sighted helper
                    // -----------------------------------------------------------------
                    is RiderState.Planning -> {
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
                        SupportingText(
                            text = ride.lines.joinToString("\n"),
                            color = palette.listening
                        )
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

                    // -----------------------------------------------------------------
                    // 11. Trip Completed State (Done)
                    // -----------------------------------------------------------------
                    is RiderState.Done -> {
                        val payment = uiState.payment
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

                    // -----------------------------------------------------------------
                    // 12. Feedback State
                    // -----------------------------------------------------------------
                    is RiderState.Feedback -> when (ride.step) {
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

                    // -----------------------------------------------------------------
                    // 13. Next Journey State
                    // -----------------------------------------------------------------
                    is RiderState.NextJourney -> when (ride.step) {
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
            }

            // Bottom bar with Help & SOS button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CabEyeSpacing.screenPadding, vertical = CabEyeSpacing.md)
            ) {
                if (uiState.ride is RiderState.Idle) {
                    SecondaryAction(
                        label = "HELP",
                        onClick = onHelp,
                        accentColor = palette.muted,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .defaultMinSize(minWidth = 110.dp)
                    )
                }
                SosButton(
                    onSos = onSos,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Live camera assist in top corner
        CameraPanel(
            camera = uiState.camera,
            onAnswer = onCameraAnswer,
            onStop = onStopCamera,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // Emergency Overlay
        if (uiState.sosActive) {
            EmergencyOverlay(
                onDismiss = onDismissSos,
                modifier = Modifier.fillMaxSize()
            )
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
    distanceMeters: Int,
    bearingDegrees: Float,
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
 * Live camera corner panel for driver assist.
 */
@Composable
private fun CameraPanel(
    camera: CameraShare,
    onAnswer: (Boolean) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (camera == CameraShare.OFF) return
    val palette = LocalPalette.current
    val live = camera == CameraShare.LIVE

    Column(
        modifier = modifier
            .padding(12.dp)
            .width(230.dp)
            .background(palette.surface, CabEyeShapes.card)
            .border(2.dp, if (live) palette.danger else palette.clarify, CabEyeShapes.card)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (live) "● CAMERA ON" else "Driver asks to see camera",
            style = CabEyeType.badgeText,
            color = if (live) palette.danger else palette.clarify,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (live) "Shown to driver only." else "Say yes or no",
            style = CabEyeType.secondaryInfo.copy(fontSize = 13.sp),
            color = palette.muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        if (live) {
            SecondaryAction(label = "STOP CAMERA", onClick = onStop, accentColor = palette.danger)
        } else {
            PrimaryAction(label = "SHARE", onClick = { onAnswer(true) }, accentColor = palette.confirm)
            Spacer(Modifier.height(8.dp))
            SecondaryAction(label = "NO", onClick = { onAnswer(false) }, accentColor = palette.onBackground)
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
