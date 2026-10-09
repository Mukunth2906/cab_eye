package com.cabeye.rider.walk

/**
 * The rider's words for a place on the walk → a landmark, a turn, or a refusal.
 *
 * Only cues a blind traveller can sense are kept: something underfoot, something to touch,
 * something heard or smelt. "The red sign" is refused with a reason, because a route that
 * depends on it fails the person it is for. Colours are stripped from cues that are otherwise
 * tactile ("the red door" → "door").
 *
 * Rule-based on purpose, like the rest of the memory agent: every decision is explainable and
 * a JVM test.
 */
object LandmarkParser {

    sealed interface Result {
        data class Found(
            val kind: LandmarkKind,
            val text: String,
            val side: Side,
            /** Said as the cue for a turn: "the bakery smell is the cue for the left turn". */
            val cueFor: TurnDirection? = null
        ) : Result

        /** "turning left here" — a turn, not a landmark. */
        data class TurnHere(val direction: TurnDirection) : Result

        /** Only visual: a sign, a colour, a poster. Not stored. */
        data class VisualOnly(val text: String) : Result
    }

    // Order matters: the first matching kind wins ("tactile tiles" is tactile, not texture).
    private val KINDS: List<Pair<LandmarkKind, Regex>> = listOf(
        LandmarkKind.SMELL to Regex("\\b(smell|smells|smelly|scent|aroma|fragrance|perfume|stink)\\b"),
        LandmarkKind.SOUND to Regex("\\b(sound|sounds|noise|noisy|hear|hum|humming|music|bell|bells|fountain|horn|beep|beeping|chirping|birds|generator|announcement|loudspeaker)\\b"),
        LandmarkKind.TACTILE to Regex("\\b(tactile|bumpy tiles|raised tiles|guiding tiles|guide tiles|warning tiles|dotted tiles)\\b"),
        LandmarkKind.CROSSING to Regex("\\b(crossing|zebra|pedestrian signal|beeping signal|signal crossing)\\b"),
        LandmarkKind.KERB to Regex("\\b(kerb|curb|kerbs|curbs|footpath ends|pavement ends|edge of the road|step down to the road)\\b"),
        LandmarkKind.STEPS to Regex("\\b(steps|step|stairs|staircase|stair)\\b"),
        LandmarkKind.RAMP to Regex("\\b(ramp|slope|incline|slopes)\\b"),
        LandmarkKind.DOOR to Regex("\\b(door|doors|doorway|entrance|entry|shutter|glass door|revolving door)\\b"),
        LandmarkKind.GATE to Regex("\\b(gate|gates|turnstile)\\b"),
        LandmarkKind.RAILING to Regex("\\b(railing|railings|handrail|hand rail|rail|fence|grill|grille|barrier)\\b"),
        LandmarkKind.POLE to Regex("\\b(pole|poles|pillar|pillars|post|lamp post|bollard|bollards)\\b"),
        LandmarkKind.WALL to Regex("\\b(wall|walls|compound wall)\\b"),
        LandmarkKind.TEXTURE to Regex("\\b(tiles|tiled|gravel|grass|sand|carpet|mat|mud|muddy|uneven|rough|smooth|cobbled|cobblestones|floor changes|ground changes|marble|cement|concrete|wooden floor|drain cover|manhole|speed breaker|pothole)\\b"),
        LandmarkKind.OTHER to Regex("\\b(tree|bench|counter|shop front|corner|pillar box|water tap|tap|bin|dustbin|parked)\\b")
    )

    private val VISUAL = Regex(
        "\\b(sign|signs|signboard|board|billboard|hoarding|poster|posters|banner|logo|written|writing|" +
            "display|screen|picture|painting|painted|colou?r|colou?red|red|blue|green|yellow|white|black|orange|" +
            "pink|purple|grey|gray|brown|bright|lit|neon|look|looks|see|visible)\\b"
    )

    private val COLOURS = Regex(
        "\\b(red|blue|green|yellow|white|black|orange|pink|purple|grey|gray|brown|colou?red|painted|bright)\\b"
    )

