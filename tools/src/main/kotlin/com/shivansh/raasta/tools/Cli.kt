package com.shivansh.raasta.tools

import com.shivansh.raasta.core.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos

/**
 * PC helper for the Raasta engine.
 *   build <lat,lon> <lat,lon> <out.rpk> [fromName] [toName]   download a pack from live OSM data
 *   show <pack.rpk>                                           print every route's spoken decisions
 *   render <pack.rpk> <out.png> [routeIndex]                  draw the corridor, flyovers and route
 *   sim <pack.rpk> [routeIndex]                               simulate a drive and print what is spoken
 *   tiles <extract.osm.pbf> <outDir>                          cut Delhi road tiles for hosting on GitHub
 * build reads tiles from $RAASTA_TILES (a folder or URL; default: the local data/ folder).
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "build" -> build(args)
        "show" -> show(PackIO.read(File(args[1])))
        "render" -> render(PackIO.read(File(args[1])), File(args[2]), args.getOrNull(3)?.toInt() ?: 0)
        "sim" -> sim(PackIO.read(File(args[1])), args.getOrNull(2)?.toInt() ?: 0)
        "diag" -> diag(ll(args[1]), ll(args[2]))
        "search" -> Geocoder(Http()).search(args.drop(1).joinToString(" ")).forEach { println("%-16s %s  (%s)".format(it.kind.label, it.name, it.detail)) }
        "places" -> TileCutter.run(File(args[1]), File("data"), overture = File(args[2]))
        "find" -> find(args)
        "tiles" -> TileCutter.run(File(args[1]), File(args[2]))
        else -> println("usage: build|show|render|sim … (see Cli.kt)")
    }
}

/** find [--at lat,lon] words…   search the offline place file, as the app does */
private fun find(args: Array<String>) {
    val db = java.io.RandomAccessFile("app/src/main/assets/places.db", "r").channel.use { PlaceDb(it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size())) }
    var rest = args.drop(1)
    var at: LatLon? = null
    if (rest.firstOrNull() == "--at") { at = ll(rest[1]); rest = rest.drop(2) }
    for (q in rest.joinToString(" ").split(" / ")) {
        val t0 = System.nanoTime()
        val r = db.search(q, at, 6, typing = false)
        println("== $q   (${(System.nanoTime() - t0) / 1_000_000} ms)")
        for (h in r) println("   %.2f %-18s %s  (%s)%s".format(h.score, db.cat(h.id).label, h.place.name, h.place.detail, if (h.distance.isNaN()) "" else "  " + Phrases.shortDistance(h.distance)))
    }
}

private fun ll(s: String) = s.split(',').let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }

private fun build(args: Array<String>) {
    val from = ll(args[1]); val to = ll(args[2])
    val out = File(args[3])
    val http = Http()
    val tileBase = System.getenv("RAASTA_TILES") ?: "data"
    val builder = PackBuilder(http, RoadTiles(http, listOf(tileBase)), useOverpassFallback = false)
    val t0 = System.currentTimeMillis()
    val routes = builder.findRoutes(from, to)
    println("OSRM: " + (routes?.joinToString { "%.1f km / %.0f min".format(it.distance / 1000, it.duration / 60) } ?: "unreachable, routing locally"))
    val pack = builder.build(
        Place(args.getOrNull(4) ?: "Start", "", from.lat, from.lon),
        Place(args.getOrNull(5) ?: "Destination", "", to.lat, to.lon),
        routes,
    ) { println("  … $it") }
    PackIO.write(pack, out)
    println("Saved ${out.path}: ${out.length() / 1024} KB, ${pack.lat.size} nodes, ${pack.ways.size} roads, " +
        "${pack.landmarks.size} landmarks, ${(System.currentTimeMillis() - t0) / 1000}s")
    show(pack)
}

private fun show(pack: RoutePack) {
    val g = RoadGraph(pack)
    println("\n== ${pack.meta.title} · ${pack.meta.summary}")
    for ((k, r) in pack.routes.withIndex()) {
        val path = Path.fromNodes(g, r.nodePath) ?: continue
        val ms = Guidance.build(path, pack.meta.toName)
        println("\n-- Route $k: ${r.label} · %.1f km (path %.1f km) · %s".format(r.distance / 1000, path.length / 1000, Guidance.summary(ms)))
        println(Phrases.briefing(pack.meta.toName, path.length, path.time, ms))
        for (m in ms) println("  $m")
    }
}

private fun sim(pack: RoutePack, routeIndex: Int) {
    val g = RoadGraph(pack)
    val path = Path.fromNodes(g, pack.routes[routeIndex].nodePath)!!
    var clock = 0
    val nav = Navigator(g, path, pack.meta.toName) { text, urgent ->
        println("[%4ds %s] %s".format(clock, if (urgent) "!" else " ", text))
    }
    nav.start()
    val demo = DemoDriver(nav)
    while (!demo.done && clock < 7200) {
        val s = nav.onFix(demo.next())
        clock++
        if (s.status == NavStatus.ARRIVED) break
    }
}

