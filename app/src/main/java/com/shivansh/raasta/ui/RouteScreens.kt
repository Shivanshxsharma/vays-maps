package com.shivansh.raasta.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bike
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.shivansh.raasta.AppModel
import com.shivansh.raasta.Screen
import com.shivansh.raasta.core.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlin.math.max

// ---------------------------------------------------------------- ride preview

@Composable
fun PackScreen(vm: AppModel, requestLocation: (() -> Unit) -> Unit) {
    BackHandler { vm.screen = Screen.Home }
    val lp = vm.loaded
    if (lp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.secondary, strokeWidth = 2.dp) }
        return
    }
    if (lp.paths.isEmpty()) { Text("This ride has no usable route.", style = T.body, modifier = Modifier.padding(32.dp)); return }
    val idx = vm.routeIndex.coerceIn(0, lp.paths.size - 1)
    val path = lp.paths[idx]
    val ms = lp.guidance[idx]
    val full = (LocalConfiguration.current.screenHeightDp * 0.56f).dp
    val peek = 262.dp
    val d = LocalDensity.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    // The sheet rises into place; pull it down to see more map.
    val span = with(d) { (full - peek).toPx() }
    val off = remember { Animatable(with(d) { full.toPx() }) }
    var open by remember { mutableStateOf(true) }
    LaunchedEffect(open) { off.animateTo(if (open) 0f else span, spring(dampingRatio = 0.86f, stiffness = 340f)) }
    val state = remember { MapState(MapCamera().also { it.minScale = 0.0065f * d.density; it.maxScale = 5f * d.density }, scope) }
    val bounds = remember(path) { boundsOf(path) }
    LaunchedEffect(path, open, state.cam.size) {
        val p = d.density
        val first = state.cam.cx == 0f && state.cam.cy == 0f
        if (state.cam.size.width > 0f) state.fit(bounds, 50 * p, 120 * p, 50 * p, with(d) { (if (open) full else peek).toPx() } + 46 * p, animate = !first)
    }
    val now = rememberNow()
    val markers = remember(path) {
        val n = path.nodes.size - 1
        listOf(
            MapMarker(World.x(path.lon(0)), World.y(path.lat(0)), false, lp.pack.meta.fromName),
            MapMarker(World.x(path.lon(n)), World.y(path.lat(n)), true, lp.pack.meta.toName),
        )
    }
    val drag = Modifier.draggable(
        rememberDraggableState { dy -> scope.launch { off.snapTo((off.value + dy).coerceIn(0f, span)) } }, Orientation.Vertical,
        onDragStopped = { v ->
            val want = if (v > 600) false else if (v < -600) true else off.value < span / 2
            if (want == open) off.animateTo(if (open) 0f else span, spring(dampingRatio = 0.86f, stiffness = 340f)) else open = want
        },
    )

    Box(Modifier.fillMaxSize()) {
        LiveMap(
            state, vm.city, vm.tiles, Modifier.fillMaxSize(), extra = lp.cells, on = vm.layersOn, route = path, maneuvers = ms,
            markers = markers, me = meDot(vm.here, now),
        )
        TopScrim()
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            GlassCircle(Lucide.ChevronLeft, "Back", size = 44.dp) { vm.screen = Screen.Home }
            Spacer(Modifier.weight(1f))
            GlassCircle(Lucide.Trash2, "Delete", tint = C.red, size = 44.dp) { confirmDelete = true }
        }
        MapButtons(
            state, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = full + 12.dp).graphicsLayer { translationY = off.value.coerceAtMost(span) },
            onLocate = {
                val h = vm.here
                if (h != null) state.show(World.x(h.fix.lon), World.y(h.fix.lat), max(state.cam.scale, 0.3f * d.density), 0f, with(d) { full.toPx() } - off.value)
                else requestLocation { vm.startLive() }
            },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(full)
                .graphicsLayer { translationY = off.value }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(C.grouped).blockMap(),
        ) {
            Box(drag.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }) { Grabber() }
            Column(drag.padding(horizontal = 22.dp)) {
                Text("FROM ${lp.pack.meta.fromName.uppercase()}", style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                Text(lp.pack.meta.toName, style = T.title1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val r = lp.pack.routes[idx]
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(Phrases.duration(r.duration).substringBefore(" "), style = T.number)
                    Text(" MIN", style = T.label, modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.width(18.dp))
                    Text(Phrases.shortDistance(path.length).substringBefore(" "), style = T.number.copy(color = C.secondary))
                    Text(" " + Phrases.shortDistance(path.length).substringAfter(" ").uppercase(), style = T.label, modifier = Modifier.padding(bottom = 8.dp))
                }
                r.label.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let { Text(it, style = T.footnote) }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlossCapsule("Start ride", Modifier.weight(1.3f), icon = Lucide.Bike) { requestLocation { vm.startNavigation(simulate = false) } }
                    LineCapsule("Demo", Modifier.weight(0.7f), icon = Lucide.Play) { vm.startNavigation(simulate = true) }
                }
                val away = vm.here?.let { h -> Geo.dist(h.latLon, LatLon(path.lat(0), path.lon(0))) }
                val onIt = vm.here?.let { h -> (0 until path.nodes.size).any { Geo.dist(h.fix.lat, h.fix.lon, path.lat(it), path.lon(it)) < 150 } } ?: true
                if (away != null && away > 300 && !onIt && vm.inDelhi) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("You're ${Phrases.shortDistance(away)} from the start of this ride.", style = T.footnote.copy(color = C.label), modifier = Modifier.weight(1f))
                    Text("Ride from here", style = T.footnote.copy(color = C.blue, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.pressable({ val m = lp.pack.meta; vm.plan(to = Place(m.toName, "", m.to.lat, m.to.lon)) }).padding(8.dp))
                }
                if (lp.paths.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(lp.paths) { i, p ->
                            val sel = i == idx
                            Column(
                                Modifier.clip(RoundedCornerShape(16.dp)).background(C.elevated)
                                    .border(1.5.dp, if (sel) C.blue else Color.Transparent, RoundedCornerShape(16.dp))
                                    .pressable({ vm.routeIndex = i }).padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(Phrases.duration(lp.pack.routes[i].duration), style = T.headline)
                                Text("${Phrases.shortDistance(p.length)} · ${lp.pack.routes[i].label.substringBefore(" · ")}", style = T.footnote)
                            }
                        }
                    }
                }
                if (!vm.speaker.offlineVoice) {
                    Text(
                        "Your phone's voice needs internet. Install an offline English (India) voice in Settings › Text-to-speech.",
                        style = T.footnote.copy(color = C.red), modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
                item {
                    Text("WHAT YOU'LL HEAR", style = T.label, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                }
                itemsIndexed(ms) { i, m -> TimelineRow(m, first = i == 0, last = i == ms.size - 1, modifier = Modifier.appear(i, delayStep = 40)) }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = C.elevated,
            title = { Text("Delete this ride?", style = T.headline) },
            text = { Text("You can download it again while you're online.", style = T.subhead) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePack(lp.pack.meta.id) }) { Text("Delete", style = T.body.copy(color = C.red, fontWeight = FontWeight.SemiBold)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = T.body) } },
        )
    }
}

