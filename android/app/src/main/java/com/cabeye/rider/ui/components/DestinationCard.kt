package com.cabeye.rider.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cabeye.rider.BuildConfig
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RideType
import com.cabeye.rider.ui.theme.CabEyeColors
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.CabEyeSpacing
import com.cabeye.rider.ui.theme.CabEyeType
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

/**
 * Minimalist, high-contrast destination card for Confirming and InTrip states.
 *
 * Displays:
 * - 📍 Destination name (large, bold)
 * - Address / City subtitle
 * - Vehicle type badge (e.g. 🚕 Cab)
 * - Pickup location indicator ("Current location")
 * - Optional non-interactive map preview
 */
@Composable
fun DestinationCard(
    destinationName: String,
    modifier: Modifier = Modifier,
    address: String? = null,
    rideType: RideType? = null,
    place: PlaceOption? = null,
    pickupText: String = "Current location",
    showMapPreview: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CabEyeShapes.card)
            .background(CabEyeColors.SurfaceDark)
            .border(2.dp, CabEyeColors.CardBorder, CabEyeShapes.card)
            .padding(CabEyeSpacing.md)
    ) {
        // Top row: Destination Icon & Name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(CabEyeColors.SurfaceElevated, CircleShape)
                    .border(1.5.dp, CabEyeColors.SuccessGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "📍", fontSize = 20.sp)
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = destinationName,
                    style = CabEyeType.mainInstruction.copy(fontWeight = FontWeight.Bold),
                    color = CabEyeColors.TextPrimary
                )
                if (!address.isNullOrBlank()) {
                    Text(
                        text = address,
                        style = CabEyeType.secondaryInfo,
                        color = CabEyeColors.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Details Row: Ride Type & Pickup Location
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CabEyeColors.SurfaceElevated, CabEyeShapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rideType != null) {
                val icon = when (rideType) {
                    RideType.AUTO -> "🛺"
                    RideType.CAB -> "🚕"
                    RideType.BIKE -> "🏍"
                }
                Text(
                    text = "$icon ${rideType.spokenName.replaceFirstChar { it.uppercase() }}",
                    style = CabEyeType.buttonText.copy(fontSize = 16.sp),
                    color = CabEyeColors.WarningYellow
                )
                Spacer(Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(CabEyeColors.TextMuted, CircleShape)
                )
                Spacer(Modifier.width(16.dp))
            }

            Text(
                text = "Pickup: $pickupText",
                style = CabEyeType.secondaryInfo.copy(fontSize = 14.sp),
                color = CabEyeColors.TextSecondary
            )
        }

        // Optional non-interactive map preview if Google Maps key is present
        if (showMapPreview && place != null && BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            MapPreview(place = place)
        }
    }
}

@Composable
private fun MapPreview(place: PlaceOption) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }

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

    AndroidView(
        factory = {
            mapView.apply {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        update = { view ->
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
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(CabEyeShapes.medium)
            .border(1.dp, CabEyeColors.CardBorder, CabEyeShapes.medium)
    )
}
