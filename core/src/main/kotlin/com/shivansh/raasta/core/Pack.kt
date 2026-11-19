package com.shivansh.raasta.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** OSM highway classes we keep, with typical Delhi driving speeds. */
enum class RoadClass(val osm: String, val kmh: Int, val rank: Int) {
    MOTORWAY("motorway", 70, 7),
    TRUNK("trunk", 50, 6),
    PRIMARY("primary", 40, 5),
    SECONDARY("secondary", 35, 4),
    TERTIARY("tertiary", 30, 3),
    UNCLASSIFIED("unclassified", 25, 2),
    RESIDENTIAL("residential", 20, 1),
    LIVING("living_street", 10, 0),
    SERVICE("service", 15, 0);

    companion object {
        private val byOsm = entries.associateBy { it.osm }
        /** "primary_link" -> PRIMARY (link flag is stored separately). */
        fun of(highway: String): RoadClass? = byOsm[highway.removeSuffix("_link")]
    }
}

class PackWay(
    val osmId: Long,
    val nodes: IntArray,
    val cls: RoadClass,
    val link: Boolean,
    /** 1 = only forward, -1 = only backward, 0 = both ways. */
    val oneway: Int,
    val bridge: Boolean,
    val tunnel: Boolean,
    val layer: Int,
    val roundabout: Boolean,
    val name: String?,
    val ref: String?,
    val destination: String?,
    val lanes: Int,
    val maxspeed: Int,
) {
    val label: String? get() = name ?: ref

    /** Metres per second used for routing. */
    fun speedMps(): Double {
        var kmh = cls.kmh.toDouble()
        if (maxspeed in 10..120) kmh = minOf(kmh * 1.15, maxspeed.toDouble())
        if (link) kmh *= 0.8
        return kmh / 3.6
    }

    val isUnderground get() = tunnel || layer < 0
}

class Landmark(val name: String, val kind: String, val lat: Double, val lon: Double) {
    /** How it is said in speech: "Moti Bagh metro station", "the Indian Oil petrol pump". */
    val spoken: String
        get() = when (kind) {
            "metro" -> if (name.contains("metro", true)) name else "$name metro station"
            "station" -> if (name.contains("station", true)) name else "$name station"
            "fuel" -> if (name.isBlank()) "the petrol pump" else "the $name petrol pump"
            else -> name
        }
}

/** One planned route through the pack's road graph, as a list of graph node indexes. */
class PlannedRoute(val nodePath: IntArray, val distance: Double, val duration: Double, val label: String)

class PackMeta(
    val id: String,
    val title: String,
    val fromName: String,
    val toName: String,
    val createdAt: Long,
    val from: LatLon,
    val to: LatLon,
    val distance: Double,
    val duration: Double,
    /** e.g. "Takes 4 flyovers, skips 2". */
    val summary: String,
    val routeCount: Int,
)

class RoutePack(
    val meta: PackMeta,
    val nodeIds: LongArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** bit 0 = traffic signal */
    val nodeFlags: ByteArray,
    val ways: List<PackWay>,
    val landmarks: List<Landmark>,
    val routes: List<PlannedRoute>,
) {
    fun isSignal(node: Int) = (nodeFlags[node].toInt() and 1) != 0

    fun withRoutes(meta: PackMeta, routes: List<PlannedRoute>) =
        RoutePack(meta, nodeIds, lat, lon, nodeFlags, ways, landmarks, routes)

    companion object { const val FLAG_SIGNAL: Byte = 1 }
}

/** Compact gzip'd binary format (*.rpk). Meta comes first so the route list can be shown without loading the graph. */
object PackIO {
    private const val MAGIC = 0x52535441 // "RSTA"
    private const val VERSION = 1

