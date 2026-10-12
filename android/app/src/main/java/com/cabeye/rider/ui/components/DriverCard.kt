package com.cabeye.rider.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.state.DriverInfo
import com.cabeye.rider.ui.theme.CabEyeColors
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.ui.theme.CabEyeShapes
import com.cabeye.rider.ui.theme.CabEyeSpacing
import com.cabeye.rider.ui.theme.CabEyeType

/**
 * Driver information card for Assigned, Approaching, Arrived, and InTrip states.
 *
 * Exposes:
 * - Driver name & rating
 * - Vehicle model & license plate
 * - ETA, distance, direction
 */
@Composable
fun DriverCard(
    driver: DriverInfo,
    modifier: Modifier = Modifier,
    etaMinutes: Int? = null,
    distanceMeters: Int? = null,
    bearingDegrees: Float? = null,
    statusText: String? = null
) {
    val palette = LocalPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CabEyeShapes.card)
            .background(palette.surface)
            .border(2.dp, palette.outline, CabEyeShapes.card)
            .padding(CabEyeSpacing.md)
    ) {
        // Driver header: Avatar, Name, Rating
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(palette.surfaceElevated, CircleShape)
                    .border(2.dp, palette.clarify, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "👤", fontSize = 26.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = driver.name,
                    style = CabEyeType.mainInstruction.copy(fontWeight = FontWeight.Bold),
                    color = palette.onBackground
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "★ ${driver.rating}",
                        style = CabEyeType.secondaryInfo.copy(fontWeight = FontWeight.Bold),
                        color = palette.clarify
                    )
                    if (!statusText.isNullOrBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "·  $statusText",
                            style = CabEyeType.secondaryInfo,
                            color = palette.muted
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Vehicle and plate. The plate never wraps (a split plate is hard to read back to a
        // driver); the vehicle name takes what's left and wraps if it must.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surfaceElevated, CabEyeShapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🚕", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = driver.vehicleModel,
                style = CabEyeType.buttonText.copy(fontSize = 17.sp),
                color = palette.onBackground,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(palette.clarify.copy(alpha = 0.15f), CabEyeShapes.small)
                    .border(1.5.dp, palette.clarify, CabEyeShapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Number plate ${driver.vehiclePlate}" }
            ) {
                Text(
                    text = driver.vehiclePlate,
                    style = CabEyeType.buttonText.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = palette.clarify,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // ETA / Distance / Direction row if available
        if ((etaMinutes != null && etaMinutes > 0) || (distanceMeters != null && distanceMeters >= 0)) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (etaMinutes != null && etaMinutes > 0) {
                    Text(
                        text = "Arriving in $etaMinutes min",
                        style = CabEyeType.buttonText.copy(fontSize = 16.sp),
                        color = palette.clarify
                    )
                }

                if (distanceMeters != null && distanceMeters >= 0) {
                    Text(
                        text = "${formatDistance(distanceMeters)} away",
                        style = CabEyeType.secondaryInfo.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.onBackground
                    )
                }
            }
        }

        // Bearing / Direction guidance
        if (bearingDegrees != null) {
            val directionText = describeBearing(bearingDegrees)
            Spacer(Modifier.height(6.dp))
            Text(
                text = directionText,
                style = CabEyeType.secondaryInfo.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = palette.muted
            )
        }
    }
}

/**
 * A distance as a rider reads it: "40 m", "1.2 km". Metres are rounded to 10 (GPS is not
 * better than that, and "37 m" claims a precision nobody has). Callers must not pass the
 * wire's "unknown" (-1): an unknown distance is not shown at all.
 */
fun formatDistance(metres: Int): String {
    if (metres < 10) return "under 10 m"
    val rounded = (metres + 5) / 10 * 10
    return if (rounded >= 1000) String.format(java.util.Locale.ROOT, "%.1f km", rounded / 1000.0) else "$rounded m"
}

/** Human-friendly bearing description (e.g. "Slightly to your right"). */
private fun describeBearing(degrees: Float): String {
    val norm = ((degrees % 360) + 360) % 360
    return when {
        norm in 340f..360f || norm in 0f..20f -> "Straight ahead of you"
        norm in 20f..70f -> "Slightly to your right"
        norm in 70f..110f -> "To your right"
        norm in 110f..160f -> "Behind to your right"
        norm in 160f..200f -> "Directly behind you"
        norm in 200f..250f -> "Behind to your left"
        norm in 250f..290f -> "To your left"
        else -> "Slightly to your left"
    }
}