private fun render(pack: RoutePack, file: File, routeIndex: Int) {
    val g = RoadGraph(pack)
    val minLat = pack.lat.min(); val maxLat = pack.lat.max(); val minLon = pack.lon.min(); val maxLon = pack.lon.max()
    val k = cos(Math.toRadians((minLat + maxLat) / 2))
    val wM = (maxLon - minLon) * k * 111_320; val hM = (maxLat - minLat) * 111_320
    val size = 2000
    val scale = size / maxOf(wM, hM)
    val W = (wM * scale).toInt() + 80; val H = (hM * scale).toInt() + 80
    fun x(lon: Double) = (40 + (lon - minLon) * k * 111_320 * scale)
    fun y(lat: Double) = (40 + (maxLat - lat) * 111_320 * scale)
    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    val gr = img.createGraphics()
    gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    gr.color = Color(0x0E, 0x10, 0x14); gr.fillRect(0, 0, W, H)
    fun wayPath(w: PackWay) = Path2D.Double().apply {
        moveTo(x(pack.lon[w.nodes[0]]), y(pack.lat[w.nodes[0]]))
        for (n in w.nodes.drop(1)) lineTo(x(pack.lon[n]), y(pack.lat[n]))
    }
    val sorted = pack.ways.sortedBy { (if (it.bridge) 100 else 0) + it.cls.rank }
    for (w in sorted) {
        val width = when { w.cls.rank >= 5 -> 5f; w.cls.rank >= 3 -> 3.5f; else -> 2f }
        if (w.bridge) {
            gr.stroke = BasicStroke(width + 5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            gr.color = Color(0, 0, 0); gr.draw(wayPath(w))
            gr.stroke = BasicStroke(width + 2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            gr.color = Color(0xFF, 0xB0, 0x20)
        } else if (w.isUnderground) {
            gr.stroke = BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 6f), 0f)
            gr.color = Color(0x7A, 0x8C, 0xFF)
        } else {
            gr.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            gr.color = if (w.cls == RoadClass.SERVICE) Color(0x2A, 0x2F, 0x38) else Color(0x4A, 0x52, 0x60)
        }
        gr.draw(wayPath(w))
    }
    val path = Path.fromNodes(g, pack.routes[routeIndex].nodePath)!!
    val rp = Path2D.Double().apply {
        moveTo(x(path.lon(0)), y(path.lat(0)))
        for (i in 1 until path.nodes.size) lineTo(x(path.lon(i)), y(path.lat(i)))
    }
    gr.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    gr.color = Color(0x36, 0xD6, 0xC3, 210); gr.draw(rp)
    gr.font = Font("SansSerif", Font.BOLD, 15)
    for ((n, m) in Guidance.build(path).withIndex()) {
        val px = x(path.lon(m.node)); val py = y(path.lat(m.node))
        gr.color = if (m.kind.structural) Color(0xFF, 0xB0, 0x20) else Color.WHITE
        gr.fillOval(px.toInt() - 6, py.toInt() - 6, 12, 12)
        gr.drawString("${n + 1} ${Phrases.banner(m)}", px.toInt() + 9, py.toInt() - 6)
    }
    gr.dispose()
    ImageIO.write(img, "png", file)
    println("Wrote ${file.path} (${W}x$H)")
}

/** Where does our aligned path leave the OSRM line? */
private fun diag(from: LatLon, to: LatLon) {
    val http = Http()
    val tiles = RoadTiles(http, listOf(System.getenv("RAASTA_TILES") ?: "data"))
    val b = PackBuilder(http, tiles, false)
    val r = b.findRoutes(from, to)!!.first()
    println("osrm nodes=${r.nodeIds.size} coords=${r.coords.size} dist=${r.distance}")
    val data = tiles.load(RoadTiles.along(listOf(r.coords), 250.0))
    val pack = PackBuilder.toPack(data, Place("a", "", from.lat, from.lon), Place("b", "", to.lat, to.lon), listOf(r.coords), 250.0)
    val g = RoadGraph(pack)
    val idx = HashMap<Long, Int>(); pack.nodeIds.forEachIndexed { i, id -> idx[id] = i }
    for (k in r.nodeIds.indices) {
        val n = idx[r.nodeIds[k]]
        val c = r.coords[k]
        val d = if (n == null) -1.0 else Geo.dist(c.lat, c.lon, g.lat[n], g.lon[n])
        if (n == null || d > 5) println("  #$k id=${r.nodeIds[k]} ${if (n == null) "missing" else "off by %.0f m".format(d)}")
    }
    if (System.getenv("SEQ") != null) diagSeq(g, r.coords)
    val path = b.mapRoute(g, r)!!
    println("path %.0f m vs osrm %.0f m".format(path.length, r.distance))
    var inDet = false
    for (i in path.nodes.indices) {
        val p = LatLon(path.lat(i), path.lon(i))
        var md = Double.MAX_VALUE
        for (j in 0 until r.coords.size - 1) md = minOf(md, Geo.project(p.lat, p.lon, r.coords[j].lat, r.coords[j].lon, r.coords[j + 1].lat, r.coords[j + 1].lon).dist)
        if (md > 25 && !inDet) { inDet = true; println("  leaves OSRM line at path node $i (%.0f m) id=${pack.nodeIds[path.nodes[i]]} way=${path.way(maxOf(0, i - 1)).label} ${path.way(maxOf(0,i-1)).osmId}".format(path.cum[i])) }
        if (md <= 25 && inDet) { inDet = false; println("  rejoins at node $i (%.0f m)".format(path.cum[i])) }
    }
}

internal fun diagSeq(g: RoadGraph, coords: List<LatLon>) {
    for ((k, p) in coords.withIndex().take(30)) {
        val near = g.lat.indices.filter { Geo.dist(p.lat, p.lon, g.lat[it], g.lon[it]) < 2.5 }
        println("  c$k ${"%.6f,%.6f".format(p.lat, p.lon)} -> " + near.joinToString { n ->
            "${g.pack.nodeIds[n]} out=" + g.out[n].joinToString("/") { e -> "${g.pack.nodeIds[g.edges[e].to]}(${g.way(e).cls.osm}${if (g.way(e).oneway != 0) " ow" else ""})" }
        })
    }
}