    fun write(pack: RoutePack, out: OutputStream) {
        DataOutputStream(GZIPOutputStream(out).buffered()).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            writeMeta(o, pack.meta)
            val n = pack.lat.size
            o.writeInt(n)
            for (i in 0 until n) {
                o.writeLong(pack.nodeIds[i])
                o.writeInt(Math.round(pack.lat[i] * 1e7).toInt())
                o.writeInt(Math.round(pack.lon[i] * 1e7).toInt())
                o.writeByte(pack.nodeFlags[i].toInt())
            }
            o.writeInt(pack.ways.size)
            for (w in pack.ways) {
                o.writeLong(w.osmId)
                o.writeInt(w.nodes.size)
                for (x in w.nodes) o.writeInt(x)
                o.writeByte(w.cls.ordinal)
                o.writeBoolean(w.link)
                o.writeByte(w.oneway)
                o.writeBoolean(w.bridge)
                o.writeBoolean(w.tunnel)
                o.writeByte(w.layer)
                o.writeBoolean(w.roundabout)
                str(o, w.name); str(o, w.ref); str(o, w.destination)
                o.writeByte(w.lanes)
                o.writeShort(w.maxspeed)
            }
            o.writeInt(pack.landmarks.size)
            for (l in pack.landmarks) {
                o.writeUTF(l.name); o.writeUTF(l.kind); o.writeDouble(l.lat); o.writeDouble(l.lon)
            }
            o.writeInt(pack.routes.size)
            for (r in pack.routes) {
                o.writeInt(r.nodePath.size)
                for (x in r.nodePath) o.writeInt(x)
                o.writeDouble(r.distance); o.writeDouble(r.duration); o.writeUTF(r.label)
            }
        }
    }

    fun write(pack: RoutePack, file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { write(pack, it) }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) throw java.io.IOException("Could not save ${file.name}")
    }

    fun readMeta(file: File): PackMeta = open(file.inputStream()).use { readMeta(it) }

    fun read(file: File): RoutePack = file.inputStream().use { read(it) }

    fun read(input: InputStream): RoutePack = open(input).use { i ->
        val meta = readMeta(i)
        val n = i.readInt()
        val ids = LongArray(n); val lat = DoubleArray(n); val lon = DoubleArray(n); val flags = ByteArray(n)
        for (k in 0 until n) {
            ids[k] = i.readLong(); lat[k] = i.readInt() / 1e7; lon[k] = i.readInt() / 1e7; flags[k] = i.readByte()
        }
        val ways = List(i.readInt()) {
            val id = i.readLong()
            val nodes = IntArray(i.readInt()) { i.readInt() }
            PackWay(
                osmId = id, nodes = nodes, cls = RoadClass.entries[i.readByte().toInt()], link = i.readBoolean(),
                oneway = i.readByte().toInt(), bridge = i.readBoolean(), tunnel = i.readBoolean(),
                layer = i.readByte().toInt(), roundabout = i.readBoolean(),
                name = str(i), ref = str(i), destination = str(i),
                lanes = i.readByte().toInt(), maxspeed = i.readShort().toInt(),
            )
        }
        val landmarks = List(i.readInt()) { Landmark(i.readUTF(), i.readUTF(), i.readDouble(), i.readDouble()) }
        val routes = List(i.readInt()) {
            val path = IntArray(i.readInt()) { i.readInt() }
            PlannedRoute(path, i.readDouble(), i.readDouble(), i.readUTF())
        }
        RoutePack(meta, ids, lat, lon, flags, ways, landmarks, routes)
    }

    private fun open(input: InputStream): DataInputStream {
        val i = DataInputStream(GZIPInputStream(input).buffered())
        if (i.readInt() != MAGIC) throw java.io.IOException("Not a Vays Maps route pack")
        val v = i.readInt()
        if (v != VERSION) throw java.io.IOException("Route pack version $v is not supported")
        return i
    }

    private fun writeMeta(o: DataOutputStream, m: PackMeta) {
        o.writeUTF(m.id); o.writeUTF(m.title); o.writeUTF(m.fromName); o.writeUTF(m.toName)
        o.writeLong(m.createdAt)
        o.writeDouble(m.from.lat); o.writeDouble(m.from.lon); o.writeDouble(m.to.lat); o.writeDouble(m.to.lon)
        o.writeDouble(m.distance); o.writeDouble(m.duration); o.writeUTF(m.summary); o.writeInt(m.routeCount)
    }

    private fun readMeta(i: DataInputStream) = PackMeta(
        id = i.readUTF(), title = i.readUTF(), fromName = i.readUTF(), toName = i.readUTF(),
        createdAt = i.readLong(),
        from = LatLon(i.readDouble(), i.readDouble()), to = LatLon(i.readDouble(), i.readDouble()),
        distance = i.readDouble(), duration = i.readDouble(), summary = i.readUTF(), routeCount = i.readInt(),
    )

    private fun str(o: DataOutputStream, s: String?) {
        o.writeBoolean(s != null)
        if (s != null) o.writeUTF(s)
    }

    private fun str(i: DataInputStream): String? = if (i.readBoolean()) i.readUTF() else null
}

/** Folder of downloaded packs. */
class PackStore(val dir: File) {
    init { dir.mkdirs() }

    fun list(): List<Pair<File, PackMeta>> =
        (dir.listFiles { f -> f.name.endsWith(".rpk") } ?: emptyArray())
            .mapNotNull { f -> runCatching { f to PackIO.readMeta(f) }.getOrNull() }
            .sortedByDescending { it.second.createdAt }

    fun fileFor(id: String) = File(dir, "$id.rpk")
    fun save(pack: RoutePack): File = fileFor(pack.meta.id).also { PackIO.write(pack, it) }
    fun load(id: String): RoutePack = PackIO.read(fileFor(id))
    fun delete(id: String) = fileFor(id).delete()
}
