package com.shivansh.raasta

import android.content.Context
import com.shivansh.raasta.core.Place
import com.shivansh.raasta.core.PlaceDb
import com.shivansh.raasta.core.PlaceKind
import com.shivansh.raasta.core.Poi
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Opens the bundled place file. It ships compressed, is unpacked once after each install or update, and then read
 * straight from disk (memory-mapped), so 200k places cost next to no memory.
 */
object PlaceFile {
    fun open(app: Context): PlaceDb? = runCatching {
        val stamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        val dir = File(app.filesDir, "places").apply { mkdirs() }
        val file = File(dir, "places-$stamp.db")
        if (!file.exists()) {
            val tmp = File(dir, "unpacking.tmp")
            app.assets.open("places.db").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            dir.listFiles()?.forEach { if (it.name.startsWith("places-")) it.delete() }
            check(tmp.renameTo(file))
        }
        RandomAccessFile(file, "r").channel.use { PlaceDb(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())) }
    }.getOrNull()
}

/** The smallest map scale (dp per metre) at which a place of each rank appears. */
private val APPEARS = floatArrayOf(0.035f, 0.14f, 0.28f, 0.42f, 0.85f, 2.2f)

fun maxRankAt(z: Float): Int = APPEARS.indexOfLast { z >= it }

/** The places worth drawing in a box: best first, so when there isn't room the ones that matter win. */
fun PlaceDb.forMap(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double, maxRank: Int, limit: Int = 700): List<Poi> {
    if (maxRank < 0) return emptyList()
    val ids = ArrayList<Int>()
    inBox(latMin, lonMin, latMax, lonMax, maxRank) { i ->
        val k = kind(i)
        if (k != PlaceKind.AREA && k != PlaceKind.ROAD && k != PlaceKind.METRO && !(k == PlaceKind.METRO_GATE && maxRank < 4)) ids.add(i)
    }
    val key = ids.map { i -> i to rank(i) * 10.0 - confidence(i) }.sortedBy { it.second }.take(limit)
    return key.map { (i, _) -> Poi(name(i), kind(i), lat(i), lon(i), rank(i)) }
}

fun Poi.asPlace(label: String = kind.label) = Place(name, "", lat, lon, kind, label)
