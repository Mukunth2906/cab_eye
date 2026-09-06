package com.cabeye.rider.places

/**
 * The cities this build knows about, each owning its own place list.
 *
 * ## Why the gazetteer is city-scoped rather than one flat list
 * Step 1 shipped a flat Chennai-only list with the city left implicit, and that implicitness
 * was the whole of bug A2: a rider asking for Peelamedu got "Sorry, I didn't catch a place I
 * know" — a sentence that is simply false. The app *did* catch the place; it has no idea about
 * Coimbatore. To someone who cannot see the screen those two failures are indistinguishable,
 * and they need completely different responses from the rider: try again more clearly, versus
 * this app cannot help you here.
 *
 * Making the city explicit lets the recovery ladder say the true thing —
 * *"Peelamedu is in Coimbatore. I'm set to Chennai. Say 'switch to Coimbatore'."* — which is
 * both an accurate diagnosis and a route forward, rather than an apology that hides which
 * failure happened.
 *
 * ## Why these coordinates matter
 * They are not decoration. Every pair of coordinates feeds `DIVERGENCE_KM` in the ambiguity
 * budget, which decides whether the app interrupts the rider to ask a question. A sloppy
 * coordinate does not produce a slightly-off map pin; it produces a question that should not
 * have been asked, or worse, a silent booking that should have been questioned. The pairs the
 * gate depends on are pinned by unit test in `VoiceLogicTest`.
 */
