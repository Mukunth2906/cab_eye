package com.cabeye.rider.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cabeye.rider.BuildConfig
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.RiderUiState
import com.cabeye.rider.places.Gazetteer
import com.cabeye.rider.ui.theme.FocusIndicatorWidth
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.LocalReducedMotion
import com.cabeye.rider.ui.theme.MinTouchTarget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.roundToInt

/**
 * **The** rider surface. One Composable, eleven states, no navigation.
 *
 * This is principle 1 made concrete: there is no `NavHost`, no back stack, and no
 * transition the rider can initiate. The surface changes because the *ride* changed. A
 * blind user cannot be somewhere unexpected in a hierarchy if there is no hierarchy.
 *
 * ## What the screen is for
 * Nothing here is the primary interface. Audio is. But "blind users can't see it, so it doesn't
 * matter" is the wrong conclusion to draw from that: most people with a vision impairment have
 * **some** usable sight, and they are exactly the people who will look at this screen. So it is
 * designed for low vision specifically — true black to avoid halation, 24sp floor on body text,
 * bold weights only, one target filling most of the screen — rather than for a sighted
 * designer's idea of an accessible theme.
 *
 * ## The per-state contract, enforced structurally
 * Every state renders through [StateScaffold], which allows exactly: one huge headline, at most
 * one supporting line, a listening indicator, and at most two buttons. That is a constraint the
 * brief states in prose, and prose constraints get violated one screen at a time. Making it a
 * function signature means a screen that wanted three buttons would not compile.
 *
 * @param uiState complete state to render
 * @param onHoldStart the rider pressed the surface; open the microphone
 * @param onHoldEnd the rider released; close the microphone and transcribe
 * @param onCancel cancel the in-flight booking during the 5 s window
 * @param onClarifyChoice a disambiguation option was chosen. Note this is the *fallback*
 *   path for a sighted helper — the rider answers by voice, per principle 2
 * @param onNearMissAnswer yes/no answer to "Did you mean Adyar?", same fallback status
 * @param onCodeConfirmed the sighted-helper fallback for the boarding code. The rider's own
 *   path is to let the app hear the driver say it — this button exists for the case where the
 *   street is too loud for the recogniser, not as the primary route
 * @param onSos raise the SOS overlay
 * @param onDismissSos lower it, returning to the state underneath
 * @param onOpenSettings raises the debug settings screen. Reached only by [SETTINGS_TAP_COUNT]
 *   taps on the small connection indicator — deliberately awkward, because a rider who cannot
 *   see the screen landing in settings by accident has no way to get back out
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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val announcement = announcementFor(uiState)
    val context = LocalContext.current

    // The gesture detector below is keyed on Unit so it is never restarted. These keep the
    // callbacks it captured up to date across recompositions without changing that key.
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)

    // The haptic fires on every ride-state change, not on every recomposition. Keying the
    // effect on the state's class means a Listening -> Listening update carrying a new
    // partial transcript does not buzz the phone thirty times while the rider is talking.
    LaunchedEffect(uiState.ride::class, uiState.sosActive) {
        haptics.perform(hapticFor(uiState))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)

            // ------------------------------------------------------------------
            //  THE MASSIVE TAP TARGET
            //  The whole screen is the button. There is nothing to aim at, which is
            //  the only design a rider who cannot see the screen can use reliably.
            //  Press and hold to talk; release to send.
            // ------------------------------------------------------------------
            // KEYED ON Unit, DELIBERATELY. An earlier version keyed this on the ride state,
            // which was a real bug found on device: pressing changes the state, the changed
            // key cancels and restarts this whole block mid-press, and the restarted
            // detector re-fires onPress and immediately resolves the release. The symptom
            // was the app appearing to start listening on its own and then cutting the
            // rider off after ~7 ms. The gesture detector must outlive state changes.
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        currentHoldStart()
                        // Suspends until the finger lifts (or the gesture is cancelled),
                        // which is what makes this hold-to-talk rather than tap-to-toggle.
                        tryAwaitRelease()
                        currentHoldEnd()
                    }
                )
            }

            // ------------------------------------------------------------------
            //  SEMANTICS: one merged target with a live region
            //
            //  mergeDescendants = true collapses every child into a single node, so
            //  TalkBack presents the surface as ONE thing to swipe to rather than a
            //  traversal list of labels. Traversing a list is exactly the navigation
            //  this product is built to remove.
            //
            //  liveRegion = Assertive makes TalkBack announce contentDescription
            //  whenever it changes, without the user moving focus — so a ride-driven
            //  change reaches the rider even though they never touched anything.
            // ------------------------------------------------------------------
            .semantics(mergeDescendants = true) {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Assertive

                // With descendants merged, child buttons stop being individually
                // focusable. These custom actions put those affordances back for a
                // TalkBack user, reachable from the single merged node.
                customActions = buildCustomActions(
                    uiState, onCancel, onClarifyChoice, onNearMissAnswer, onSos
                )
            }
    ) {
        // The `when` is exhaustive over the sealed interface — no `else` branch. Adding a
        // twelfth state becomes a compile error here rather than a blank screen at runtime.
        when (val ride = uiState.ride) {

            is RiderState.Idle -> StateScaffold(
                icon = "●",
                word = "Ready",
                headline = "Hold anywhere\nand speak",
                support = "“take me to ${firstExample()}”  ·  ${uiState.activeCityName}",
                accent = palette.onBackground,
                micOpen = uiState.micOpen
            )

            is RiderState.Listening -> StateScaffold(
                icon = "◉",
                word = "Listening",
                headline = if (ride.isFollowUp) "Listening\nfor your answer" else "Listening",
                support = ride.partialTranscript.takeIf { it.isNotBlank() },
                accent = palette.listening,
                micOpen = uiState.micOpen,
                inputLevel = ride.inputLevel
            )

            is RiderState.Resolving -> StateScaffold(
                icon = "◌",
                word = "Working",
                headline = "Working\nthat out",
                support = ride.transcript.takeIf { it.isNotBlank() },
                accent = palette.muted,
                micOpen = uiState.micOpen
            )

            is RiderState.Clarify -> ClarifyContent(
                state = ride,
                micOpen = uiState.micOpen,
                onChoice = onClarifyChoice,
                onNearMissAnswer = onNearMissAnswer
            )

            is RiderState.Confirming -> ConfirmingContent(ride, uiState.micOpen, uiState.selectedDestination, onCancel)

            is RiderState.Finding -> StateScaffold(
                icon = "◍",
                word = "Finding",
                headline = "Finding\na driver",
                support = "The pulse means it's still working",
                accent = palette.listening,
                micOpen = uiState.micOpen
            )

            is RiderState.Assigned -> StateScaffold(
                icon = "✓",
                word = "Assigned",
                headline = "${ride.driver.name}\nis coming",
                support = "${ride.etaMinutes} min  ·  ${ride.driver.vehicleModel}",
                accent = palette.confirm,
                micOpen = uiState.micOpen,
                primary = ButtonSpec("Cancel", palette.danger, onCancel)
            )

            // Wordless by design: the panned, rising earcon carries the distance far faster
            // than a sentence could. The screen still shows it, for the sighted helper.
            is RiderState.Approaching -> StateScaffold(
                icon = "→",
                word = "Approaching",
                headline = "${ride.distanceMeters} m\naway",
                support = "${ride.driver.name}  ·  ${ride.driver.vehiclePlate}",
                accent = palette.listening,
                micOpen = uiState.micOpen,
                primary = ButtonSpec("Cancel", palette.danger, onCancel)
            )

            // The boarding code is REVERSED on purpose: the driver's screen shows it, the
            // driver says it aloud, and this app verifies it. A blind rider standing on a
            // street cannot tell who is within earshot, so the app announcing a secret over a
            // loudspeaker would defeat the whole point of having one.
            is RiderState.Arrived -> StateScaffold(
                icon = "◆",
                word = "Arrived",
                headline = "Your driver\nis here",
                // The code IS shown here, and only here. This screen is for the sighted helper
                // and the low-vision rider who can read it — the rider who cannot never has it
                // spoken aloud on the loudspeaker, which is the part that matters. The app
                // announces it through the earpiece only, and only with headphones connected.
                support = if (ride.headphonesConnected)
                    "Listening for the driver to say the code"
                else
                    "Ask the driver to say the code — I'm listening",
                accent = palette.confirm,
                micOpen = uiState.micOpen,
                primary = ButtonSpec("Code is right", palette.confirm, onCodeConfirmed),
                secondary = ButtonSpec("Not my driver", palette.danger, onSos)
            )

            is RiderState.InTrip -> StateScaffold(
                icon = "▶",
                word = "On the way",
                headline = "On the way\nto ${ride.destination}",
                support = "${ride.etaMinutes} min  ·  ${ride.driver.name}",
                accent = palette.onBackground,
                micOpen = uiState.micOpen
            )

            is RiderState.Done -> StateScaffold(
                icon = "★",
                word = "Complete",
                headline = "You've\narrived",
                support = "${ride.destination}  ·  ₹${ride.fareRupees}  ·  ${ride.durationMinutes} min",
                accent = palette.confirm,
                micOpen = uiState.micOpen,
                // Payment is the one step this app deliberately does not own. Handing off to
                // the rider's own UPI app means the PIN is entered in the app they already
                // trust, with the accessibility setup they have already configured there —
                // rather than this app collecting a payment credential through a screen a
                // blind rider cannot verify.
                primary = ButtonSpec("PROCEED TO PAYMENT", palette.confirm) {
                    payWithUpi(
                        context = context,
                        amountRupees = ride.fareRupees,
                        note = "Cab Eye ride to ${ride.destination}"
                    )
                }
            )
        }

        // Connection state, and the hidden way into settings.
        //
        // The warning half is the point: a dropped socket must be visible AND audible, because
        // on its own it presents as silence — and silence is reserved for "working normally".
        // The audible half is the view model's job; this is the visible one, for the sighted
        // helper looking over the rider's shoulder.
        ConnectionChip(
            connected = uiState.connected,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        SosButton(onSos = onSos, modifier = Modifier.align(Alignment.BottomEnd))

        if (uiState.sosActive) {
            SosOverlay(onDismiss = onDismissSos)
        }
    }
}

// =====================================================================================
//  The scaffold every state renders through
// =====================================================================================

/** One button: its label, its accent, and what it does. */
private data class ButtonSpec(
    val label: String,
    val accent: Color,
    val onClick: () -> Unit
)

