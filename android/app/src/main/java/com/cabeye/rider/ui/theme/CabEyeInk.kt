package com.cabeye.rider.ui.theme

import kotlin.math.pow

/**
 * Every colour in the app, as raw ARGB, plus the maths that proves they are legible.
 *
 * ## Why the values live here as `Long` and not as Compose `Color`
 * So that the contrast floor is a **unit test**, not a comment. A comment saying "14.9:1" is a
 * claim someone made once; `assertTrue(ratio >= 7.0)` running on every build is a fact. Keeping
 * the raw values in a file with no Compose dependency means the test needs no Android runtime,
 * so there is no excuse not to run it.
 *
 * ## Who the visual layer is actually for
 * Not "blind users can't see it, so the colours don't matter." Most people with a vision
 * impairment have **some** usable sight, and they are precisely the people who will look at
 * this screen. So these are chosen for low vision specifically, rather than for a sighted
 * designer's idea of what an accessible theme looks like:
 *
 *  - **True black, never dark grey.** Dark grey backgrounds cause halation — the light-bleed
 *    that makes bright text smear and bloom for people with cataracts, corneal scarring, or
 *    posterior capsule opacification. `#121212` is a Material convention, not an accessibility
 *    one, and for this audience it is actively worse than `#000000`.
 *  - **Every pair far above the bar.** WCAG AAA is 7:1; the primary pairs here run 14:1 to
 *    19.8:1. AAA is a floor for the general population, not a target for an app whose users
 *    were selected for having difficulty seeing.
 */
object CabEyeInk {

    // =================================================================================
    //  Deep dark — the default
    //  OLED-friendly, least glare, and the lowest-halation option available.
    // =================================================================================

    const val DD_BACKGROUND = 0xFF000000   // true black
    const val DD_ON_BACKGROUND = 0xFFF5F9F8 // 19.79:1 — near-white, very slightly cooled
    const val DD_MUTED = 0xFFB9BFC7        // 11.34:1
    const val DD_LISTENING = 0xFF4FC3F7    // 10.48:1
    const val DD_CONFIRM = 0xFF69F0AE      // 14.68:1
    const val DD_CLARIFY = 0xFFFFD54F      // 14.88:1
    const val DD_DANGER = 0xFFFF8A80       //  9.20:1 — deliberately not #FF0000, which is 5.25:1 and fails AAA outright
    const val DD_OUTLINE = 0xFF3A3A44      // hairlines only, non-informational

    // =================================================================================
    //  High-contrast yellow — the classic low-vision pairing
    //
    //  Yellow on black is the pairing most commonly prescribed in low-vision clinics, and for
    //  many users with macular degeneration or retinitis pigmentosa it is legible at sizes and
    //  distances where white on black is not. It is offered as a peer of the default, not as a
    //  novelty: for some riders this is the only theme that works.
    //
    //  Note that in this theme most accents are the SAME yellow. That is intentional and it is
    //  only safe because no state in this app is signalled by colour alone — every one also
    //  carries an icon, a word, and a sound. Inventing four distinguishable hues here would
    //  mean four compromised hues, which is the wrong trade for the people who choose it.
    // =================================================================================

    const val HCY_BACKGROUND = 0xFF000000
    const val HCY_ON_BACKGROUND = 0xFFFFD400 // 14.67:1 — the signature yellow
    const val HCY_MUTED = 0xFFE0B400         // 10.70:1 — a dimmer yellow, still far above AAA
    const val HCY_LISTENING = 0xFFFFD400     // 14.67:1
    const val HCY_CONFIRM = 0xFFFFD400       // 14.67:1
    const val HCY_CLARIFY = 0xFFFFFFFF       // 21.00:1 — the one deliberate second value
    const val HCY_DANGER = 0xFFFF8A80        //  9.20:1 — distinct from yellow at a glance
    const val HCY_OUTLINE = 0xFF6B5A00

