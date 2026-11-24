package com.shivansh.raasta

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shivansh.raasta.core.Geo
import com.shivansh.raasta.core.PlaceKind
import com.shivansh.raasta.core.Poi
import com.shivansh.raasta.core.RoadTiles
import com.shivansh.raasta.ui.CellSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.floor

/**
 * Street-level detail for the map, loaded as you look around: first from the phone, else a ~9 KB tile from GitHub.
 * Every tile seen once stays on the phone, so places you've looked at keep working offline.
 */
class LiveTiles(private val tiles: RoadTiles, private val scope: CoroutineScope) {
    private class Tile(val cells: CellSet?, val pois: List<Poi>)

    private val loaded = LinkedHashMap<String, Tile>(64, 0.75f, true)
    private val failedAt = HashMap<String, Long>()
    private val flying = HashSet<String>()
    private val gate = Semaphore(3)
    private var wanted: Set<String> = emptySet()

    /** What to draw. A new list each time something arrives. */
    var sets by mutableStateOf<List<CellSet>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    /** Tiles were needed but couldn't be fetched: we're offline. */
    var offline by mutableStateOf(false)
        private set

    /** Main thread. Keeps [keys] on screen; anything else is dropped once the memory budget is full. */
    fun want(keys: Collection<String>, network: Boolean) {
        wanted = keys.toSet()
        val now = System.currentTimeMillis()
        for (k in keys) {
            if (loaded[k] != null || k in flying) continue
            if ((failedAt[k] ?: 0) > now - 8_000) continue
            flying.add(k)
            scope.launch {
                gate.withPermit {
                    try {
                        if (k in wanted) fetch(k, network)
                    } finally {
                        flying.remove(k)
                        loading = flying.isNotEmpty()
                    }
                }
            }
        }
        loading = flying.isNotEmpty()
        if (loaded.keys.any { it !in shown }) publish()
    }

    private var shown: Set<String> = emptySet()

    private suspend fun fetch(k: String, network: Boolean) {
        try {
            val t = withContext(Dispatchers.IO) {
                tiles.shapes(k, network)?.let { Tile(CellSet.ofTile(it), it.pois) }
            }
            if (t == null && !network) return
            loaded[k] = t ?: Tile(null, emptyList())
            failedAt.remove(k)
            if (network) offline = false
            publish()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failedAt[k] = System.currentTimeMillis()
            if (network) offline = true
        }
    }

    private fun publish() {
        val it = loaded.entries.iterator()
        while (loaded.size > MAX && it.hasNext()) if (it.next().key !in wanted) it.remove()
        shown = loaded.keys.toSet()
        sets = loaded.values.mapNotNull { it.cells }
    }

    /** Places of the given kinds near a point, nearest first. Loads the tiles around it if needed. */
    suspend fun nearby(lat: Double, lon: Double, kinds: Set<PlaceKind>, limit: Int = 10): List<Pair<Poi, Double>> {
        var failed = 0
        val keys = around(lat, lon, 2)
        val pois = coroutineScope {
            keys.map { k ->
                async {
                    loaded[k]?.pois ?: gate.withPermit {
                        withContext(Dispatchers.IO) {
                            runCatching { tiles.shapes(k) }.onFailure { failed++ }.getOrNull()?.pois.orEmpty()
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        if (pois.isEmpty() && failed > 0) throw java.io.IOException("Needs internet the first time you look around here.")
        return pois.filter { it.kind in kinds }.map { it to Geo.dist(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }.distinctBy { it.first.name to (it.second / 60).toInt() }.take(limit)
    }

    companion object {
        private const val MAX = 90

        fun around(lat: Double, lon: Double, r: Int): List<String> {
            val row = floor(lat / RoadTiles.SIZE).toInt()
            val col = floor(lon / RoadTiles.SIZE).toInt()
            return (-r..r).flatMap { a -> (-r..r).map { b -> a to b } }.sortedBy { (a, b) -> a * a + b * b }
                .map { (a, b) -> "${row + a}_${col + b}" }
        }

        /** Tiles covering a box, centre first so what you're looking at appears first. */
        fun covering(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double): List<String> {
            val r0 = floor(latMin / RoadTiles.SIZE).toInt(); val r1 = floor(latMax / RoadTiles.SIZE).toInt()
            val c0 = floor(lonMin / RoadTiles.SIZE).toInt(); val c1 = floor(lonMax / RoadTiles.SIZE).toInt()
            val rm = (r0 + r1) / 2.0; val cm = (c0 + c1) / 2.0
            val k = cos(Math.toRadians(latMin))
            return (r0..r1).flatMap { r -> (c0..c1).map { c -> r to c } }
                .sortedBy { (r, c) -> (r - rm) * (r - rm) + (c - cm) * (c - cm) * k * k }
                .map { (r, c) -> "${r}_$c" }
        }
    }
}