/**
 * The shape of every state.
 *
 * Taking these as parameters rather than trusting eleven hand-written screens is what makes the
 * brief's per-state rules structural instead of aspirational: there is no third button
 * parameter, so no state can grow a third button.
 *
 * Note that [icon] and [word] are **not decoration**. The brief requires that no meaning is
 * carried by colour alone, and these are how that requirement is met on screen — the accent
 * colour is the third signal, not the first. A rider with a colour vision deficiency, or one
 * using the high-contrast yellow theme where several accents are the same yellow, still gets
 * the state from the glyph and the word. The audio layer carries the fourth signal.
 *
 * @param icon a single glyph, sized large. Text rather than a vector so it scales with the
 *   system font setting for free and can never go missing from a drawable folder.
 * @param word one word naming the state, for the same reason
 * @param headline the one huge line. Line breaks are authored, not wrapped, so the break lands
 *   somewhere meaningful at every font size.
 * @param support at most one supporting line, or null
 * @param micOpen drives the pulsing indicator. Deliberately *not* derived from the ride state:
 *   the mic is also open during `confirming` and during the recovery ladder, and a helper
 *   watching the screen needs to know that.
 * @param inputLevel 0..1 mic level, which nudges the indicator so sound reaching the recogniser
 *   is visible and not merely asserted
 */
