package com.shivansh.raasta.tools

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.zip.Inflater

/**
 * Minimal OpenStreetMap PBF reader (nodes + ways, no relations), no dependencies.
 * Format: https://wiki.openstreetmap.org/wiki/PBF_Format
 */
class PbfReader(private val file: File) {
    interface Sink {
        fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>)
        fun way(id: Long, refs: LongArray, tags: Map<String, String>)
    }

    fun read(sink: Sink, wantWays: Boolean = true) {
        DataInputStream(file.inputStream().buffered(1 shl 20)).use { inp ->
            while (true) {
                val headerLen = try { inp.readInt() } catch (_: EOFException) { break }
                val header = ByteArray(headerLen).also { inp.readFully(it) }
                var type = ""
                var dataSize = 0
                Proto(header).forEach { f, p ->
                    when (f) {
                        1 -> type = p.string()
                        3 -> dataSize = p.varint().toInt()
                        else -> p.skip()
                    }
                }
                val blob = ByteArray(dataSize).also { inp.readFully(it) }
                if (type != "OSMData") continue
                block(unpack(blob), sink, wantWays)
            }
        }
    }

    private fun unpack(blob: ByteArray): ByteArray {
        var raw: ByteArray? = null
        var rawSize = 0
        var z: ByteArray? = null
        Proto(blob).forEach { f, p ->
            when (f) {
                1 -> raw = p.bytes()
                2 -> rawSize = p.varint().toInt()
                3 -> z = p.bytes()
                else -> p.skip()
            }
        }
        raw?.let { return it }
        val inf = Inflater()
        inf.setInput(z ?: error("Unsupported blob compression"))
        val out = ByteArray(rawSize)
        var n = 0
        while (n < rawSize) {
            val r = inf.inflate(out, n, rawSize - n)
            if (r == 0 && (inf.finished() || inf.needsInput())) break
            n += r
        }
        inf.end()
        return out
    }

    private fun block(data: ByteArray, sink: Sink, wantWays: Boolean) {
        val strings = ArrayList<String>()
        val groups = ArrayList<ByteArray>()
        var gran = 100L
        var latOff = 0L
        var lonOff = 0L
        Proto(data).forEach { f, p ->
            when (f) {
                1 -> Proto(p.bytes()).forEach { sf, sp -> if (sf == 1) strings.add(sp.string()) else sp.skip() }
                2 -> groups.add(p.bytes())
                17 -> gran = p.varint()
                19 -> latOff = p.varint()
                20 -> lonOff = p.varint()
                else -> p.skip()
            }
        }
        fun lat(v: Long) = 1e-9 * (latOff + gran * v)
        fun lon(v: Long) = 1e-9 * (lonOff + gran * v)
        for (g in groups) {
            Proto(g).forEach { f, p ->
                when (f) {
                    2 -> dense(p.bytes(), strings, sink, ::lat, ::lon)
                    3 -> if (wantWays) way(p.bytes(), strings, sink) else p.skip()
                    1 -> node(p.bytes(), strings, sink, ::lat, ::lon)
                    else -> p.skip()
                }
            }
        }
    }

    private fun dense(b: ByteArray, st: List<String>, sink: Sink, lat: (Long) -> Double, lon: (Long) -> Double) {
        var ids = LongArray(0); var lats = LongArray(0); var lons = LongArray(0); var kv = IntArray(0)
        Proto(b).forEach { f, p ->
            when (f) {
                1 -> ids = p.packedSInt64Delta()
                8 -> lats = p.packedSInt64Delta()
                9 -> lons = p.packedSInt64Delta()
                10 -> kv = p.packedInt32()
                else -> p.skip()
            }
        }
        var k = 0
        for (i in ids.indices) {
            var tags: Map<String, String> = emptyMap()
            if (kv.isNotEmpty()) {
                var m: HashMap<String, String>? = null
                while (k < kv.size && kv[k] != 0) {
                    if (m == null) m = HashMap()
                    m[st[kv[k]]] = st[kv[k + 1]]
                    k += 2
                }
                k++ // skip the 0 separator
                if (m != null) tags = m
            }
            sink.node(ids[i], lat(lats[i]), lon(lons[i]), tags)
        }
    }

    private fun node(b: ByteArray, st: List<String>, sink: Sink, lat: (Long) -> Double, lon: (Long) -> Double) {
        var id = 0L; var la = 0L; var lo = 0L; var keys = IntArray(0); var vals = IntArray(0)
        Proto(b).forEach { f, p ->
            when (f) {
                1 -> id = p.sint64()
                2 -> keys = p.packedInt32()
                3 -> vals = p.packedInt32()
                8 -> la = p.sint64()
                9 -> lo = p.sint64()
                else -> p.skip()
            }
        }
        sink.node(id, lat(la), lon(lo), keys.indices.associate { st[keys[it]] to st[vals[it]] })
    }

    private fun way(b: ByteArray, st: List<String>, sink: Sink) {
        var id = 0L; var keys = IntArray(0); var vals = IntArray(0); var refs = LongArray(0)
        Proto(b).forEach { f, p ->
            when (f) {
                1 -> id = p.varint()
                2 -> keys = p.packedInt32()
                3 -> vals = p.packedInt32()
                8 -> refs = p.packedSInt64Delta()
                else -> p.skip()
            }
        }
        sink.way(id, refs, keys.indices.associate { st[keys[it]] to st[vals[it]] })
    }
}

/** Just enough protobuf. */
class Proto(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    private var wire = 0

    inline fun forEach(fn: (field: Int, p: Proto) -> Unit) {
        while (hasMore()) {
            val key = nextKey()
            fn(key, this)
        }
    }

    fun hasMore() = pos < end

    fun nextKey(): Int {
        val k = varint().toInt()
        wire = k and 7
        return k ushr 3
    }

    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            val x = b[pos++].toInt()
            r = r or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }

    fun sint64(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }

    private fun len(): Int = varint().toInt()

    fun bytes(): ByteArray { val n = len(); val r = b.copyOfRange(pos, pos + n); pos += n; return r }

    fun string(): String { val n = len(); val s = String(b, pos, n, Charsets.UTF_8); pos += n; return s }

    fun packedSInt64Delta(): LongArray {
        val n = len(); val stop = pos + n
        val out = LongArrayBuilder()
        var acc = 0L
        while (pos < stop) { acc += sint64(); out.add(acc) }
        return out.toArray()
    }

    fun packedInt32(): IntArray {
        val n = len(); val stop = pos + n
        val out = ArrayList<Int>()
        while (pos < stop) out.add(varint().toInt())
        return out.toIntArray()
    }

    fun skip() {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val n = len(); pos += n } // (pos += len() would read pos before len() advances it)
            5 -> pos += 4
            else -> error("Unknown wire type $wire")
        }
    }
}

class LongArrayBuilder {
    private var a = LongArray(64)
    var size = 0
        private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray(): LongArray = a.copyOf(size)
}