    private val CUE = Regex(
        "(?:is |as |that'?s )?(?:the |my )?(?:cue|clue|sign|signal|marker|landmark|hint|point)\\s+(?:for|of|before|to)\\s+" +
            "(?:the |my |a )?(slight left|slight right|left|right)(?: turn)?|" +
            "(?:where|when)\\s+(?:i|you|we)\\s+(?:should\\s+|have to\\s+|need to\\s+)?turn\\s+(left|right)|" +
            "(?:then\\s+)?turn\\s+(left|right)\\s+(?:at|after|by|when|there)"
    )

    private val SIDE_LEFT = Regex("\\b(on|to|at)\\s+(my|the|your)\\s+left(\\s+(side|hand))?\\b|\\bleft\\s+(side|hand side)\\b|\\bon\\s+left\\b")
    private val SIDE_RIGHT = Regex("\\b(on|to|at)\\s+(my|the|your)\\s+right(\\s+(side|hand))?\\b|\\bright\\s+(side|hand side)\\b|\\bon\\s+right\\b")
    private val SIDE_AHEAD = Regex("\\b(straight ahead|ahead|in front( of me| of you)?|in the front)\\b")

    private val TURN_ONLY = Regex(
        "^(?:i'?m\\s+|i am\\s+|now\\s+|ok(?:ay)?\\s+)?(?:turning|turn|going|go|taking a|take a)\\s+" +
            "(slight left|slight right|left|right|around)(?:\\s+turn)?(?:\\s+(?:here|now|now here))?$|" +
            "^(left|right)\\s+turn(?:\\s+here)?$|^(?:a\\s+)?u[ -]?turn(?:\\s+here)?$|^bear\\s+(left|right)(?:\\s+here)?$"
    )

    private val LEAD = Regex(
        "^(?:(?:okay|ok|so|now|and|mark|landmark|note|remember)\\s+)*" +
            "(?:there(?:'s| is| are)|here(?:'s| is)|this is|it'?s|i can (?:smell|hear|feel)|i (?:smell|hear|feel)|you can (?:smell|hear|feel)|i pass|i'm passing|passing)?\\s*"
    )

    fun parse(spoken: String): Result? {
        val raw = spoken.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (raw.isBlank()) return null

        TURN_ONLY.find(raw)?.let { m ->
            val word = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "around"
            // "bear left" is a slight turn, said the way O&M instructors say it.
            return Result.TurnHere(turnWord(if (raw.startsWith("bear ")) "slight $word" else word))
        }

        var text = raw
        var cue: TurnDirection? = null
        CUE.find(text)?.let { m ->
            val word = m.groupValues.drop(1).first { it.isNotEmpty() }
            cue = turnWord(word)
            text = text.removeRange(m.range)
        }

        var side = Side.NONE
        for ((s, re) in listOf(Side.LEFT to SIDE_LEFT, Side.RIGHT to SIDE_RIGHT, Side.AHEAD to SIDE_AHEAD)) {
            val m = re.find(text) ?: continue
            side = s
            text = text.removeRange(m.range)
            break
        }

        val kind = KINDS.firstOrNull { it.second.containsMatchIn(text) }?.first
        if (kind == null) {
            return if (VISUAL.containsMatchIn(text)) Result.VisualOnly(clean(text)) else null
        }

        text = COLOURS.replace(text, " ")
        var cleaned = clean(text)
        if (cleaned.isBlank()) return null
        // "I can smell the bakery" → "bakery smell": the sense stays in the words read back.
        if (kind == LandmarkKind.SMELL && !KINDS[0].second.containsMatchIn(cleaned)) cleaned = "$cleaned smell"
        if (kind == LandmarkKind.SOUND && !KINDS[1].second.containsMatchIn(cleaned)) cleaned = "$cleaned sound"
        return Result.Found(kind, cleaned, side, cue)
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\s+"), " ").trim()
        .replace(LEAD, "")
        .replace(Regex("\\b(here|right here|over here|just here|now)$"), "")
        .replace(Regex("^(the|a|an|some)\\s+"), "")
        .replace(Regex("\\s+(is|are)$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun turnWord(w: String): TurnDirection = when (w.trim()) {
        "left" -> TurnDirection.LEFT
        "right" -> TurnDirection.RIGHT
        "slight left" -> TurnDirection.SLIGHT_LEFT
        "slight right" -> TurnDirection.SLIGHT_RIGHT
        else -> TurnDirection.AROUND
    }
}