@Composable
private fun StateScaffold(
    icon: String,
    word: String,
    headline: String,
    support: String?,
    accent: Color,
    micOpen: Boolean,
    inputLevel: Float = 0f,
    primary: ButtonSpec? = null,
    secondary: ButtonSpec? = null,
    extraContent: (@Composable () -> Unit)? = null
) {
    val palette = LocalPalette.current

    // States that carry extra content below the headline (today: the destination map) have to
    // fit a genuinely long place name AND the map on one screen. "PSG College Of Technology"
    // wraps to three lines at displayLarge and pushes the map off the bottom — and the surface
    // cannot be scrolled without opening the microphone, so off the bottom means gone. Long
    // names are the normal case now that Google supplies the candidates, not the exception.
    val hasExtra = extraContent != null

    CenteredColumn(verticalPadding = if (hasExtra) 24.dp else 40.dp) {
        ListeningIndicator(micOpen = micOpen, accent = accent, inputLevel = inputLevel)

        Spacer(Modifier.height(20.dp))

        Text(
            text = "$icon  ${word.uppercase()}",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        // The primary target: one huge headline filling the bulk of the surface. The whole
        // screen is pressable, so this is what the rider is aiming at when they aim at all.
        //
        // A MINIMUM height derived from the real screen, not `fillMaxHeight(0.6f)`. Inside the
        // scroll container above, the height constraint is unbounded, and `fillMaxHeight`
        // silently does nothing against an unbounded constraint — the 60% floor would have
        // looked correct in the source and been absent on the device. A minimum also degrades
        // the right way at 200% font: the block grows past 60% and scrolls, rather than
        // capping and clipping the headline.
        // When a state carries extra content — today, the destination map — the 60% floor is
        // yielded down to a smaller one. Not cosmetic: the whole screen is a hold-to-talk
        // target, so anything pushed below the fold is genuinely unreachable. A swipe to scroll
        // to it registers as a press and opens the microphone instead, which is how a sighted
        // user checking the map ends up re-triggering the recogniser. Content that cannot be
        // scrolled to must therefore fit without scrolling.
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val targetFraction =
            if (hasExtra) PRIMARY_TARGET_FRACTION_WITH_EXTRA else PRIMARY_TARGET_FRACTION
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = (screenHeightDp * targetFraction).dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = headline,
                // 48sp rather than 60sp when a map is also on screen. Still twice the 24sp
                // body floor and still the largest thing on the surface, but a three-line
                // name now costs 168dp instead of 198dp, which is the difference between
                // the map being visible and being unreachable.
                style = if (hasExtra) {
                    MaterialTheme.typography.displayMedium
                } else {
                    MaterialTheme.typography.displayLarge
                },
                color = accent,
                textAlign = TextAlign.Center
            )
        }

        if (support != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = support,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }

        extraContent?.invoke()

        if (primary != null || secondary != null) {
            Spacer(Modifier.height(if (hasExtra) 16.dp else 28.dp))
            ActionRow(primary, secondary)
        }
    }
}