enum class City(
    /** How the narrator says it, and how the rider says it back to switch. */
    val displayName: String,

    /** Extra forms a recogniser produces for the city name itself, for "switch to ..." . */
    val spokenAliases: List<String>,

    /**
     * The two places the recovery ladder offers as examples.
     *
     * Named explicitly rather than taken from the top of [places], because the first entries
     * happen to be the deliberately ambiguous pairs — offering a rider "try Anna Nagar East or
     * Anna Nagar West" after a failure would steer them straight into the one query guaranteed
     * to trigger a clarification question. The examples should be the *easiest* places to say,
     * not the most interesting ones.
     */
    val examples: List<String>,

    val places: List<Place>
) {

    CHENNAI(
        displayName = "Chennai",
        spokenAliases = listOf("chennai", "madras"),
        examples = listOf("Adyar", "T Nagar"),
        places = listOf(
            Place("Anna Nagar East", 13.0850, 80.2101, listOf("anna nagar east", "annanagar east")),

            // Anna Nagar West sits at the depot/extension side of the neighbourhood. This
            // coordinate was corrected in step 2: the earlier one put East and West 1.27 km
            // apart, just under DIVERGE, so the gate stayed silent on a bare "anna nagar" and
            // picked one at random. The real separation is ~2.0 km, which is far enough that
            // being wrong costs the rider a genuine detour — so the gate should, and now does,
            // ask. `VoiceLogicTest` asserts the distance so a future edit cannot quietly move
            // it back under the line.
            Place("Anna Nagar West", 13.0865, 80.1918, listOf("anna nagar west", "annanagar west")),

            // Anna Salai (Mount Road) shares the "Anna" token with Anna Nagar but is ~6 km
            // away. A bare "anna" is genuinely ambiguous between them, and that is the case
            // the clarification gate exists for.
            Place("Anna Salai", 13.0604, 80.2626, listOf("anna salai", "mount road")),
            Place("T Nagar", 13.0418, 80.2341, listOf("t nagar", "tee nagar", "thyagaraya nagar", "tnagar")),
            Place("Adyar", 13.0012, 80.2565, listOf("adyar", "adaiyar")),
            Place("Velachery", 12.9791, 80.2210, listOf("velachery", "velacheri", "vellachery")),
            Place("Guindy", 13.0067, 80.2206, listOf("guindy", "gindy")),
            Place("Mylapore", 13.0339, 80.2698, listOf("mylapore", "mylapur")),
            Place("Egmore", 13.0732, 80.2609, listOf("egmore", "eghmore")),
            Place("Chennai Central", 13.0827, 80.2757, listOf("central", "central station", "chennai central")),
            Place("Chennai Airport", 12.9941, 80.1709, listOf("airport","chennai airport")),
            Place("Tambaram", 12.9249, 80.1000, listOf("tambaram", "thambaram")),
            Place("Porur", 13.0374, 80.1575, listOf("porur", "porrur")),
            Place("Thoraipakkam", 12.9430, 80.2340, listOf("thoraipakkam", "omr", "o m r")),
            Place("Besant Nagar", 13.0002, 80.2668, listOf("besant nagar", "bessy", "elliots beach")),
            Place("Vadapalani", 13.0503, 80.2121, listOf("vadapalani", "vadapalni")),
            Place("Nungambakkam", 13.0569, 80.2425, listOf("nungambakkam", "nungambakam"))
        )
    ),

    /**
     * Coimbatore — the default, because this is where the app is being demonstrated.
     *
     * Aliases here lean on what on-device English recognition actually returns for Tamil place
     * names, which is rarely the correct spelling: "peelamedu" comes back as "pillamedu" or
     * "peela medu", "ukkadam" as "ukadam", "gandhipuram" as "gandipuram". A gazetteer that only
     * matched the correct spelling would fail on most real utterances, and the failure would
     * look like the rider's fault rather than the recogniser's.
     */
    COIMBATORE(
        displayName = "Coimbatore",
        spokenAliases = listOf("coimbatore", "kovai", "covai"),
        examples = listOf("Peelamedu", "Gandhipuram"),
        places = listOf(
            Place("Peelamedu", 11.0299, 77.0266, listOf("peelamedu", "pillamedu", "peela medu", "pilamedu")),

            // RS Puram and Gandhipuram are Coimbatore's counterpart to the Anna Nagar pair:
            // a bare "puram" scores 0.65 against one and 0.58 against the other — a gap of
            // 0.07, well under DELTA — and they are ~2.2 km apart, over DIVERGE. That is the
            // exact shape the gate exists for, and it is pinned by test.
            Place("RS Puram", 11.0068, 76.9490, listOf("rs puram", "r s puram", "ras puram", "race puram")),
            Place("Gandhipuram", 11.0177, 76.9660, listOf("gandhipuram", "gandipuram", "gandhi puram")),

            Place("Race Course", 10.9975, 76.9705, listOf("race course", "racecourse", "race course road")),
            Place("Singanallur", 11.0060, 77.0290, listOf("singanallur", "singanallore", "singa nallur")),
            Place("Saibaba Colony", 11.0244, 76.9450, listOf("saibaba colony", "sai baba colony", "saibaba kovil")),
            Place("Ukkadam", 10.9860, 76.9600, listOf("ukkadam", "ukadam", "ukkadum")),
            Place("Hopes College", 11.0245, 77.0070, listOf("hopes college", "hope college", "hopes")),
            Place("Ganapathy", 11.0403, 76.9800, listOf("ganapathy", "ganapathi", "ganpathy")),
            Place("Vadavalli", 11.0210, 76.8930, listOf("vadavalli", "vadavalli road", "vadavali")),
            Place("Ondipudur", 10.9930, 77.0450, listOf("ondipudur", "ondi pudur", "ondipudhur")),
            Place("Sundarapuram", 10.9450, 76.9600, listOf("sundarapuram", "sundara puram")),
            Place("Kuniamuthur", 10.9500, 76.9350, listOf("kuniamuthur", "kuniyamuthur", "kunia muthur")),
            Place("Town Hall", 10.9975, 76.9600, listOf("town hall", "townhall")),
            Place(
                "Coimbatore Junction", 10.9960, 76.9670,
                listOf("junction", "railway station", "coimbatore junction", "cbe junction")
            ),
            Place(
                "Coimbatore Airport", 11.0297, 77.0434,
                listOf("airport","coimbatore airport")
            )
        )
    );

    companion object {

        /**
         * Resolves a spoken city name, e.g. from "switch to Coimbatore".
         *
         * Substring rather than equality because the recogniser routinely appends or prepends
         * debris, and a rider who says "switch to Kovai please" should not be told the city
         * does not exist.
         */
        fun fromSpoken(text: String): City? {
            val lower = text.lowercase()
            return entries.firstOrNull { city ->
                city.spokenAliases.any { alias -> lower.contains(alias) }
            }
        }
    }
}
