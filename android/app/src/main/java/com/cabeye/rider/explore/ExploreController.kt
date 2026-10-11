package com.cabeye.rider.explore

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cabeye.rider.audio.NarrationTier
import com.cabeye.rider.camera.CameraStreamer
import com.cabeye.rider.memory.TextSimilarity
import com.cabeye.rider.mindmap.Edge
import com.cabeye.rider.mindmap.EdgeKind
import com.cabeye.rider.mindmap.MindMapStore
import com.cabeye.rider.mindmap.Node
import com.cabeye.rider.mindmap.NodeKind
import com.cabeye.rider.vision.FrameTap
import com.cabeye.rider.vision.Scene
import com.cabeye.rider.vision.SceneDetector
import com.cabeye.rider.vision.SceneObject
import com.cabeye.rider.vision.SceneSource
import com.cabeye.rider.vision.SceneSpeech
import com.cabeye.rider.walk.LandmarkKind
import com.cabeye.rider.walk.RouteBuilder
import com.cabeye.rider.walk.Side
import com.cabeye.rider.walk.WalkEvent
import com.cabeye.rider.walk.WalkRecorder
import com.cabeye.rider.walk.WalkRecordingService
import com.cabeye.rider.walk.WalkRoute
import com.cabeye.rider.walk.WalkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the explore screen shows. */
data class ExploreUi(
    val open: Boolean = false,
    val mode: ExploreMode = ExploreMode.LOOKING,
    val findTarget: String = "",
    /** The last full description (boxes, text, what was said). */
    val scene: Scene? = null,
    /** What the fast detector sees right now, for live boxes. */
    val live: List<SceneObject> = emptyList(),
    val lastSaid: String = "",
    val busy: Boolean = false,
    val recordingPath: Boolean = false,
    val seen: Int = 0,
    val spots: List<String> = emptyList(),
    val startedAt: Long = 0
)

enum class ExploreMode { LOOKING, CONTINUOUS, FINDING }

/**
 * The explore module: no ride, just the rider, their phone's camera and their street.
 *
 * The rider walks around holding the phone at chest height. The camera's pictures go to the
 * detector ([SceneDetector]): on request a full description (Gemini, or the phone's own detector),
 * in "keep telling me" a fast on-phone check of every picture that says only what is new, and in
 * "find the door" a running answer of where the door is.
 *
 * Everything found goes into the explore part of the mind map ([MindMapStore.observe]): each thing
 * seen, where, which side, how many times — and named spots ("this is Ravi's tea shop"). The path
 * walked is recorded too (steps, compass, GPS, as for a recorded walk), with the things seen as its
 * landmarks, and saved as a walk at the end. Later, "what's around here" includes what the camera
 * saw, and "guide me" can follow the walk.
 */