/** At most two buttons, side by side when there are two. */
@Composable
private fun ActionRow(primary: ButtonSpec?, secondary: ButtonSpec?) {
    if (primary != null && secondary != null) {
        Row(Modifier.fillMaxWidth()) {
            ChoiceButton(primary.label, primary.accent, Modifier.weight(1f), primary.onClick)
            Spacer(Modifier.width(16.dp))
            ChoiceButton(secondary.label, secondary.accent, Modifier.weight(1f), secondary.onClick)
        }
    } else {
        val only = primary ?: secondary ?: return
        ChoiceButton(only.label, only.accent, Modifier.fillMaxWidth(), only.onClick)
    }
}

/**
 * The pulsing microphone indicator.
 *
 * Pulses **only while the microphone is genuinely open**, which is the entire point: a sighted
 * helper standing next to the rider needs to be able to tell, at a glance and without asking,
 * whether the app is waiting for speech. Animating it whenever the screen is on would make it a
 * decoration and destroy the signal.
 *
 * When the mic is closed the ring is still drawn, dimmed and static. Removing it entirely would
 * make the layout jump by 140 dp every time the app starts or stops listening — reflowing text
 * under someone who is mid-read, which the brief forbids.
 */
@Composable
private fun ListeningIndicator(micOpen: Boolean, accent: Color, inputLevel: Float) {
    val palette = LocalPalette.current
    val reducedMotion = LocalReducedMotion.current

    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val animatedPulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-scale"
    )

    // Under reduced motion the ring holds still and stays fully opaque instead. The
    // information is carried by the earcon and the haptic regardless, so removing the
    // animation costs the rider nothing — and this animation only ever scales a ring, never
    // moves or reflows text.
    val pulse = if (micOpen && !reducedMotion) animatedPulse else 1f
    val levelScale = 1f + (inputLevel.coerceIn(0f, 1f) * 0.12f)

    Box(
        modifier = Modifier
            .size(140.dp)
            .scale(pulse * levelScale)
            .alpha(if (micOpen) 1f else 0.25f)
            .border(
                width = if (micOpen) 10.dp else 4.dp,
                color = if (micOpen) accent else palette.outline,
                shape = CircleShape
            )
    )
}