    // =================================================================================
    //  High-contrast light — for users who need maximum brightness
    //
    //  Some conditions, and plenty of ordinary outdoor sunlight, make a dark theme unreadable.
    //  Every accent here is a DARK, saturated tone on white, because on a light ground the
    //  bright accents that work on black would collapse to 2:1 or worse.
    // =================================================================================

    const val HCL_BACKGROUND = 0xFFFFFFFF
    const val HCL_ON_BACKGROUND = 0xFF0A0C0D // 19.60:1 — near-black, avoids pure-black smear
    const val HCL_MUTED = 0xFF3A4046         // 10.49:1
    const val HCL_LISTENING = 0xFF0B4A7A     //  9.23:1 — deep blue
    const val HCL_CONFIRM = 0xFF0A5A2A       //  8.37:1 — deep green
    const val HCL_CLARIFY = 0xFF6B4000       //  8.90:1 — deep amber
    const val HCL_DANGER = 0xFFA00000        //  8.42:1 — deep red
    const val HCL_OUTLINE = 0xFF9AA0A6

    /** The WCAG AAA floor for normal text. Every pair below must clear it. */
    const val AAA_FLOOR = 7.0

    /**
     * Every foreground/ground pair that carries information, for the test to sweep.
     *
     * Outline colours are excluded on purpose: they draw hairlines and dividers that convey
     * nothing on their own. Anything that can hold a word or an icon is in this list.
     */
    val INFORMATIONAL_PAIRS: List<InkPair> = listOf(
        InkPair("deep-dark/on-background", DD_ON_BACKGROUND, DD_BACKGROUND),
        InkPair("deep-dark/muted", DD_MUTED, DD_BACKGROUND),
        InkPair("deep-dark/listening", DD_LISTENING, DD_BACKGROUND),
        InkPair("deep-dark/confirm", DD_CONFIRM, DD_BACKGROUND),
        InkPair("deep-dark/clarify", DD_CLARIFY, DD_BACKGROUND),
        InkPair("deep-dark/danger", DD_DANGER, DD_BACKGROUND),

        InkPair("hc-yellow/on-background", HCY_ON_BACKGROUND, HCY_BACKGROUND),
        InkPair("hc-yellow/muted", HCY_MUTED, HCY_BACKGROUND),
        InkPair("hc-yellow/listening", HCY_LISTENING, HCY_BACKGROUND),
        InkPair("hc-yellow/confirm", HCY_CONFIRM, HCY_BACKGROUND),
        InkPair("hc-yellow/clarify", HCY_CLARIFY, HCY_BACKGROUND),
        InkPair("hc-yellow/danger", HCY_DANGER, HCY_BACKGROUND),

        InkPair("hc-light/on-background", HCL_ON_BACKGROUND, HCL_BACKGROUND),
        InkPair("hc-light/muted", HCL_MUTED, HCL_BACKGROUND),
        InkPair("hc-light/listening", HCL_LISTENING, HCL_BACKGROUND),
        InkPair("hc-light/confirm", HCL_CONFIRM, HCL_BACKGROUND),
        InkPair("hc-light/clarify", HCL_CLARIFY, HCL_BACKGROUND),
        InkPair("hc-light/danger", HCL_DANGER, HCL_BACKGROUND)
    )

    /** One text-on-ground combination, named so a test failure says which one broke. */
    data class InkPair(val name: String, val foreground: Long, val background: Long)

    /**
     * WCAG 2.1 contrast ratio between two opaque ARGB colours, 1.0 to 21.0.
     *
     * Implemented from the specification rather than pulled from a library so it can be read
     * and checked here: linearise each channel, weight by luminance, then compare the lighter
     * against the darker with the 0.05 flare constant.
     */
    fun contrastRatio(foreground: Long, background: Long): Double {
        val lightest = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darkest = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lightest + 0.05) / (darkest + 0.05)
    }

    /** WCAG relative luminance, 0.0 (black) to 1.0 (white). */
    fun relativeLuminance(argb: Long): Double {
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(value: Long): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
