package com.cabeye.rider.places

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled Coimbatore atlas once (assets/atlas/coimbatore.json, ~125 KB) and keeps it.
 * No network: the whole point is that area names, well-known places and place search work with
 * no paid service, no key and no signal.
 */
object AtlasStore {
    private const val ASSET = "atlas/coimbatore.json"

    @Volatile
    private var loaded: Atlas? = null

    suspend fun get(context: Context): Atlas? = loaded ?: withContext(Dispatchers.IO) {
        loaded ?: runCatching {
            val json = context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            Atlas.parse(json)?.withAreas(gazetteerAreas())
        }.onFailure { Log.w("CabEye.Atlas", "ATLAS load failed: ${it.message}") }
            .getOrNull()
            ?.also {
                loaded = it
                Log.i("CabEye.Atlas", "ATLAS ${it.region}: ${it.areas.size} areas, ${it.places.size} places")
            }
    }

    /**
     * The app's own hand-checked Coimbatore neighbourhoods (Race Course, Sundarapuram, Hope
     * College…) where OpenStreetMap has no area point for them. Stations and the airport are
     * places, not areas.
     */
    fun gazetteerAreas(): List<AtlasArea> =
        City.COIMBATORE.places
            .filterNot { Regex("junction|airport", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            .map { AtlasArea(it.name, "suburb", it.latitude, it.longitude) }
}