// =====================================================================================
//  The two states that ask a question
// =====================================================================================

/**
 * `clarify` — the app is asking a question, and is therefore listening for the answer.
 *
 * Two shapes share this state:
 *
 *  - **Ambiguity**: two candidates within DELTA on score *and* more than DIVERGE apart on the
 *    map. Both numbers are shown, because they are also logged on every decision and having
 *    them on screen makes a demo inspectable without a logcat window open.
 *  - **Near miss**: one candidate in the 0.30–0.50 band. Plausible, not actionable. This is the
 *    case that used to book silently, and the question costs the rider about two seconds
 *    against a wrong booking costing them twenty minutes.
 *
 * The buttons are the sighted-helper fallback. The rider answers out loud, and neither path is
 * second-class: the same two options are on screen and in the spoken prompt, in the same order.
 */
@Composable
private fun ClarifyContent(
    state: RiderState.Clarify,
    micOpen: Boolean,
    onChoice: (PlaceOption) -> Unit,
    onNearMissAnswer: (Boolean) -> Unit
) {
    val palette = LocalPalette.current

    if (state.isNearMiss) {
        StateScaffold(
            icon = "?",
            word = "Check",
            headline = "Did you mean\n${state.optionA.name}?",
            support = "Say yes or no",
            accent = palette.clarify,
            micOpen = micOpen,
            primary = ButtonSpec("Yes", palette.confirm) { onNearMissAnswer(true) },
            secondary = ButtonSpec("No", palette.danger) { onNearMissAnswer(false) }
        )
        return
    }

    StateScaffold(
        icon = "?",
        word = "Which one",
        headline = "Which\none?",
        // Diagnostics, deliberately on screen. gap < 0.16 AND divergence > 1.5 km is what
        // justified interrupting the rider at all.
        support = "gap ${"%.3f".format(state.scoreGap)}  ·  " +
                "${"%.2f".format(state.divergenceKm)} km apart",
        accent = palette.clarify,
        micOpen = micOpen,
        primary = ButtonSpec(state.optionA.name, palette.clarify) { onChoice(state.optionA) },
        secondary = ButtonSpec(state.optionB.name, palette.clarify) { onChoice(state.optionB) }
    )
}

/**
 * `confirming` — the ride is **already being booked**.
 *
 * This is optimistic execution, so there is no question on screen and none asked aloud. The app
 * states what it is doing and gives a real, honoured five-second window in which the microphone
 * is open and saying "cancel" genuinely aborts. A confirmation prompt would add a full
 * conversational turn to every single booking to guard against a rare mistake.
 *
 * Note the mic indicator is pulsing throughout this state. That is not a bug — the window only
 * means anything because the app is genuinely listening for the whole of it, and the pulse is
 * the visible proof.
 */
@Composable
private fun ConfirmingContent(
    state: RiderState.Confirming,
    micOpen: Boolean,
    destination: PlaceOption?,
    onCancel: () -> Unit
) {
    val palette = LocalPalette.current
    val secondsLeft = (state.cancelWindowMillisRemaining / 1000.0).roundToInt()

    StateScaffold(
        icon = "✓",
        word = "Booking ${state.rideType.spokenName}",
        headline = state.destination,
        support = buildString {
            append("Say “cancel”  ·  ${secondsLeft}s")
            destination?.formattedAddress?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
        },
        accent = palette.confirm,
        micOpen = micOpen,
        primary = ButtonSpec("Cancel", palette.danger, onCancel),
        extraContent = destination
            ?.takeIf { BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank() }
            ?.let { place -> { DestinationMapPreview(place = place) } }
    )
}

/**
 * Non-interactive map preview of the exact place returned by Places SDK. Voice remains the
 * primary interaction; disabling map clicks keeps the existing whole-screen hold-to-talk target.
 */
