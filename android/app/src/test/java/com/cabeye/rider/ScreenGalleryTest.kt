package com.cabeye.rider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabeye.rider.auth.SignInStep
import com.cabeye.rider.auth.SignInUi
import com.cabeye.rider.demo.DemoData
import com.cabeye.rider.explore.ExploreMode
import com.cabeye.rider.explore.ExploreUi
import com.cabeye.rider.memory.VisitedPlace
import com.cabeye.rider.mindmap.MindMapBuilder
import com.cabeye.rider.mindmap.MindMapCatalog
import com.cabeye.rider.net.StopInfo
import com.cabeye.rider.state.CameraShare
import com.cabeye.rider.state.DriverInfo
import com.cabeye.rider.state.FeedbackStep
import com.cabeye.rider.state.NextStep
import com.cabeye.rider.state.PaymentPhase
import com.cabeye.rider.state.PaymentUi
import com.cabeye.rider.state.PlaceOption
import com.cabeye.rider.state.RideType
import com.cabeye.rider.state.RiderState
import com.cabeye.rider.state.RiderUiState
import com.cabeye.rider.state.ThemeChoice
import com.cabeye.rider.ui.ExploreActions
import com.cabeye.rider.ui.MindMapActions
import com.cabeye.rider.ui.RiderSignInSurface
import com.cabeye.rider.ui.RiderSurface
import com.cabeye.rider.ui.theme.CabEyeTheme
import com.cabeye.rider.ui.theme.LocalPalette
import com.cabeye.rider.vision.Box as SceneBox
import com.cabeye.rider.vision.SceneObject
import com.cabeye.rider.walk.WalkProfile
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every rider screen, in every theme, to PNG files: a way to check what the app looks
 * like without a phone. Skipped unless asked for:
 *
 *     gradlew :app:testDebugUnitTest --tests "*ScreenGalleryTest" -Pscreenshots
 *
 * Output: `app/build/screenshots/<theme>/<NN-screen>.png`, plus one contact sheet per theme.
 * Robolectric draws with Android's real graphics code, so layout, colour, clipping and text
 * wrapping are what a phone shows; emoji may come out as boxes (no emoji font on the PC).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenGalleryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("cabeye.screenshots.dir") ?: "build/screenshots")

    private val driver = DriverInfo("Karthik", "Yellow Bajaj RE", "TN 37 BX 4412", "9000022222", 4.8f)
    private val psg = PlaceOption("PSG College of Technology", 11.0248, 77.0029, 0.92f, formattedAddress = "Peelamedu, Coimbatore")
    private val psgHospital = PlaceOption("PSG Hospitals", 11.0222, 77.0089, 0.88f, formattedAddress = "Peelamedu, Coimbatore")
    private val stops = listOf(
        StopInfo("s1", 1, "WAIT", "DONE", "Apollo Pharmacy"),
        StopInfo("s2", 2, "DROP", "PENDING", "Gandhipuram", note = "my friend")
    )

    private class Shot(val name: String, val content: @Composable () -> Unit)

    private fun rider(
        state: RiderUiState,
        explore: ExploreUi = ExploreUi(),
        mindMapOpen: Boolean = false
    ): @Composable () -> Unit = {
        RiderSurface(
            uiState = state,
            onHoldStart = {}, onHoldEnd = {}, onCancel = {}, onClarifyChoice = {}, onNearMissAnswer = {},
            onCodeConfirmed = {}, onSos = {}, onDismissSos = {}, onPaymentResult = {}, onPay = {},
            onPaymentMethod = {}, onDeclinePayment = {}, onOpenSettings = {},
            explore = explore,
            mindMap = mindMapActions,
            exploreActions = exploreActions,
            mindMapOpen = mindMapOpen
        )
    }

    private val base = RiderUiState(connected = true, activeCityName = "Coimbatore")

    private val catalog: MindMapCatalog.Catalog by lazy {
        val now = 1_790_000_000_000L
        val c = DemoData.build(now)
        val places = listOf(
            VisitedPlace(DemoData.KGH, "KG Hospital", latitude = 11.0005, longitude = 76.97164, placeId = DemoData.KGH, visitCount = 9, aliases = listOf("hospital")),
            VisitedPlace(DemoData.PSG, "PSG College of Technology", latitude = 11.02481, longitude = 77.00292, placeId = DemoData.PSG, visitCount = 21)
        )
        MindMapCatalog.build(MindMapBuilder.build(c.authored, c.routes, places), places, emptyList(), c.routes, WalkProfile(), 11.0080 to 76.9502, now)
    }

    private val mindMapActions = object : MindMapActions {
        override fun catalog() = catalog
        override fun answer() = ""
        override fun load() {}
        override fun book(card: MindMapCatalog.PlaceCard) = false
        override fun explain(nodeId: String) {}
        override fun forget(nodeId: String) {}
        override fun guide(routeId: String) = false
        override fun readWalk(routeId: String) {}
        override fun forgetWalk(routeId: String) {}
        override fun bookRoute(name: String) = false
    }

    private val exploreActions = object : ExploreActions {
        override fun open() {}
        override fun describe() {}
        override fun read() {}
        override fun keepTelling() {}
        override fun find(target: String) {}
        override fun stop() {}
        override fun name(name: String) {}
        override fun askName() {}
        override fun finish(save: Boolean) {}
    }

    private fun shots(): List<Shot> = listOf(
        Shot("01-idle", rider(base)),
        Shot("02-listening", rider(base.copy(ride = RiderState.Listening("take me to PSG"), micOpen = true))),
        Shot("03-hold-to-answer", rider(base.copy(ride = RiderState.Listening(isFollowUp = true), holdToAnswer = true))),
        Shot("04-resolving", rider(base.copy(ride = RiderState.Resolving("take me to PSG Tech")))),
        Shot("05-clarify", rider(base.copy(ride = RiderState.Clarify(psg, psgHospital, 0.04f, 1.8f)))),
        Shot("06-near-miss", rider(base.copy(ride = RiderState.Clarify(psg, psgHospital, 0.3f, 0f, isNearMiss = true)))),
        Shot("07-confirming", rider(base.copy(ride = RiderState.Confirming("PSG College of Technology", RideType.AUTO, 3_000)))),
        Shot("08-finding", rider(base.copy(ride = RiderState.Finding(elapsedMillis = 47_000)))),
        Shot("09-assigned", rider(base.copy(ride = RiderState.Assigned(driver, 4)))),
        Shot("10-approaching-unknown", rider(base.copy(ride = RiderState.Approaching(driver, null, null)))),
        Shot("11-approaching-240m", rider(base.copy(ride = RiderState.Approaching(driver, 240, 80f)))),
        Shot("12-camera-question", rider(base.copy(ride = RiderState.Assigned(driver, 3), camera = CameraShare.ASKING, cameraFrom = "Karthik"))),
        Shot("13-arrived", rider(base.copy(ride = RiderState.Arrived(driver, "472")))),
        Shot("14-arrived-verified", rider(base.copy(ride = RiderState.Arrived(driver, "472", codeVerified = true)))),
        Shot("15-arrived-wrong-code", rider(base.copy(ride = RiderState.Arrived(driver, "472", verificationFailed = true)))),
        Shot("16-in-trip", rider(base.copy(ride = RiderState.InTrip("PSG College of Technology", 14, driver, stops)))),
        Shot("17-done-pay", rider(base.copy(
            ride = RiderState.Done("PSG College of Technology", 124, 18, 5600),
            payment = PaymentUi(PaymentPhase.AWAITING, 124)
        ))),
        Shot("18-feedback", rider(base.copy(ride = RiderState.Feedback("Karthik", FeedbackStep.RATING)))),
        Shot("19-next-journey", rider(base.copy(ride = RiderState.NextJourney(NextStep.CHOOSE)))),
        Shot("20-planning", rider(base.copy(ride = RiderState.Planning(
            listOf("Stop 1: Apollo Pharmacy, the driver waits", "Stop 2: Gandhipuram, dropping my friend", "Then: PSG College of Technology"),
            "Say yes to book, or change a stop."
        )))),
        Shot("21-walking", rider(base.copy(ride = RiderState.Walking(
            "Say next at each turn.", listOf("▶ 1. about 20 m · right at the kerb", "2. about 35 m · glass door on your left"), recording = false, guiding = true
        )))),
        Shot("22-sos", rider(base.copy(sosActive = true))),
        Shot("23-explore", rider(base, explore = ExploreUi(
            open = true, mode = ExploreMode.CONTINUOUS,
            live = listOf(
                SceneObject("door", SceneBox(0.55f, 0.2f, 0.85f, 0.75f), "1 o'clock", 3.0),
                SceneObject("steps", SceneBox(0.1f, 0.7f, 0.6f, 0.95f), "11 o'clock", 1.5)
            ),
            lastSaid = "Steps going down, ahead. A door at 1 o'clock, about 3 metres.", seen = 7,
            recordingPath = true
        ))),
        Shot("24-mind-map", rider(base, mindMapOpen = true)),
        Shot("25-offline", rider(RiderUiState(connected = false, activeCityName = "Coimbatore"))),
        Shot("26-sign-in", {
            RiderSignInSurface(
                ui = SignInUi(step = SignInStep.ASK_PHONE, prompt = "Say your ten digit number.", phoneSoFar = "98765", heard = "nine eight seven six five"),
                onHoldStart = {}, onHoldEnd = {}, onTyped = {}, onSkip = {}
            )
        })
    )

    @Test
    fun renderEveryScreen() {
        assumeTrue("run with -Pscreenshots", System.getProperty("cabeye.screenshots") == "true")

        val all = shots()
        var theme by mutableStateOf(ThemeChoice.DEEP_DARK)
        var current by mutableStateOf(all.first())

        compose.setContent {
            CabEyeTheme(choice = theme) {
                Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
                    // Keyed so each screen starts from fresh remembered state.
                    androidx.compose.runtime.key(theme, current.name) { current.content() }
                }
            }
        }

        for (t in ThemeChoice.entries) {
            val dir = File(outDir, t.name.lowercase()).apply { mkdirs() }
            val images = mutableListOf<Pair<String, Bitmap>>()
            for (shot in all) {
                theme = t
                current = shot
                compose.waitForIdle()
                val bitmap = capture()
                File(dir, "${shot.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                images += shot.name to bitmap
            }
            contactSheets(images, dir)
        }
    }

    /**
     * The window, drawn into a bitmap. (The test library's own capture waits for a frame
     * callback that Robolectric never sends.)
     */
    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Six screens a sheet, half size, labelled: quick to look through. */
    private fun contactSheets(images: List<Pair<String, Bitmap>>, dir: File) {
        images.chunked(6).forEachIndexed { i, page ->
            val w = page.first().second.width / 2
            val h = page.first().second.height / 2
            val label = 36
            val sheet = Bitmap.createBitmap(w * 3, (h + label) * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(60, 60, 60))
            val paint = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true }
            page.forEachIndexed { j, (name, bmp) ->
                val x = (j % 3) * w
                val y = (j / 3) * (h + label)
                canvas.drawText(name, x + 8f, y + 28f, paint)
                canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, w, h, true), x.toFloat(), (y + label).toFloat(), null)
            }
            File(dir, "sheet-${i + 1}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
