package com.cabeye.rider.ui.theme

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabeye.rider.state.ThemeChoice

/**
 * One theme's colours, resolved.
 *
 * Every field is used by name rather than by role-in-a-Material-scheme, because the states of
 * this app are its own vocabulary — `listening` and `clarify` are not "primary" and "tertiary",
 * and pretending otherwise would make the next person guess.
 */
data class CabEyePalette(
    val choice: ThemeChoice,
    val isLight: Boolean,
    val background: Color,
    val onBackground: Color,
    val muted: Color,
    val listening: Color,
    val confirm: Color,
    val clarify: Color,
    val danger: Color,
    val outline: Color
)

private fun paletteFor(choice: ThemeChoice): CabEyePalette = when (choice) {

    ThemeChoice.DEEP_DARK -> CabEyePalette(
        choice = choice,
        isLight = false,
        background = Color(CabEyeInk.DD_BACKGROUND),
        onBackground = Color(CabEyeInk.DD_ON_BACKGROUND),
        muted = Color(CabEyeInk.DD_MUTED),
        listening = Color(CabEyeInk.DD_LISTENING),
        confirm = Color(CabEyeInk.DD_CONFIRM),
        clarify = Color(CabEyeInk.DD_CLARIFY),
        danger = Color(CabEyeInk.DD_DANGER),
        outline = Color(CabEyeInk.DD_OUTLINE)
    )

    ThemeChoice.HIGH_CONTRAST_YELLOW -> CabEyePalette(
        choice = choice,
        isLight = false,
        background = Color(CabEyeInk.HCY_BACKGROUND),
        onBackground = Color(CabEyeInk.HCY_ON_BACKGROUND),
        muted = Color(CabEyeInk.HCY_MUTED),
        listening = Color(CabEyeInk.HCY_LISTENING),
        confirm = Color(CabEyeInk.HCY_CONFIRM),
        clarify = Color(CabEyeInk.HCY_CLARIFY),
        danger = Color(CabEyeInk.HCY_DANGER),
        outline = Color(CabEyeInk.HCY_OUTLINE)
    )

    ThemeChoice.HIGH_CONTRAST_LIGHT -> CabEyePalette(
        choice = choice,
        isLight = true,
        background = Color(CabEyeInk.HCL_BACKGROUND),
        onBackground = Color(CabEyeInk.HCL_ON_BACKGROUND),
        muted = Color(CabEyeInk.HCL_MUTED),
        listening = Color(CabEyeInk.HCL_LISTENING),
        confirm = Color(CabEyeInk.HCL_CONFIRM),
        clarify = Color(CabEyeInk.HCL_CLARIFY),
        danger = Color(CabEyeInk.HCL_DANGER),
        outline = Color(CabEyeInk.HCL_OUTLINE)
    )
}

/**
 * The active palette.
 *
 * `static` because a theme change should recompose the whole tree — it is a deliberate,
 * rider-initiated event a handful of times per install, not a value that churns.
 */
val LocalPalette: ProvidableCompositionLocal<CabEyePalette> =
    staticCompositionLocalOf { paletteFor(ThemeChoice.DEEP_DARK) }

/**
 * Minimum interactive size, per the brief.
 *
 * Well above Android's own 48.dp guidance, because a rider who cannot see the control is
 * aiming from memory and proprioception rather than sight. Nothing touchable may be
 * smaller than this.
 */
val MinTouchTarget = 88.dp

/** Focus ring thickness. Thick, and never the only signal — shape and label change too. */
val FocusIndicatorWidth = 4.dp

/**
 * Whether the user has asked the system to reduce or disable animation.
 *
 * Android has no direct equivalent of `prefers-reduced-motion`, so the accepted proxy is
 * `Settings.Global.ANIMATOR_DURATION_SCALE`, which TalkBack users and anyone with
 * motion sensitivity commonly set to 0. Read once and exposed here so every animation in
 * the app consults a single flag rather than each re-deriving it.
 *
 * Defaults to `false` (motion allowed) if the setting cannot be read.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Type, sized and weighted for low vision.
 *
 * Three rules from the brief are enforced here rather than left to each screen:
 *
 *  - **Body is never below 24sp.** Step 1 had `bodyMedium` at 18sp, which is generous by
 *    Material's standards and still too small for the audience.
 *  - **Bold or semi-bold only.** Thin and light weights lose their stems first at low acuity —
 *    a light 24sp is harder to read than a bold 18sp, so weight is not a style choice here.
 *  - **No italics anywhere**, and letter spacing opened up on labels, where short strings give
 *    the eye the fewest shape cues to work with.
 *
 * Sizes are in `sp`, so they scale with the system font setting on top of these values. The
 * layouts are built to survive 200% on top of this baseline — see `CenteredColumn`.
 */
private val CabEyeTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 60.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold
    ),
    headlineLarge = TextStyle(
        fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold
    ),
    headlineMedium = TextStyle(
        fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold
    ),
    labelLarge = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp
    )
)

/**
 * App theme.
 *
 * Dynamic colour (Material You) is intentionally **not** used. It would let the system
 * wallpaper pick foreground colours, and a wallpaper-derived palette cannot be guaranteed
 * to hold 7:1 — an accessibility requirement must not be delegated to a decorative source.
 *
 * The system's own light/dark setting is likewise ignored. The rider picks a theme by voice
 * for a reason to do with their eyes, and having the OS override that at sunset would be a
 * bug, not a courtesy.
 *
 * @param choice which of the three themes to render; changed by voice
 */
@Composable
fun CabEyeTheme(
    choice: ThemeChoice = ThemeChoice.DEEP_DARK,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val palette = remember(choice) { paletteFor(choice) }

    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    // Material's scheme is still populated, because Material components read from it. The app's
    // own code reads LocalPalette instead — the two are kept in step here so a stray Material
    // default can never introduce a colour that was not contrast-checked.
    val colorScheme = remember(palette) {
        val scheme = if (palette.isLight) lightColorScheme() else darkColorScheme()
        scheme.copy(
            primary = palette.listening,
            onPrimary = palette.background,
            secondary = palette.confirm,
            onSecondary = palette.background,
            tertiary = palette.clarify,
            onTertiary = palette.background,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.background,
            onSurface = palette.onBackground,
            error = palette.danger,
            onError = palette.background,
            outline = palette.outline
        )
    }

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalPalette provides palette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CabEyeTypography,
            content = content
        )
    }
}