@Composable
private fun DestinationMapPreview(place: PlaceOption) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var configuredKey by remember { mutableStateOf("") }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    val key = "${place.placeId}:${place.latitude}:${place.longitude}"
    AndroidView(
        factory = {
            mapView.apply {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        update = { view ->
            if (configuredKey != key) {
                configuredKey = key
                view.getMapAsync { map ->
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isScrollGesturesEnabled = false
                    map.uiSettings.isZoomGesturesEnabled = false
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    val target = LatLng(place.latitude, place.longitude)
                    map.clear()
                    map.addMarker(MarkerOptions().position(target).title(place.name))
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15f))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            // 150dp, not 180dp: the map has to share one non-scrollable screen with a
            // three-line place name, the address line and the Cancel button.
            .height(150.dp)
            .border(1.dp, LocalPalette.current.outline, RoundedCornerShape(16.dp))
    )
}

// =====================================================================================
//  SOS
// =====================================================================================

/**
 * SOS is an overlay, not a state — it can be raised on top of any point in the ride and
 * dismissing it must return the rider exactly where they were.
 */
@Composable
private fun SosOverlay(onDismiss: () -> Unit) {
    val palette = LocalPalette.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        CenteredColumn {
            Text(
                text = "SOS",
                style = MaterialTheme.typography.displayLarge,
                color = palette.danger,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Sharing your location",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            ChoiceButton("Dismiss", palette.danger, Modifier.fillMaxWidth(), onDismiss)
        }
    }
}

/**
 * The connection indicator, which doubles as the hidden entrance to settings.
 *
 * ## Why the gesture is deliberately awkward
 * Settings must be reachable — the ngrok URL changes every time the tunnel restarts, and
 * someone has to be able to paste the new one. But the rider must not be able to get there by
 * accident: the whole screen is a press target, and a blind rider who lands on a settings form
 * has no way to know where they are or how to leave. So it takes [SETTINGS_TAP_COUNT] taps on
 * a small chip in a corner, within a short window — a gesture nobody performs unintentionally,
 * and one that a helper can be told over the phone.
 *
 * ## Why it is `clearAndSetSemantics`
 * The surface is one merged accessibility node with an assertive live region. Leaving this chip
 * in the tree would add a second focusable thing to swipe to, which is precisely the traversal
 * this product exists to remove. Its information — offline — reaches the rider as speech from
 * the view model, which is the channel that actually works for them.
 */
@Composable
private fun ConnectionChip(
    connected: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    var taps by remember { mutableIntStateOf(0) }
    var firstTapAt by remember { mutableLongStateOf(0L) }

    // The visual glyph stays a single unobtrusive character, but the actual touch target is
    // the full MinTouchTarget square anchored at the same corner. Landing 5 taps on a bare
    // "·" glyph's own pixels is not reliable on a real screen — the tap kept missing onto
    // the full-screen hold-to-talk surface behind it, which is a size bug, not a Compose
    // dispatch-order one: a hit inside this box's bounds already takes priority over the
    // surface's pointerInput, same as any button placed over a scrollable background does.
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clearAndSetSemantics { }
            .clickable {
                val now = System.currentTimeMillis()
                if (now - firstTapAt > SETTINGS_TAP_WINDOW_MS) {
                    firstTapAt = now
                    taps = 1
                } else {
                    taps++
                }
                if (taps >= SETTINGS_TAP_COUNT) {
                    taps = 0
                    onOpenSettings()
                }
            },
        contentAlignment = Alignment.TopEnd
    ) {
        Text(
            text = if (connected) "·" else "offline",
            style = MaterialTheme.typography.bodyMedium,
            color = if (connected) palette.muted else palette.danger,
            modifier = Modifier.padding(20.dp)
        )
    }
}

/** Always-available SOS control, comfortably above the 88.dp floor. */
@Composable
private fun SosButton(onSos: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current

    Button(
        onClick = onSos,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = palette.danger
        ),
        modifier = modifier
            .padding(20.dp)
            .size(MinTouchTarget)
            .border(FocusIndicatorWidth, palette.danger, RoundedCornerShape(20.dp))
    ) {
        Text("SOS", style = MaterialTheme.typography.labelLarge)
    }
}