/** One prompt on a vertical timeline: distance on the left rail, the icon, and the exact words. */
@Composable
private fun TimelineRow(m: Maneuver, first: Boolean, last: Boolean, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            // Rail
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(2.dp).height(20.dp).background(if (first) Color.Transparent else C.separator))
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (m.kind == Kind.ARRIVE) C.blue else C.label))
                Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else C.separator))
            }
        }
        Row(Modifier.weight(1f).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverGlyph(m, 38.dp, hollow = C.grouped)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Text(Phrases.banner(m), style = T.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("“${Phrases.action(m).replaceFirstChar { it.uppercase() }}”", style = T.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(Phrases.shortDistance(m.at).uppercase(), style = T.label)
        }
    }
}

// ---------------------------------------------------------------- riding

@Composable
fun NavScreen(vm: AppModel) {
    val lp = vm.loaded
    val s = vm.nav
    BackHandler { vm.stopNavigation() }
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    if (lp == null || s == null) return

    Box(Modifier.fillMaxSize()) {
        val end = lp.pack.meta.to
        val dest = remember(lp) { listOf(MapMarker(World.x(end.lon), World.y(end.lat), true, lp.pack.meta.toName)) }
        DriveMap(lp, vm.city, vm.tiles, vm.layersOn, s, Modifier.fillMaxSize(), dest, recentreBottom = 250.dp)
        ManeuverBanner(s, vm, Modifier.align(Alignment.TopCenter))
        Column(Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                SpeedCluster(s)
                Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.BottomCenter) {
                    // The road you're on, small: for confirming, never needed for following.
                    val road = remember(s.pathVersion, (s.progress / 15).toInt()) { s.path.way(s.path.segmentAt(s.progress)).label }
                    if (road != null && s.status != NavStatus.OUTSIDE_AREA) Text(
                        road, style = T.footnote.copy(color = C.label, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.panel(CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                AnimatedContent(targetState = vm.muted, label = "mute", transitionSpec = { (scaleIn(initialScale = 0.7f) + fadeIn()) togetherWith fadeOut() }) { muted ->
                    GlassCircle(if (muted) Lucide.VolumeX else Lucide.Volume2, "Mute", size = 52.dp, tint = if (muted) C.red else C.label) { vm.toggleMute() }
                }
            }
            TripCard(s, vm, Modifier)
        }
    }
}

/**
 * Speed limit sign (when the map knows it) and your speed. Over the limit, the speed turns red and the
 * sign pulses once; within it, everything stays quiet.
 */
@Composable
private fun SpeedCluster(s: NavState) {
    val kmh by animateFloatAsState(s.speedKmh.coerceAtLeast(0.0).toFloat(), tween(700), label = "speed")
    val over = s.overLimit
    val ring by androidx.compose.animation.animateColorAsState(if (over) C.red else C.separator, tween(300), label = "ring")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(visible = s.limitKmh > 0, enter = scaleIn(initialScale = 0.6f) + fadeIn(), exit = fadeOut()) {
            // Regulatory sign: white disc, red ring, black number.
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(Color.White).border(6.dp, Color(0xFFE5322D), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("${s.limitKmh}", style = T.title2.copy(color = Color(0xFF0D0D0D), fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum")) }
        }
        Column(
            Modifier.size(70.dp).panel(CircleShape).border(2.dp, ring, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (s.speedKmh < 0) "–" else "${kmh.toInt()}",
                style = T.title2.copy(fontSize = 24.sp, color = if (over) C.red else C.label, fontFeatureSettings = "tnum"),
            )
            Text("KM/H", style = T.label.copy(fontSize = 9.sp))
        }
    }
}

/** Big and glanceable. A new instruction slides in from below; the distance counts down smoothly. Tap to hear it again. */
@Composable
private fun ManeuverBanner(s: NavState, vm: AppModel, modifier: Modifier) {
    val m = s.next
    val arrived = s.status == NavStatus.ARRIVED
    val dist by animateFloatAsState(s.distToNext.toFloat(), tween(1000), label = "dist")
    Column(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)
            .panel(RoundedCornerShape(30.dp)).pressable({ vm.repeatLast() }).padding(20.dp),
    ) {
        AnimatedContent(
            targetState = if (arrived) -1 else (m?.node ?: -2),
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it / 2 } + fadeIn(tween(200))) togetherWith
                    (slideOutVertically(tween(180)) { -it / 3 } + fadeOut(tween(140)))
            },
            label = "maneuver",
        ) { key ->
            val shown = if (key == -1) Maneuver(0, 0.0, Kind.ARRIVE, Side.MIDDLE, 0.0) else m
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shown != null) ManeuverGlyph(shown, 92.dp, hollow = C.grouped)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        key == -1 -> {
                            Text("Arrived", style = T.title1.copy(color = C.good))
                            Text(vm.loaded?.pack?.meta?.toName.orEmpty(), style = T.subhead)
                        }
                        shown != null -> {
                            val d = Phrases.shortDistance(dist.toDouble())
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(d.substringBefore(" "), style = T.number.copy(fontSize = 46.sp))
                                Text(" " + d.substringAfter(" ").uppercase(), style = T.label.copy(fontSize = 13.sp), modifier = Modifier.padding(bottom = 9.dp))
                            }
                            Text(Phrases.banner(shown), style = T.title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = Phrases.detail(shown)
                            if (detail.isNotBlank()) Text(detail, style = T.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        // One quiet line under the main instruction: what comes right after, or a status.
        val after = m?.let { cur -> vm.navManeuvers.firstOrNull { it.at > cur.at + 1 } }
        val (line, color) = when {
            vm.gpsLost && !vm.demo -> "Searching for GPS…" to C.secondary
            s.status == NavStatus.OUTSIDE_AREA -> "Off the downloaded route · head back" to C.red
            s.status == NavStatus.REROUTING -> "New route found" to C.good
            !arrived && m != null && after != null && after.kind != Kind.ARRIVE && after.at - m.at < 350 ->
                "Then ${Phrases.banner(after).replaceFirstChar { it.lowercase() }}" to C.secondary
            else -> null to C.secondary
        }
        AnimatedVisibility(visible = line != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(1.dp).background(C.separator))
                Text(line.orEmpty(), style = T.subhead.copy(color = color, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun TripCard(s: NavState, vm: AppModel, modifier: Modifier) {
    val eta = System.currentTimeMillis() + (s.remainingTime * 1000).toLong()
    val remaining by animateFloatAsState(s.remaining.toFloat(), tween(1000), label = "remaining")
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 8.dp)
            .panel(RoundedCornerShape(30.dp)).padding(start = 22.dp, end = 14.dp, top = 16.dp, bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(s.remainingTime / 60).toInt().coerceAtLeast(1)}", style = T.title1.copy(fontFeatureSettings = "tnum"))
                    Text(" MIN", style = T.label, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(
                    "${Phrases.shortDistance(remaining.toDouble())} · arrive ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(eta))}" + if (vm.demo) " · demo" else "",
                    style = T.footnote,
                )
            }
            LineCapsule("End", height = 46.dp, tint = C.red) { vm.stopNavigation() }
        }
        Spacer(Modifier.height(14.dp))
        RoadAhead(s.path, s.progress, vm.navManeuvers, windowM = 4000.0, modifier = Modifier.fillMaxWidth().height(14.dp))
    }
}
