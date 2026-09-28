package com.cabeye.rider.driver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.ui.QrCode
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.MinTouchTarget

/**
 * The driver's surface: seven screens, none of which make a sound.
 *
 * ## Why this looks nothing like the rider's screen
 * The rider's surface is one enormous press target with a 60sp headline, because its user
 * cannot see it and its sighted users have low vision. This one is for a person with normal
 * sight who is about to drive, or is driving. Different constraints entirely: information
 * density is fine, but **glance time** is everything, so every screen has one obvious action
 * and the buttons are large enough to hit without aiming.
 *
 * ## The four accessibility affordances, and where they live
 *  1. **The badge** — [ImpairedBadge], non-dismissable, on the request and pickup screens.
 *  2. **Position presets** — [PresetGrid], on the navigating screen. Driver taps, rider hears.
 *  3. **The audio beacon** — [BeaconRow], panned to the driver's real bearing.
 *  4. **Confirm seated** — its own screen, and a hard gate the server enforces too.
 *
 * @param onSettings opens the debug settings screen, which is the only way back to rider mode
 */
@Composable
fun DriverSurface(
    uiState: DriverUiState,
    onGoOnline: () -> Unit,
    onGoOffline: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onPreset: (PositionPreset) -> Unit,
    onBeacon: (Float) -> Unit,
    onLocation: (Int, Float) -> Unit,
    onArrived: () -> Unit,
    onConfirmSeated: () -> Unit,
    onStartTrip: () -> Unit,
    onComplete: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onMessage: (String) -> Unit,
    onSettings: () -> Unit,
    driverName: String = "",
    onProfile: () -> Unit = {},
    cameraFrame: Bitmap? = null,
    onRequestCamera: () -> Unit = {},
    onStopCamera: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DriverHeader(uiState.connected, driverName, onProfile, onSettings)

            Spacer(Modifier.height(16.dp))

            when (val state = uiState.state) {
                is DriverState.Offline -> OfflineScreen(onGoOnline)
                is DriverState.Online -> OnlineScreen(state, onGoOffline)
                is DriverState.Request -> RequestScreen(state, onAccept, onDecline)
                is DriverState.Navigating -> NavigatingScreen(
                    state, uiState.lastPresetSent, onPreset, onBeacon, onLocation, onArrived, onCancel, onMessage,
                    camera = { RiderViewPanel(uiState.camera, cameraFrame, onRequestCamera, onStopCamera) }
                )
                is DriverState.Arrived -> ArrivedScreen(
                    state, onConfirmSeated, onCancel,
                    camera = { RiderViewPanel(uiState.camera, cameraFrame, onRequestCamera, onStopCamera) },
                    onMessage = onMessage
                )
                is DriverState.Seated -> SeatedScreen(onStartTrip)
                is DriverState.InTrip -> InTripScreen(state, onComplete, onMessage)
                is DriverState.Complete -> CompleteScreen(state, onFinish)
            }

            if (uiState.banner.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = uiState.banner,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.clarify,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// =====================================================================================
//  Header
// =====================================================================================

@Composable
private fun DriverHeader(connected: Boolean, driverName: String, onProfile: () -> Unit, onSettings: () -> Unit) {
    val palette = LocalPalette.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // The signed-in driver's name doubles as the way into their profile.
        Button(
            onClick = onProfile,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.background,
                contentColor = palette.muted
            ),
            modifier = Modifier.border(2.dp, palette.outline, RoundedCornerShape(14.dp))
        ) {
            Text(
                text = if (driverName.isBlank()) "DRIVER" else "● $driverName",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // The driver can see this, so unlike the rider's side it is shown and never
                // announced. Nothing on this screen is ever spoken.
                text = if (connected) "● online" else "○ offline",
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) palette.confirm else palette.danger
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onSettings,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.background,
                    contentColor = palette.muted
                ),
                modifier = Modifier.border(2.dp, palette.outline, RoundedCornerShape(14.dp))
            ) {
                Text("Settings", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// =====================================================================================
//  1 — Offline
// =====================================================================================

@Composable
private fun OfflineScreen(onGoOnline: () -> Unit) {
    val palette = LocalPalette.current

    Spacer(Modifier.height(60.dp))
    Text("You are offline", style = MaterialTheme.typography.headlineLarge, color = palette.onBackground)
    Spacer(Modifier.height(12.dp))
    Text(
        "Go online to receive ride requests.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(40.dp))
    DriverButton("GO ONLINE", palette.confirm, onClick = onGoOnline)
}

// =====================================================================================
//  2 — Online, waiting
// =====================================================================================

@Composable
private fun OnlineScreen(state: DriverState.Online, onGoOffline: () -> Unit) {
    val palette = LocalPalette.current

    Spacer(Modifier.height(60.dp))
    Text("Waiting for a ride", style = MaterialTheme.typography.headlineLarge, color = palette.listening)
    Spacer(Modifier.height(12.dp))
    Text(
        if (state.waitingSeconds > 0) "${state.waitingSeconds} seconds" else "Listening for requests",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted
    )
    Spacer(Modifier.height(40.dp))
    DriverButton("GO OFFLINE", palette.danger, onClick = onGoOffline)
}

// =====================================================================================
//  3 — A request
// =====================================================================================

@Composable
private fun RequestScreen(
    state: DriverState.Request,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val palette = LocalPalette.current

    // Before the destination, before the fare, before anything. A driver who learns this at
    // the kerb rather than here is a driver who was not given a chance to prepare.
    if (state.visuallyImpaired) ImpairedBadge()

    Spacer(Modifier.height(24.dp))
    Text("New request", style = MaterialTheme.typography.labelLarge, color = palette.muted)
    Spacer(Modifier.height(8.dp))
    Text(
        state.destination,
        style = MaterialTheme.typography.displayMedium,
        color = palette.onBackground,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(state.rideType.lowercase(), style = MaterialTheme.typography.bodyLarge, color = palette.muted)

    Spacer(Modifier.height(36.dp))
    DriverButton("ACCEPT", palette.confirm, onClick = onAccept)
    Spacer(Modifier.height(12.dp))
    DriverButton("Decline", palette.muted, onClick = onDecline)
}

// =====================================================================================
//  4 — Navigating to the pickup
// =====================================================================================

@Composable
private fun NavigatingScreen(
    state: DriverState.Navigating,
    lastPreset: String,
    onPreset: (PositionPreset) -> Unit,
    onBeacon: (Float) -> Unit,
    onLocation: (Int, Float) -> Unit,
    onArrived: () -> Unit,
    onCancel: () -> Unit,
    onMessage: (String) -> Unit,
    camera: @Composable () -> Unit = {}
) {
    val palette = LocalPalette.current

    // Still showing. The badge is on the request screen AND this one, because this is where
    // the driver is deciding how to approach the kerb and who they are looking for.
    if (state.visuallyImpaired) ImpairedBadge()

    // Can't spot them? Ask to see what their phone's camera sees.
    camera()

    Spacer(Modifier.height(20.dp))
    Text("Navigate to pickup", style = MaterialTheme.typography.headlineMedium, color = palette.onBackground)
    Spacer(Modifier.height(6.dp))
    Text(
        "Passenger is going to ${state.destination}",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(24.dp))
    BeaconRow(state.bearingDegrees, onBeacon)

    Spacer(Modifier.height(24.dp))
    Text(
        "Tell them where you are",
        style = MaterialTheme.typography.bodyLarge,
        color = palette.clarify
    )
    Spacer(Modifier.height(4.dp))
    Text(
        // The rule, said on screen so it does not have to be learned by trial: the driver taps,
        // the rider's phone speaks. Neither one crosses into the other's modality.
        "You tap. Their phone speaks it. You never have to.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    PresetGrid(lastPreset, onPreset)

    Spacer(Modifier.height(20.dp))
    MessageComposer(onMessage)

    Spacer(Modifier.height(24.dp))
    DistanceRow(state.distanceMeters, state.bearingDegrees, onLocation)

    // Heading to the rider, so this navigates to the PICKUP point, not the drop-off.
    NavigateButton(
        latitude = state.pickupLatitude,
        longitude = state.pickupLongitude,
        label = "NAVIGATE TO PICKUP"
    )

    MeetingContactCard(state.contactName, state.contactPhone, state.dropNote)

    Spacer(Modifier.height(28.dp))
    DriverButton("I HAVE ARRIVED", palette.confirm, onClick = onArrived)
    Spacer(Modifier.height(12.dp))
    DriverButton("Cancel ride", palette.danger, onClick = onCancel)
}

/**
 * Hands the route off to the driver's own Google Maps app.
 *
 * Deliberately an Intent rather than an in-app route. Drawing the route ourselves would mean
 * the Routes API — another billed product, a polyline renderer, and a second navigation UI for
 * a driver who already has one they trust and already has running on the dashboard. Rapido and
 * Uber drivers in India work exactly this way. It also keeps the driver's turn-by-turn voice
 * out of this app entirely, which matters because the rider's narration is the audio channel
 * this product actually owns.
 *
 * Renders nothing when coordinates are absent — a ride resolved by the offline Gazetteer has a
 * place name but no fix, and a Navigate button that opens the map at 0,0 is worse than no
 * button at all.
 */
@Composable
private fun NavigateButton(latitude: Double?, longitude: Double?, label: String) {
    if (latitude == null || longitude == null) return

    val context = LocalContext.current
    Spacer(Modifier.height(20.dp))
    DriverButton(label, LocalPalette.current.listening) {
        openInGoogleMaps(context, latitude, longitude)
    }
}

/**
 * Tries turn-by-turn in Google Maps, then any installed map app, then the browser.
 *
 * `try`/`catch` rather than `resolveActivity`: from Android 11 package visibility would make
 * `resolveActivity` return null for Maps unless a `<queries>` element is added to the manifest,
 * so the check would silently disable the button on exactly the modern devices it is for.
 */
private fun openInGoogleMaps(context: Context, latitude: Double, longitude: Double) {
    val navigation = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.navigation:q=$latitude,$longitude&mode=d")
    ).setPackage("com.google.android.apps.maps")

    val anyMapApp = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
    )

    val browser = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(
            "https://www.google.com/maps/dir/?api=1" +
                "&destination=$latitude,$longitude&travelmode=driving"
        )
    )

    for (intent in listOf(navigation, anyMapApp, browser)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the next, less specific, way of showing the same point.
        }
    }
    Log.w(TAG, "NAVIGATE no app could handle $latitude,$longitude")
}

private const val TAG = "CabEye.Driver"

// =====================================================================================
//  5 — Arrived: the boarding code
// =====================================================================================

/**
 * The pickup screen, and the reason the boarding code works the way it does.
 *
 * **The driver's screen shows the code and the driver says it aloud.** The rider's phone never
 * announces it over a loudspeaker — a blind rider standing on a street cannot tell who is
 * within earshot, and broadcasting a secret to an unknown audience defeats the point of having
 * one. Hearing the correct code come out of the car is exactly the verification a blind rider
 * cannot perform by looking at a number plate.
 *
 * So this screen has one job, rendered as large as the display allows.
 */
@Composable
private fun ArrivedScreen(
    state: DriverState.Arrived,
    onConfirmSeated: () -> Unit,
    onCancel: () -> Unit,
    camera: @Composable () -> Unit = {},
    onMessage: (String) -> Unit = {}
) {
    val palette = LocalPalette.current

    ImpairedBadge()

    Spacer(Modifier.height(28.dp))
    Text(
        "SAY THIS CODE OUT LOUD",
        style = MaterialTheme.typography.labelLarge,
        color = palette.clarify,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(4.dp, palette.clarify, RoundedCornerShape(24.dp))
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = state.boardingCode,
            // Deliberately larger than anything else in the app. It is read at arm's length,
            // through a windscreen, in daylight, by someone who then has to say it clearly.
            fontSize = 84.sp,
            style = MaterialTheme.typography.displayLarge,
            color = palette.clarify,
            textAlign = TextAlign.Center
        )
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Your passenger's phone is listening for it. Do not show them the screen — say it.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Center
    )

    if (state.codeConfirmed) {
        Spacer(Modifier.height(20.dp))
        Text(
            "✓ Passenger confirmed the code",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.confirm,
            textAlign = TextAlign.Center
        )
    } else {
        // Still looking for them at the kerb: the camera and a message stay one tap away.
        camera()
        Spacer(Modifier.height(16.dp))
        MessageComposer(onMessage)
    }

    Spacer(Modifier.height(32.dp))

    // The gate. Not "start trip" — this screen cannot start a trip, and neither can the server
    // until this has happened. And this cannot happen until the passenger's phone has
    // confirmed the code: the server refuses it, so the screen says so up front.
    if (!state.codeConfirmed) {
        Text(
            "Waiting for your passenger's phone to confirm the code. Say it clearly, close to their phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.clarify,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
    }
    DriverButton("PASSENGER IS SEATED", if (state.codeConfirmed) palette.confirm else palette.muted, onClick = onConfirmSeated)
    Spacer(Modifier.height(12.dp))
    DriverButton("Cancel ride", palette.danger, onClick = onCancel)
}

// =====================================================================================
//  The passenger's camera
// =====================================================================================

/**
 * "See your passenger's view": the passenger's back camera, shown live, to find them.
 *
 * Only a request button until the passenger says yes on their own phone — this screen can
 * ask, never switch it on. It goes off by itself when the code is confirmed, the passenger is
 * seated, after three minutes, or when either side stops it.
 */
@Composable
private fun RiderViewPanel(
    camera: DriverCamera,
    frame: Bitmap?,
    onRequest: () -> Unit,
    onStop: () -> Unit
) {
    val palette = LocalPalette.current
    Spacer(Modifier.height(20.dp))

    when (camera) {
        DriverCamera.OFF, DriverCamera.DECLINED -> {
            DriverButton("SEE PASSENGER'S VIEW", palette.listening, onClick = onRequest)
            Spacer(Modifier.height(6.dp))
            Text(
                if (camera == DriverCamera.DECLINED) "They didn't share it. You can ask again, or send a message."
                else "Can't spot them? Ask to see what their phone's camera sees.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }

        DriverCamera.ASKING -> {
            Text(
                "Asking your passenger…",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.clarify,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Their phone is asking them by voice. Nothing is shown until they say yes.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }

        DriverCamera.LIVE -> {
            Text(
                "● LIVE — your passenger's view",
                style = MaterialTheme.typography.labelLarge,
                color = palette.danger,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .border(3.dp, palette.danger, RoundedCornerShape(18.dp))
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Live view from your passenger's camera",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        "Waiting for the first picture…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Not recorded. Turns off when the code is confirmed.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            DriverButton("STOP CAMERA", palette.danger, onClick = onStop)
        }
    }
}

// =====================================================================================
//  6 — Seated
// =====================================================================================

@Composable
private fun SeatedScreen(onStartTrip: () -> Unit) {
    val palette = LocalPalette.current

    Spacer(Modifier.height(60.dp))
    Text("Passenger seated", style = MaterialTheme.typography.headlineLarge, color = palette.confirm)
    Spacer(Modifier.height(12.dp))
    Text(
        "Check the door is closed before you move off.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(40.dp))
    DriverButton("START TRIP", palette.confirm, onClick = onStartTrip)
}

// =====================================================================================
//  7 — In trip / complete
// =====================================================================================

@Composable
private fun InTripScreen(
    state: DriverState.InTrip,
    onComplete: () -> Unit,
    onMessage: (String) -> Unit
) {
    val palette = LocalPalette.current

    Spacer(Modifier.height(60.dp))
    Text("On the way", style = MaterialTheme.typography.headlineLarge, color = palette.onBackground)
    Spacer(Modifier.height(8.dp))
    Text(state.destination, style = MaterialTheme.typography.headlineMedium, color = palette.listening)
    Spacer(Modifier.height(8.dp))
    Text("${state.etaMinutes} min", style = MaterialTheme.typography.bodyLarge, color = palette.muted)

    if (state.destinationAddress.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            state.destinationAddress,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            textAlign = TextAlign.Center
        )
    }

    // The passenger is aboard, so now the drop-off is the thing to navigate to.
    NavigateButton(
        latitude = state.destinationLatitude,
        longitude = state.destinationLongitude,
        label = "NAVIGATE TO DESTINATION"
    )

    MeetingContactCard(state.contactName, state.contactPhone, state.dropNote)

    // Mid-trip is where a revised ETA actually matters — the rider is in the car with no way
    // to see the traffic the driver is sitting in.
    Spacer(Modifier.height(20.dp))
    MessageComposer(onMessage)

    Spacer(Modifier.height(40.dp))
    DriverButton("COMPLETE TRIP", palette.confirm, onClick = onComplete)
}

/**
 * Free-text message the rider's phone speaks aloud.
 *
 * The preset grid answers "where is the car". This answers everything else — a revised ETA,
 * a blocked gate, a diversion. Those are the things a driver would otherwise phone about, and
 * a call is the hardest channel for a blind rider to act on while standing on a pavement.
 *
 * The driver still never speaks and the rider still never reads: the driver types, the
 * rider's phone says it. That rule is the whole reason this feature can exist at all without
 * putting a screen in front of someone who cannot see one, or a phone call in the hand of
 * someone who is driving.
 *
 * The field clears on send, so a driver glancing back at it cannot mistake a sent message for
 * an unsent one and fire it twice.
 */
@Composable
private fun MessageComposer(onMessage: (String) -> Unit) {
    val palette = LocalPalette.current
    var draft by remember { mutableStateOf("") }

    Text(
        "Send a message",
        style = MaterialTheme.typography.bodyLarge,
        color = palette.clarify
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "You type. Their phone speaks it aloud.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = draft,
        onValueChange = { if (it.length <= MAX_MESSAGE_CHARS) draft = it },
        placeholder = { Text("Ten minutes, traffic at Avinashi Road") },
        singleLine = false,
        maxLines = 3,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.onBackground,
            unfocusedTextColor = palette.onBackground,
            focusedBorderColor = palette.clarify,
            unfocusedBorderColor = palette.outline
        ),
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    DriverButton("SPEAK TO RIDER", palette.clarify) {
        val message = draft.trim()
        if (message.isNotBlank()) {
            onMessage(message)
            draft = ""
        }
    }
}


/**
 * Who is meeting the rider at the drop-off, and how to reach them.
 *
 * ## Why this exists
 * "Thadagam Road" is five kilometres long. A sighted passenger leans out and waves; a blind
 * one cannot, and cannot describe which gate they mean either. The person waiting for them can
 * do both. Giving the driver a way to ring them is what turns a road into an address.
 *
 * ## Why the number is not shown
 * The driver's app holds the full number so the dialler can be pre-filled, but the screen shows
 * only the last four digits. That is enough for the driver to confirm they are calling the
 * right person and useless for anything else — a number rendered in full is a number a stranger
 * can photograph, copy or keep long after the ride is over. The rider gave this contact to get
 * home, not to hand a friend's number to whoever accepted the fare.
 *
 * Renders nothing when no contact was given, which is the ordinary case.
 */
@Composable
private fun MeetingContactCard(name: String, phone: String, dropNote: String) {
    if (name.isBlank() && dropNote.isBlank()) return

    val palette = LocalPalette.current
    val context = LocalContext.current

    Spacer(Modifier.height(20.dp))
    Text(
        "Meeting the passenger",
        style = MaterialTheme.typography.bodyLarge,
        color = palette.clarify
    )

    // The rider's own words for the spot. Kept verbatim because a local driver understands
    // "opposite the temple" even where a geocoder could not resolve it.
    if (dropNote.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            dropNote,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackground,
            textAlign = TextAlign.Center
        )
    }

    if (name.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "$name  ·  ${maskedNumber(phone)}",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            textAlign = TextAlign.Center
        )

        if (phone.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            DriverButton("CALL ${name.uppercase()}", palette.listening) {
                dialContact(context, phone)
            }
        }
    }
}

/** Last four digits only. See the note on [MeetingContactCard]. */
private fun maskedNumber(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (digits.length >= 4) "ending ${digits.takeLast(4)}" else "number saved"
}

/**
 * Opens the driver's dialler with the number filled in. The call goes out on the driver's own
 * SIM, from their own phone, as any call would.
 *
 * ACTION_DIAL rather than ACTION_CALL, deliberately. ACTION_CALL would place the call the
 * instant the button is touched and needs the CALL_PHONE permission; this driver is behind a
 * wheel, and a mis-touch that silently starts a call is worse than one that opens a dialler
 * they can glance at and dismiss. Dial needs no permission at all.
 */
private fun dialContact(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Log.w(TAG, "DIAL no dialler available")
    }
}

/** Mirrors DriverViewModel.MAX_MESSAGE_CHARS — spoken aloud, so length is time. */
private const val MAX_MESSAGE_CHARS = 120

@Composable
private fun CompleteScreen(state: DriverState.Complete, onFinish: () -> Unit) {
    val palette = LocalPalette.current

    Spacer(Modifier.height(60.dp))
    Text("Trip complete", style = MaterialTheme.typography.headlineLarge, color = palette.confirm)
    Spacer(Modifier.height(12.dp))
    Text(
        "₹${state.fareRupees}  ·  ${state.durationMinutes} min",
        style = MaterialTheme.typography.bodyLarge,
        color = palette.muted
    )
    Spacer(Modifier.height(24.dp))

    // Live payment line. Words carry the meaning, colour only reinforces it.
    val (paymentText, paymentColor) = when (state.paymentStatus) {
        "CONFIRMED" -> "✓ PAYMENT RECEIVED  ₹${state.fareRupees}" to palette.confirm
        "REPORTED" -> "Rider says paid — waiting for confirmation…" to palette.onBackground
        "FAILED" -> "✕ Payment failed — rider can retry" to palette.danger
        else -> "Waiting for the rider to pay…" to palette.muted
    }
    Text(
        paymentText,
        style = MaterialTheme.typography.titleLarge,
        color = paymentColor,
        textAlign = TextAlign.Center
    )
    // QR for a sighted companion: they scan it with their own phone camera, which opens the
    // sandbox checkout on THEIR phone. The blind customer pays inside their own app instead.
    if (state.paymentStatus != "CONFIRMED" && state.checkoutUrl.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Or scan to pay ₹${state.fareRupees}",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.onBackground
        )
        Spacer(Modifier.height(8.dp))
        QrCode(
            content = state.checkoutUrl,
            size = 200.dp,
            description = "QR code to pay ${state.fareRupees} rupees"
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "TEST MODE · no real money",
            style = MaterialTheme.typography.labelLarge,
            color = palette.muted
        )
    }
    if (state.paymentStatus == "CONFIRMED" && state.paymentRef.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Ref ${state.paymentRef.chunked(4).joinToString(" ")}",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.muted
        )
    }
    Spacer(Modifier.height(32.dp))
    DriverButton("BACK ONLINE", palette.listening, onClick = onFinish)
}

// =====================================================================================
//  The four affordances
// =====================================================================================

/**
 * The non-dismissable badge.
 *
 * No close button, no timer, no way to collapse it. That is the requirement and it is the right
 * one: a badge a driver can dismiss is a badge that gets dismissed by reflex on the first
 * request and never seen again, and the information it carries changes how the whole pickup
 * should go.
 *
 * Its colour is the warning accent, but the meaning does not depend on colour — the text says
 * it outright, for the same reason the rider's states carry a glyph and a word.
 */
@Composable
private fun ImpairedBadge() {
    val palette = LocalPalette.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.clarify.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .border(3.dp, palette.clarify, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PASSENGER IS VISUALLY IMPAIRED",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.clarify,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Do not wave or flash your lights. Use the buttons below to tell them where you are.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The position presets.
 *
 * Two per row so the labels stay readable at a glance, and every tile is at least
 * [MinTouchTarget] tall — a driver is aiming at a phone in a cradle, often while the car is
 * moving in traffic.
 */
@Composable
private fun PresetGrid(lastPreset: String, onPreset: (PositionPreset) -> Unit) {
    val palette = LocalPalette.current
    val presets = PositionPreset.entries

    Column(Modifier.fillMaxWidth()) {
        presets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { preset ->
                    val used = preset.label == lastPreset
                    Button(
                        onClick = { onPreset(preset) },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.background,
                            contentColor = if (used) palette.confirm else palette.onBackground
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .defaultMinSize(minHeight = MinTouchTarget)
                            .border(
                                2.dp,
                                if (used) palette.confirm else palette.outline,
                                RoundedCornerShape(18.dp)
                            )
                    ) {
                        Text(
                            preset.label,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                // Keeps the last row aligned when the preset count is odd.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The audio beacon, and the control that sets which direction it comes from.
 *
 * The bearing is not decoration. It becomes the stereo pan of the tone on the rider's phone, so
 * the sound arrives from the direction the car actually is — something the rider can turn
 * toward. A beacon with no bearing tells them a car exists and nothing about where, which is
 * the part they cannot work out for themselves.
 *
 * Four coarse directions rather than a slider: a driver setting an exact heading is a driver
 * looking at a phone. Left / ahead / right / behind is what a person can say and what a person
 * can act on.
 */
@Composable
private fun BeaconRow(currentBearing: Float, onBeacon: (Float) -> Unit) {
    val palette = LocalPalette.current

    val directions = listOf(
        "◀ Left" to 270f,
        "▲ Ahead" to 0f,
        "▶ Right" to 90f,
        "▼ Behind" to 180f
    )

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Audio beacon — plays a tone from your direction",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            directions.forEach { (label, bearing) ->
                val active = currentBearing == bearing
                Button(
                    onClick = { onBeacon(bearing) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.background,
                        contentColor = if (active) palette.listening else palette.onBackground
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .defaultMinSize(minHeight = MinTouchTarget)
                        .border(
                            2.dp,
                            if (active) palette.listening else palette.outline,
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Distance reporting.
 *
 * Stands in for real GPS, which is out of scope for this build. What matters is that the rider
 * hears a tone whose pitch rises as the car closes — the transport for that is this, and
 * swapping in a real location provider later changes only where the numbers come from.
 */
@Composable
private fun DistanceRow(
    distanceMeters: Int,
    bearingDegrees: Float,
    onLocation: (Int, Float) -> Unit
) {
    val palette = LocalPalette.current
    val steps = listOf(400, 250, 120, 40)

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Report distance — their phone's tone rises as you close in" +
                    if (distanceMeters > 0) "  ·  now ${distanceMeters}m" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            steps.forEach { metres ->
                Button(
                    onClick = { onLocation(metres, bearingDegrees) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.background,
                        contentColor = palette.onBackground
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .defaultMinSize(minHeight = MinTouchTarget)
                        .border(2.dp, palette.outline, RoundedCornerShape(16.dp))
                ) {
                    Text("${metres}m", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// =====================================================================================
//  Shared
// =====================================================================================

/** A full-width action. Large enough to hit without aiming, which is the whole requirement. */
@Composable
private fun DriverButton(label: String, accent: Color, onClick: () -> Unit) {
    val palette = LocalPalette.current

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.background,
            contentColor = accent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .border(3.dp, accent, RoundedCornerShape(22.dp))
    ) {
        Text(
            label,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}