// =====================================================================================
//  Shared pieces
// =====================================================================================

// =====================================================================================
//  Payment hand-off
// =====================================================================================

/**
 * Opens the rider's UPI app to settle the fare.
 *
 * A `upi://pay` deep link rather than a payment SDK. Nothing to integrate, nothing billed,
 * no card or PIN ever touches this app — the rider authorises in the app they already have
 * set up, which for a blind rider means the accessibility configuration they have already
 * done there carries over. That is the whole reason to hand off rather than embed.
 *
 * Navi is tried by package first because it is the app this deployment targets. If it is not
 * installed the same URI goes to the system chooser, so any UPI app still works — a hard
 * requirement, since a rider cannot be stranded at the end of a ride because one particular
 * app is missing.
 */
private fun payWithUpi(context: Context, amountRupees: Int, note: String) {
    val uri = Uri.parse(
        "upi://pay" +
            "?pa=$PAYEE_VPA" +
            "&pn=${Uri.encode(PAYEE_NAME)}" +
            "&am=$amountRupees" +
            "&cu=INR" +
            "&tn=${Uri.encode(note)}"
    )

    val toNavi = Intent(Intent.ACTION_VIEW, uri).setPackage(NAVI_PACKAGE)
    val toAnyUpiApp = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Pay with")

    for (intent in listOf(toNavi, toAnyUpiApp)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Navi absent — fall through to whatever UPI app the rider does have.
        }
    }
    Log.w(PAY_TAG, "PAYMENT no UPI app available for ₹$amountRupees")
}

/** Navi's Android application id. Verify with `adb shell pm list packages | findstr navi`. */
private const val NAVI_PACKAGE = "com.naviapp"

/**
 * Placeholder payee. MUST be replaced with the real collecting VPA before any live use —
 * as written the link opens the UPI app with an address that will not resolve.
 */
private const val PAYEE_VPA = "cabeye@naviaxis"
private const val PAYEE_NAME = "Cab Eye"
private const val PAY_TAG = "CabEye.Payment"

/** How much of the surface the headline block occupies. The brief's floor is 60%. */
private const val PRIMARY_TARGET_FRACTION = 0.60f

/**
 * The floor used when a state also renders extra content below the headline.
 *
 * The 60% floor plus a 180dp map does not fit on a phone, and the surface cannot be scrolled
 * without opening the microphone, so the map would be unreachable in practice. The headline
 * stays the largest single element on screen and the whole surface stays pressable, so the
 * hold-to-talk target is unchanged — only the reserved minimum shrinks.
 */
private const val PRIMARY_TARGET_FRACTION_WITH_EXTRA = 0.24f

/** Taps on the connection chip that open settings. See [ConnectionChip]. */
private const val SETTINGS_TAP_COUNT = 5

/** They must land within this window of each other, so a stray tap cannot accumulate. */
private const val SETTINGS_TAP_WINDOW_MS = 3_000L

/**
 * Full-bleed centred column with generous padding, used by every state.
 *
 * `verticalScroll` is what makes 200% system font survivable. At that setting a 60sp headline
 * renders at 120sp and a two-button row grows to match; without a scroll container the bottom
 * of the column is simply clipped off, and the buttons that get clipped are the ones the
 * low-vision user turned the font up in order to use.
 */
@Composable
private fun CenteredColumn(
    verticalPadding: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}

/**
 * A discrete control.
 *
 * `defaultMinSize` rather than a fixed `height` so the button still grows when the user has
 * a large system font set — pinning the height would clip the label for exactly the
 * low-vision users this screen exists for.
 *
 * The border is [FocusIndicatorWidth] thick and carries the accent, so the control is bounded
 * by shape as well as by colour and never relies on hue alone to be findable.
 */
@Composable
private fun ChoiceButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = accent
        ),
        modifier = modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(FocusIndicatorWidth, accent, RoundedCornerShape(24.dp))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
    }
}

/**
 * A real place from the active city, so the idle hint is never a place the app cannot book.
 *
 * Uses the city's curated examples rather than the head of its place list, for the same reason
 * the recovery ladder does: the list happens to start with the deliberately ambiguous pair, and
 * an on-screen hint that steers the rider into a clarification question is a bad hint.
 */