class ExploreController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val camera: CameraStreamer,
    private val tap: FrameTap,
    private val detector: SceneDetector,
    private val mindMap: MindMapStore,
    private val walks: WalkStore,
    private val position: suspend () -> Pair<Double, Double>?,
    private val speak: (String, NarrationTier) -> Unit,
    private val isSpeaking: () -> Boolean,
    /** True while the ride module is sharing the camera with a driver: the camera then stays on. */
    private val cameraSharedWithDriver: () -> Boolean
) {
    var ui by mutableStateOf(ExploreUi())
        private set

    private var loop: Job? = null
    private val recent = LinkedHashMap<String, Long>()
    private val markedOnPath = mutableSetOf<String>()
    private var lastObservedAt = 0L

    // ---------------------------------------------------------------------------------
    //  Open and close
    // ---------------------------------------------------------------------------------

    /** Opens the camera and starts recording the path. The caller has checked camera permission. */
    fun open() {
        if (ui.open) return
        val now = System.currentTimeMillis()
        recent.clear()
        markedOnPath.clear()
        camera.pictureSink = tap.sink
        camera.start { reason ->
            Log.w(TAG, "camera failed: $reason")
            speak("The camera stopped working, so I've closed explore.", NarrationTier.INTERRUPT)
            close(save = false)
        }
        WalkRecorder.begin(now)
        val recording = runCatching { WalkRecordingService.start(app) }.getOrDefault(false)
        if (!recording) WalkRecorder.finish()
        if (recording && !walks.profile().consentGiven) walks.saveProfile(walks.profile().copy(consentGiven = true))
        ui = ExploreUi(open = true, recordingPath = recording, startedAt = now)
        speak(
            "Exploring. Hold the phone at chest height, pointing the way you face. " +
                "Tap Describe to hear what's in front of you, or Keep telling me while you walk slowly. " +
                "You can also find something, read writing, or name this place. Tap Save and finish when you're done.",
            NarrationTier.INTERRUPT
        )
    }

    /** Leaves explore; with [save], keeps the path as a walk and says what was learned. */
    fun close(save: Boolean = true) {
        if (!ui.open) return
        loop?.cancel()
        loop = null
        camera.pictureSink = null
        if (!cameraSharedWithDriver()) camera.stop()
        val events = if (ui.recordingPath) {
            runCatching { WalkRecordingService.stop(app) }
            WalkRecorder.finish()
        } else emptyList()
        val seen = ui.seen
        val spots = ui.spots
        ui = ExploreUi()

        if (!save) return
        val route = if (events.isNotEmpty()) saveWalk(events, spots) else null
        val parts = mutableListOf<String>()
        if (route != null) parts += "You walked about ${route.totalMetres.toInt()} metres; I've saved it as ${route.name}"
        if (seen > 0) parts += "I noted ${plural(seen, "thing")} the camera saw"
        if (spots.isNotEmpty()) parts += "and remembered ${spots.joinToString(" and ")}"
        speak(
            if (parts.isEmpty()) "Closed explore. Nothing new to keep this time."
            else parts.joinToString(", ") + ". Ask what's around here, or open My places, any time.",
            NarrationTier.QUEUED
        )
    }

    private fun saveWalk(events: List<WalkEvent>, spots: List<String>): WalkRoute? {
        val now = System.currentTimeMillis()
        val built = RouteBuilder.build(events, now, idPrefix = "lmx${now.toString(36)}")
        if (built.segments.isEmpty() || built.segments.sumOf { it.metres } < 5.0) return null
        val name = spots.lastOrNull()?.let { "the walk to $it" }
            ?: "explore walk, ${SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(now))}"
        val route = WalkRoute(
            id = "walk-x${now.toString(36)}", name = name, start = "where you started exploring",
            segments = built.segments, createdAt = now, updatedAt = now, lastWalkedAt = now, walks = 1,
            indoor = built.indoor
        )
        walks.save(route)
        return route
    }

    // ---------------------------------------------------------------------------------
    //  Looking
    // ---------------------------------------------------------------------------------

    /** One full description of what is in front of the rider (or, with [read], the writing). */
    fun describe(read: Boolean = false) {
        if (!ui.open) return
        stopLoop()
        ui = ui.copy(mode = ExploreMode.LOOKING, busy = true)
        speak(if (read) "Reading." else "Looking.", NarrationTier.INTERRUPT)
        scope.launch {
            val picture = tap.next() ?: run {
                ui = ui.copy(busy = false)
                speak("I couldn't get a picture from the camera. Try again.", NarrationTier.INTERRUPT)
                return@launch
            }
            val scene = runCatching {
                detector.describe(picture, if (read) SceneDetector.Mode.READ else SceneDetector.Mode.DESCRIBE)
            }.getOrElse { Scene(source = SceneSource.ON_DEVICE) }
            picture.recycle()
            val said = when {
                read && scene.text.isNotBlank() -> "It says: ${scene.text.take(400)}"
                read -> "I can't find any writing. Hold the phone steady, about an arm's length away."
                else -> SceneSpeech.describe(scene)
            }
            ui = ui.copy(busy = false, scene = scene, live = scene.objects, lastSaid = said)
            speak(said, NarrationTier.INTERRUPT)
            remember(scene)
        }
    }

    /** "Keep telling me": every picture checked on the phone, only what's new said. */
    fun continuous(on: Boolean = ui.mode != ExploreMode.CONTINUOUS) {
        if (!ui.open) return
        stopLoop()
        if (!on) {
            ui = ui.copy(mode = ExploreMode.LOOKING)
            speak("Stopped telling you.", NarrationTier.INTERRUPT)
            return
        }
        ui = ui.copy(mode = ExploreMode.CONTINUOUS)
        speak("I'll tell you what's new as you walk.", NarrationTier.INTERRUPT)
        loop = scope.launch {
            var n = 0
            while (isActive && ui.mode == ExploreMode.CONTINUOUS) {
                val picture = tap.next() ?: run { delay(500); null } ?: continue
                n++
                // Every few pictures, a full (Gemini) look when it's available: better names,
                // distances and hazards for the mind map. The rest stay on the phone.
                val full = n % 6 == 0 && detector.geminiAvailable != false
                val scene = runCatching { if (full) detector.describe(picture) else detector.quick(picture) }
                    .getOrElse { Scene() }
                picture.recycle()
                ui = ui.copy(live = scene.objects, scene = if (full) scene else ui.scene)
                forgetOld()
                val change = SceneSpeech.changes(scene, recent.keys)
                scene.objects.forEach { recent[it.label.lowercase(Locale.ROOT)] = System.currentTimeMillis() }
                if (change != null && !isSpeaking()) {
                    ui = ui.copy(lastSaid = change)
                    speak(change, NarrationTier.QUEUED)
                }
                remember(scene)
                delay(if (full) 400 else 700)
            }
        }
    }

    /** "Find the door": keeps looking and says where it is, and when it moves. */
    fun find(said: String) {
        if (!ui.open) return
        stopLoop()
        val target = SceneSpeech.targetOf(said)
        ui = ui.copy(mode = ExploreMode.FINDING, findTarget = target)
        speak("Looking for ${SceneSpeech.aOrAn(target)}. Turn slowly.", NarrationTier.INTERRUPT)
        loop = scope.launch {
            val started = System.currentTimeMillis()
            var lastAnswer = ""
            var n = 0
            while (isActive && ui.mode == ExploreMode.FINDING) {
                if (System.currentTimeMillis() - started > FIND_FOR_MS) {
                    speak("I've stopped looking for ${SceneSpeech.aOrAn(target)}.", NarrationTier.QUEUED)
                    ui = ui.copy(mode = ExploreMode.LOOKING)
                    break
                }
                val picture = tap.next() ?: continue
                n++
                val full = n % 5 == 0 && detector.geminiAvailable != false
                val scene = runCatching {
                    if (full) detector.describe(picture, SceneDetector.Mode.FIND, target) else detector.quick(picture)
                }.getOrElse { Scene() }
                picture.recycle()
                val answer = SceneSpeech.find(target, scene)
                val hit = !answer.startsWith("I can't see")
                ui = ui.copy(live = scene.objects, scene = if (full) scene else ui.scene)
                // Say it when found, and again only when where it is changes.
                if (hit && answer != lastAnswer && !isSpeaking()) {
                    lastAnswer = answer
                    ui = ui.copy(lastSaid = answer)
                    speak(answer, NarrationTier.INTERRUPT)
                }
                delay(600)
            }
        }
    }

    fun stopLooking() {
        stopLoop()
        ui = ui.copy(mode = ExploreMode.LOOKING)
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    // ---------------------------------------------------------------------------------
    //  Into the mind map
    // ---------------------------------------------------------------------------------

    /**
     * "This is Ravi's tea shop": a named spot where the rider stands, with what the camera sees
     * there, joined to the nearest place they already know.
     */
    fun nameThisPlace(name: String) {
        val clean = name.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        if (clean.isBlank()) return
        scope.launch {
            val here = position()
            if (here == null) {
                speak("I can't tell where you are right now, so I couldn't keep $clean. Try again outdoors.", NarrationTier.INTERRUPT)
                return@launch
            }
            val now = System.currentTimeMillis()
            val seen = (ui.scene?.objects ?: ui.live).take(3).map { it.label }
            val node = Node(
                id = "a:spot-${now.toString(36)}", kind = NodeKind.ANCHOR, label = clean,
                lat = here.first, lng = here.second,
                meaning = if (seen.isNotEmpty()) "your camera saw ${seen.joinToString(", ")} there" else "",
                words = "this is $clean", createdAt = now, lastConfirmedAt = now
            )
            val all = mindMap.authored()
            val nearest = all.near(here.first, here.second, 150.0, setOf(NodeKind.PLACE))
                .firstOrNull { !it.first.id.startsWith("walk:") }?.first
            val edges = listOfNotNull(nearest?.let { Edge(node.id, it.id, EdgeKind.NEAR) })
            mindMap.saveAuthored(all.upsert(listOf(node), edges))
            if (ui.recordingPath) WalkRecorder.add(WalkEvent.Mark(now, "$clean ahead"))
            ui = ui.copy(spots = ui.spots + clean)
            speak(
                "Remembered $clean here" + (nearest?.let { ", near ${it.label}" } ?: "") +
                    ". Later, say take me to $clean, or ask what's around here.",
                NarrationTier.INTERRUPT
            )
        }
    }

    /** What the camera saw, into the explore part of the mind map and onto the walk's path. */
    private fun remember(scene: Scene) {
        val now = System.currentTimeMillis()
        val worth = scene.objects.filter { it.confidence >= 0.5f && SceneSpeech.landmarkWords(it) != null }
        if (worth.isEmpty() || now - lastObservedAt < OBSERVE_EVERY_MS) return
        lastObservedAt = now

        if (ui.recordingPath) {
            worth.filter { markedOnPath.add(it.label.lowercase(Locale.ROOT)) }
                .forEach { o -> SceneSpeech.landmarkWords(o)?.let { WalkRecorder.add(WalkEvent.Mark(now, it)) } }
        }
        scope.launch {
            val here = position() ?: return@launch
            val nodes = worth.mapIndexed { i, o ->
                Node(
                    id = "obs:${TextSimilarity.normalise(o.label).replace(' ', '-')}-${now.toString(36)}-$i",
                    kind = NodeKind.OBSERVED, label = o.label.lowercase(Locale.ROOT),
                    lat = here.first, lng = here.second,
                    sense = senseOf(o.label), side = sideOf(o), source = scene.source.name,
                    words = SceneSpeech.phrase(o), createdAt = now, lastConfirmedAt = now
                )
            }
            mindMap.observe(nodes, now)
            ui = ui.copy(seen = ui.seen + nodes.size)
        }
    }

    private fun forgetOld() {
        val cutoff = System.currentTimeMillis() - RECENT_MS
        recent.entries.removeAll { it.value < cutoff }
    }

    companion object {
        private const val TAG = "CabEye.Explore"
        const val FIND_FOR_MS = 90_000L
        /** A label heard within this long is not said again in "keep telling me". */
        const val RECENT_MS = 12_000L
        const val OBSERVE_EVERY_MS = 4_000L

        fun senseOf(label: String): LandmarkKind {
            val l = label.lowercase(Locale.ROOT)
            return when {
                "door" in l || "entrance" in l -> LandmarkKind.DOOR
                "gate" in l -> LandmarkKind.GATE
                "step" in l || "stair" in l -> LandmarkKind.STEPS
                "kerb" in l || "curb" in l -> LandmarkKind.KERB
                "ramp" in l -> LandmarkKind.RAMP
                "pole" in l || "pillar" in l || "post" in l -> LandmarkKind.POLE
                "wall" in l -> LandmarkKind.WALL
                "rail" in l -> LandmarkKind.RAILING
                "crossing" in l || "zebra" in l -> LandmarkKind.CROSSING
                else -> LandmarkKind.OTHER
            }
        }

        fun sideOf(o: SceneObject): Side = when (SceneSpeech.side(o)) {
            "on your left" -> Side.LEFT
            "on your right" -> Side.RIGHT
            else -> Side.AHEAD
        }

        private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    }
}