private fun firstExample(): String =
    Gazetteer.activeCity.examples.firstOrNull() ?: "the airport"

// =====================================================================================
//  Accessibility helpers
// =====================================================================================

/**
 * The single sentence TalkBack announces for the whole surface.
 *
 * Because the node is an assertive live region, this string is spoken whenever it changes.
 * It is written to be heard, not read: short, no abbreviations, and no punctuation that a
 * screen reader will spell out.
 *
 * Note this runs in parallel with the app's own narrator. A rider using TalkBack hears
 * this; a rider who is not hears the [com.cabeye.rider.audio.AudioEngine]. Keeping the two
 * texts close in wording keeps the product one product.
 */
private fun announcementFor(uiState: RiderUiState): String {
    if (uiState.sosActive) return "S O S active. Sharing your location."

    return when (val ride = uiState.ride) {
        is RiderState.Idle ->
            "Ready. Hold anywhere and say where you want to go in ${uiState.activeCityName}."

        is RiderState.Listening ->
            if (ride.isFollowUp) "Listening for your answer." else "Listening."

        is RiderState.Resolving ->
            "Working that out."

        is RiderState.Clarify ->
            if (ride.isNearMiss) "Did you mean ${ride.optionA.name}? Say yes or no."
            else "Did you mean ${ride.optionA.name} or ${ride.optionB.name}?"

        is RiderState.Confirming ->
            "Booking ${ride.rideType.spokenName} to ${ride.destination}. Say cancel to stop."

        is RiderState.Finding ->
            "Finding a driver."

        is RiderState.Assigned ->
            "${ride.driver.name} is coming, ${ride.etaMinutes} minutes away."

        // No spoken distance during approach — the earcon carries it. This description
        // exists only for the TalkBack case, where an earcon alone would be ambiguous.
        is RiderState.Approaching ->
            "Your driver is approaching."

        is RiderState.Arrived ->
            "Your driver has arrived. Wait for the driver to say the code."

        is RiderState.InTrip ->
            "On the way to ${ride.destination}, ${ride.etaMinutes} minutes."

        is RiderState.Done ->
            "You have arrived at ${ride.destination}. Fare ${ride.fareRupees} rupees."
    }
}

/** The haptic that accompanies entering a state. */
private fun hapticFor(uiState: RiderUiState): HapticPattern {
    if (uiState.sosActive) return HapticPattern.SOS

    return when (uiState.ride) {
        is RiderState.Idle -> HapticPattern.LISTENING_END
        is RiderState.Listening -> HapticPattern.LISTENING_START
        is RiderState.Resolving -> HapticPattern.LISTENING_END
        is RiderState.Clarify -> HapticPattern.CLARIFY
        is RiderState.Confirming -> HapticPattern.CONFIRMED
        is RiderState.Finding -> HapticPattern.LISTENING_END
        is RiderState.Assigned -> HapticPattern.CONFIRMED
        is RiderState.Approaching -> HapticPattern.LISTENING_END
        // The one pattern a rider must recognise through a coat pocket.
        is RiderState.Arrived -> HapticPattern.ARRIVED
        is RiderState.InTrip -> HapticPattern.CONFIRMED
        is RiderState.Done -> HapticPattern.CONFIRMED
    }
}

/**
 * Custom accessibility actions for the merged node.
 *
 * Merging descendants is what makes the surface one target, but it also removes the child
 * buttons from TalkBack's traversal. These actions restore those affordances without
 * reintroducing a list to swipe through — a TalkBack user reaches them from the
 * local-context menu on the single node.
 */
private fun buildCustomActions(
    uiState: RiderUiState,
    onCancel: () -> Unit,
    onClarifyChoice: (PlaceOption) -> Unit,
    onNearMissAnswer: (Boolean) -> Unit,
    onSos: () -> Unit
): List<CustomAccessibilityAction> {
    val actions = mutableListOf<CustomAccessibilityAction>()

    when (val ride = uiState.ride) {
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
        }
        else -> Unit
    }

    actions += CustomAccessibilityAction("Emergency S O S") { onSos(); true }
    return actions
}
