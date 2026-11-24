package com.shivansh.raasta.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.Bike
import com.composables.icons.lucide.Briefcase
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.History
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.LocateFixed
import com.composables.icons.lucide.LocateOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPinned
import com.composables.icons.lucide.MapPinPlus
import com.composables.icons.lucide.X
import com.shivansh.raasta.AppModel
import com.shivansh.raasta.Here
import com.shivansh.raasta.LocState
import com.shivansh.raasta.Screen
import com.shivansh.raasta.core.Geo
import com.shivansh.raasta.core.LatLon
import com.shivansh.raasta.core.Phrases
import com.shivansh.raasta.core.Place
import com.shivansh.raasta.core.PlaceKind
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** What the search panel is choosing. */
enum class SearchFor { FROM, TO, HOME, WORK }

fun meDot(h: Here?, now: Long): MeDot? = h?.let {
    MeDot(World.x(it.fix.lon), World.y(it.fix.lat), it.fix.accuracy.toFloat(),
        it.fix.bearing.takeIf { b -> b >= 0 && it.fix.speed > 1.0 }?.toFloat(), now - it.at > 30_000)
}

/** Ticks every few seconds so "updated 20 s ago" stays true. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(3_000) } }
    return now
}

private class Chip(val label: String, val icon: ImageVector, val kinds: Set<PlaceKind>)

private val CHIPS = listOf(
    Chip("Metro", iconFor(PlaceKind.METRO), setOf(PlaceKind.METRO)),
    Chip("Petrol", iconFor(PlaceKind.FUEL), setOf(PlaceKind.FUEL)),
    Chip("Food", iconFor(PlaceKind.FOOD), setOf(PlaceKind.FOOD, PlaceKind.CAFE)),
    Chip("Hospital", iconFor(PlaceKind.HOSPITAL), setOf(PlaceKind.HOSPITAL)),
    Chip("Pharmacy", iconFor(PlaceKind.PHARMACY), setOf(PlaceKind.PHARMACY)),
    Chip("ATM", iconFor(PlaceKind.BANK), setOf(PlaceKind.BANK)),
    Chip("Shopping", iconFor(PlaceKind.MALL), setOf(PlaceKind.MALL, PlaceKind.MARKET)),
)

@Composable
fun PlanScreen(vm: AppModel, requestLocation: (() -> Unit) -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var searchFor by remember { mutableStateOf(if (vm.openSearch) SearchFor.TO else null) }
    LaunchedEffect(Unit) { vm.openSearch = false }
    val px = density.density
    val state = remember {
        val h = vm.here
        val cam = if (h != null && vm.inDelhi) MapCamera(World.x(h.fix.lon), World.y(h.fix.lat), 0.17f * px)
        else MapCamera(World.x(77.2167), World.y(28.6315), 0.03f * px)
        cam.minScale = 0.0065f * px
        cam.maxScale = 5f * px
        MapState(cam, scope)
    }
    var topH by remember { mutableIntStateOf(0) }
    var bottomH by remember { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(vm.here != null && vm.inDelhi && vm.toPlace == null) }
    var touched by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf<String?>(null) }

    BackHandler {
        when {
            vm.busy != null -> {}
            searchFor != null -> { searchFor = null; vm.clearResults() }
            vm.picked != null -> vm.picked = null
            vm.nearby != null -> { vm.closeNearby(); chip = null }
            else -> vm.screen = Screen.Home
        }
    }

    val here = vm.here
    val me = meDot(here, now)
    val start = if (vm.fromHere) here?.latLon?.takeIf { vm.inDelhi } else vm.fromPlace?.latLon

    // First fix: go there, unless the user is already looking somewhere else.
    LaunchedEffect(here == null, vm.inDelhi) {
        if (here != null && vm.inDelhi && !touched && vm.toPlace == null) {
            following = true
            state.flyTo(World.x(here.fix.lon), World.y(here.fix.lat), 0.17f * px, 900)
        }
    }
    LaunchedEffect(here?.fix?.lat, here?.fix?.lon) {
        if (following && here != null && vm.inDelhi && vm.toPlace == null && vm.picked == null) state.show(World.x(here.fix.lon), World.y(here.fix.lat), state.cam.scale, topH.toFloat(), bottomH.toFloat())
    }
    // Both ends known: show both.
    LaunchedEffect(vm.toPlace, vm.fromPlace, vm.fromHere, start == null, state.cam.size.width > 0f, searchFor == null) {
        val to = vm.toPlace ?: return@LaunchedEffect
        if (searchFor != null || state.cam.size.width <= 0f) return@LaunchedEffect
        delay(60)
        following = false
        val x = World.x(to.lon); val y = World.y(to.lat)
        if (start == null) state.show(x, y, 0.15f * px, topH.toFloat(), bottomH.toFloat())
        else {
            val sx = World.x(start.lon); val sy = World.y(start.lat)
            state.fit(floatArrayOf(min(x, sx), min(y, sy), max(x, sx), max(y, sy)), 60 * px, topH + 60 * px, 70 * px, bottomH + 40 * px, animate = true, maxScale = 0.5f * px)
        }
    }
    LaunchedEffect(vm.picked?.place?.lat, vm.picked?.place?.lon) {
        val p = vm.picked?.place ?: return@LaunchedEffect
        following = false
        val c = state.cam.toScreen(World.x(p.lon), World.y(p.lat))
        val sz = state.cam.size
        // Only move if it's hidden or near an edge.
        if (c.x < 40 * px || c.x > sz.width - 60 * px || c.y < topH + 30 * px || c.y > sz.height - bottomH - 60 * px)
            state.show(World.x(p.lon), World.y(p.lat), max(state.cam.scale, 0.12f * px), topH.toFloat(), bottomH.toFloat())
    }

    val focus = remember(vm.nearby) { vm.nearby.orEmpty().map { MapPoi(it.first) } }
    // Show the nearest few results together with you.
    LaunchedEffect(focus) {
        if (focus.isEmpty()) return@LaunchedEffect
        delay(80)
        following = false
        val pts = focus.take(4).map { Offset(it.x, it.y) } + listOfNotNull(here?.takeIf { vm.inDelhi }?.let { World.of(it.latLon) }?.takeIf { o -> (o - Offset(focus[0].x, focus[0].y)).getDistance() < 4000 })
        state.fit(floatArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y }), 60 * px, topH + 40 * px, 80 * px, bottomH + 50 * px, animate = true, maxScale = 0.6f * px)
    }

    val markers = buildList {
        val to = vm.toPlace
        if (start != null && (to != null || !vm.fromHere)) add(MapMarker(World.x(start.lon), World.y(start.lat), false, if (vm.fromHere) "" else vm.fromPlace?.name.orEmpty()))
        if (to != null) add(MapMarker(World.x(to.lon), World.y(to.lat), true, to.name))
    }

    Box(Modifier.fillMaxSize().background(MapC.land)) {
        LiveMap(
            state, vm.city, vm.tiles, Modifier.fillMaxSize(), on = vm.layersOn, markers = markers, me = me,
            picked = vm.picked?.place?.let { Offset(World.x(it.lon), World.y(it.lat)) },
            focus = focus, places = rememberPlaces(state.cam, vm.places, vm.myPlaces),
            onTap = { hit, p ->
                vm.error = null
                val poi = hit?.poi; val st = hit?.station
                when {
                    st != null -> vm.pick(Place(st.name, "", st.lat, st.lon, PlaceKind.METRO))
                    poi != null -> vm.pick(vm.known(poi))
                    vm.picked != null -> vm.picked = null
                    else -> state.cam.toWorld(p).let { w -> vm.pickPoint(World.latLon(w.x, w.y)) }
                }
            },
            onLongPress = { p -> state.cam.toWorld(p).let { w -> vm.pickPoint(World.latLon(w.x, w.y)) } },
            onUserMove = { touched = true; following = false },
        )

        TopScrim()

        // Top: from / to
        Column(Modifier.align(Alignment.TopCenter).onSizeChanged { topH = it.height }.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TripBox(vm, onBack = { vm.screen = Screen.Home }, onFrom = { searchFor = SearchFor.FROM }, onTo = { searchFor = SearchFor.TO })
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in CHIPS) {
                    val sel = chip == c.label && (vm.nearby != null || vm.nearbyBusy)
                    Row(
                        Modifier.height(38.dp).panel(CircleShape, if (sel) C.label else C.grouped).pressable({
                            if (sel) { chip = null; vm.closeNearby() } else {
                                chip = c.label
                                vm.picked = null
                                val w = state.cam.toWorld(Offset(state.cam.size.width / 2, state.cam.size.height / 2))
                                vm.findNearby(c.kinds, if (following && here != null) here.latLon else World.latLon(w.x, w.y))
                            }
                        }).padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(c.icon, null, tint = if (sel) C.bg else kindColor(c.kinds.first()), modifier = Modifier.size(16.dp))
                        Text("  ${c.label}", style = T.footnote.copy(color = if (sel) C.bg else C.label, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }

        // Bottom: one card, whatever matters most right now
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
                if (vm.tiles.offline && state.cam.scale / px > 0.115f) Row(
                    Modifier.height(30.dp).panel(CircleShape).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                ) { Text("Offline · basic map only here", style = T.caption) }
                Spacer(Modifier.weight(1f))
                MapButtons(
                    state, following = following, searching = vm.loc == LocState.SEARCHING,
                    extra = { LayersButton(vm) },
                    onLocate = {
                        touched = false
                        if (vm.loc == LocState.OK && here != null && !vm.inDelhi) vm.error = "You're outside Delhi, where this map ends."
                        else if (vm.loc == LocState.OK && here != null) {
                            following = true
                            state.show(World.x(here.fix.lon), World.y(here.fix.lat), max(state.cam.scale, 0.3f * px), topH.toFloat(), bottomH.toFloat())
                        } else requestLocation { vm.startLive() }
                    },
                )
            }
            Column(
                Modifier.onSizeChanged { bottomH = it.height }.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                    .panel(RoundedCornerShape(26.dp)).blockMap().animateContentSize(spring(dampingRatio = 0.86f, stiffness = 500f)),
            ) {
                val mode = when {
                    vm.picked != null -> 0
                    vm.nearby != null || vm.nearbyBusy -> 1
                    vm.toPlace != null -> 2
                    else -> 3
                }
                AnimatedContent(mode, transitionSpec = { fadeIn(tween(200, 60)) togetherWith fadeOut(tween(90)) }, label = "card") { m ->
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        when (m) {
                            0 -> PickedCard(vm, here)
                            1 -> NearbyCard(vm, chip.orEmpty()) { chip = null; vm.closeNearby() }
                            2 -> TripCardPlan(vm, start, here, now)
                            else -> HereCard(vm, here, now, requestLocation)
                        }
                        vm.error?.let { Text(it, style = T.footnote.copy(color = C.red), modifier = Modifier.padding(top = 10.dp)) }
                    }
                }
            }
        }

        AnimatedVisibility(
            searchFor != null, enter = fadeIn(tween(160)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 500f)) { it / 12 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 16 },
        ) {
            var last by remember { mutableStateOf(searchFor ?: SearchFor.TO) }
            searchFor?.let { last = it }
            SearchPanel(vm, last, start ?: here?.latLon, requestLocation,
                change = { searchFor = it; vm.clearResults() }) { searchFor = null; vm.clearResults() }
        }
        vm.adding?.let { at -> AddPlace(onDismiss = { vm.adding = null }) { name, kind -> vm.adding = null; vm.addPlace(name, kind, at) } }
        AnimatedVisibility(vm.busy != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(C.bg).blockMap().padding(horizontal = 24.dp)) { DownloadProgress(vm) }
        }
    }
}

// ---------------------------------------------------------------- top

/** Keeps the clock readable over the map. */
@Composable
fun TopScrim() = Box(Modifier.fillMaxWidth().height(90.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xE6101110), Color.Transparent))))

/** The pickup and destination symbols, the same shapes as on the map. */
@Composable
fun PickupBadge() = Box(Modifier.size(24.dp).clip(CircleShape).background(MapC.route), contentAlignment = Alignment.Center) {
    Icon(Lucide.Bike, null, tint = C.bg, modifier = Modifier.size(14.dp))
}

@Composable
fun DestinationBadge() = Box(
    Modifier.size(24.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 3.dp)).background(Ink.i100),
    contentAlignment = Alignment.Center,
) { Icon(Lucide.Flag, null, tint = C.bg, modifier = Modifier.size(13.dp)) }

@Composable
private fun TripBox(vm: AppModel, onBack: () -> Unit, onFrom: () -> Unit, onTo: () -> Unit) {
    Row(Modifier.fillMaxWidth().panel(RoundedCornerShape(24.dp)).blockMap(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 6.dp).size(44.dp).pressable(onBack), contentAlignment = Alignment.Center) {
            Icon(Lucide.ChevronLeft, "Back", tint = C.label, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            val fromText = if (vm.fromHere) "Your location" else vm.fromPlace?.name.orEmpty()
            val sub = if (!vm.fromHere) null else if (vm.inDelhi) vm.herePlace?.name else "Outside Delhi"
            Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onFrom), verticalAlignment = Alignment.CenterVertically) {
                PickupBadge()
                Column(Modifier.padding(start = 12.dp)) {
                    Text(fromText, style = T.body.copy(fontSize = T.subhead.fontSize, color = C.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub != null) Text(sub, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.padding(start = 36.dp).fillMaxWidth().height(1.dp).background(C.separator))
            Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onTo), verticalAlignment = Alignment.CenterVertically) {
                DestinationBadge()
                Text(vm.toPlace?.name ?: "Where to?", style = T.body.copy(fontSize = T.subhead.fontSize, color = if (vm.toPlace == null) C.secondary else C.label),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Box(Modifier.padding(end = 6.dp).size(44.dp).pressable({ vm.swap() }, enabled = vm.toPlace != null), contentAlignment = Alignment.Center) {
            Icon(Lucide.ArrowUpDown, "Swap", tint = if (vm.toPlace != null) C.label else C.tertiary, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun LayersButton(vm: AppModel) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(open, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            Row(Modifier.padding(end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("Metro", vm.layersOn.metro) { vm.layersOn = vm.layersOn.copy(metro = !vm.layersOn.metro) }
                Toggle("Places", vm.layersOn.places) { vm.layersOn = vm.layersOn.copy(places = !vm.layersOn.places) }
            }
        }
        Box(Modifier.size(46.dp).panel(CircleShape).pressable({ open = !open }), contentAlignment = Alignment.Center) {
            Icon(if (open) Lucide.X else Lucide.Layers, "Map layers", tint = C.label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.height(40.dp).panel(CircleShape).pressable(onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (on) Icon(Lucide.Check, null, tint = C.good, modifier = Modifier.padding(end = 6.dp).size(15.dp))
        Text(text, style = T.footnote.copy(color = if (on) C.label else C.secondary))
    }
}

// ---------------------------------------------------------------- bottom cards

private fun accuracyText(h: Here, now: Long): String {
    val age = (now - h.at) / 1000
    val acc = "Accurate to ${h.fix.accuracy.toInt().coerceAtLeast(1)} m"
    return when {
        age > 90 -> "Last seen ${age / 60} min ago · $acc"
        age > 25 -> "Last seen ${age} s ago · $acc"
        else -> "$acc · live"
    }
}

/** "You're here": the place, how sure we are, and what to do if it's wrong. */
@Composable
private fun HereCard(vm: AppModel, here: Here?, now: Long, requestLocation: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    fun open(i: Intent) { runCatching { ctx.startActivity(i) } }
    when {
        vm.loc == LocState.NO_PERMISSION -> Notice(Lucide.LocateOff, "See where you are", "Allow location so your ride can start from where you are. It stays on your phone; only the address lookup uses internet.", "Allow location") {
            requestLocation { vm.startLive() }
        }
        vm.loc == LocState.SWITCHED_OFF -> Notice(Lucide.LocateOff, "Location is switched off", "Turn it on in your phone's settings, then come back.", "Open settings") {
            open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        here == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MapC.me)
            Column(Modifier.padding(start = 16.dp)) {
                Text("Finding you…", style = T.headline)
                Text("Near a window or outside is quicker. You can also search, or hold on the map.", style = T.footnote)
            }
        }
        else -> Column {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(14.dp).clip(CircleShape).background(Color.White).padding(2.5.dp).clip(CircleShape).background(MapC.me))
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("YOU ARE HERE", style = T.label)
                    Text(if (!vm.inDelhi) "Outside Delhi" else vm.herePlace?.name ?: "%.5f, %.5f".format(here.fix.lat, here.fix.lon), style = T.title3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    vm.herePlace?.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = T.subhead, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(accuracyText(here, now), style = T.footnote.copy(color = if (here.sharp && now - here.at < 25_000) C.good else C.secondary), modifier = Modifier.padding(top = 5.dp))
                }
            }
            when {
                !vm.inDelhi -> Text("You're outside Delhi, where this map ends. Choose a start point in Delhi.", style = T.footnote.copy(color = C.red), modifier = Modifier.padding(top = 10.dp))
                vm.approximateOnly -> Text("Only approximate location is allowed. Turn on “Precise location”", style = T.footnote.copy(color = C.label),
                    modifier = Modifier.padding(top = 10.dp).clickable { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) })
                !here.sharp -> Text("Not exact yet. If the dot is off, tap the right spot on the map and choose “Start here”.", style = T.footnote, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, title: String, text: String, action: String, onClick: () -> Unit) {
    Column {
        Row {
            KeyIcon(icon)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = T.headline)
                Text(text, style = T.footnote)
            }
        }
        Spacer(Modifier.height(14.dp))
        GlossCapsule(action, Modifier.fillMaxWidth(), height = 48.dp, onClick = onClick)
    }
}

@Composable
private fun LineDots(vm: AppModel, p: Place) {
    val map = vm.city?.map ?: return
    val st = map.stations.firstOrNull { it.name == p.name && Geo.dist(it.lat, it.lon, p.lat, p.lon) < 400 } ?: return
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (l in st.lines) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 18.dp, height = 5.dp).clip(CircleShape).background(Color(map.metro[l].color)))
            Text("  ${map.metro[l].name}", style = T.footnote.copy(color = C.label))
        }
    }
}

@Composable
private fun PickedCard(vm: AppModel, here: Here?) {
    val pick = vm.picked ?: return
    val p = pick.place
    Row(verticalAlignment = Alignment.Top) {
        KeyIcon(iconFor(p.kind), tint = kindColor(p.kind))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(p.name, style = T.title3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val dist = here?.let { "${Phrases.shortDistance(Geo.dist(it.latLon, p.latLon))} away" }
            Text(listOfNotNull(p.what.takeIf { it.isNotBlank() }, vm.whereabouts(p).takeIf { it.isNotBlank() && !it.equals(p.name, true) }, dist).joinToString(" · "), style = T.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.kind == PlaceKind.METRO) LineDots(vm, p)
        }
        Box(Modifier.size(34.dp).clip(CircleShape).background(C.elevated).pressable({ vm.picked = null }), contentAlignment = Alignment.Center) {
            Icon(Lucide.X, "Close", tint = C.secondary, modifier = Modifier.size(16.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LineCapsule("Start here", Modifier.weight(1f), icon = Lucide.Bike, height = 50.dp) { vm.setFrom(p) }
        GlossCapsule("Go here", Modifier.weight(1f), icon = Lucide.Flag, height = 50.dp) { vm.setTo(p) }
    }
    if (pick.bare) Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(44.dp).pressable({ vm.adding = p.latLon }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(Lucide.MapPinPlus, null, tint = C.secondary, modifier = Modifier.size(17.dp))
        Text("  Shop or place missing here? Add it", style = T.footnote.copy(color = C.label))
    }
    if (vm.isMine(p)) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
        val ctx = LocalContext.current
        Text("Share with everyone", style = T.footnote.copy(color = C.label), modifier = Modifier.pressable({
            val body = "Name: ${p.name}\nType: ${p.kind.label}\nLocation: %.6f, %.6f\n\nAdded in the app; please include it in the next data update.".format(java.util.Locale.ROOT, p.lat, p.lon)
            val u = Uri.parse("https://github.com/Shivanshxsharma/vays-maps/issues/new").buildUpon()
                .appendQueryParameter("title", "Missing place: ${p.name}").appendQueryParameter("body", body).build()
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }
        }).padding(12.dp))
        Text("Remove", style = T.footnote.copy(color = C.red), modifier = Modifier.pressable({ vm.removePlace(p) }).padding(12.dp))
    }
}

@Composable
private fun NearbyCard(vm: AppModel, title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$title nearby", style = T.headline, modifier = Modifier.weight(1f))
        if (vm.nearbyBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.secondary)
        Box(Modifier.padding(start = 10.dp).size(34.dp).clip(CircleShape).background(C.elevated).pressable(onClose), contentAlignment = Alignment.Center) {
            Icon(Lucide.X, "Close", tint = C.secondary, modifier = Modifier.size(16.dp))
        }
    }
    LazyRow(Modifier.padding(top = 12.dp).height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.nearby.orEmpty()) { (poi, d) ->
            Column(
                Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(C.elevated)
                    .pressable({ vm.pick(vm.known(poi)) }).padding(12.dp),
            ) {
                Text(poi.name, style = T.subhead.copy(color = C.label, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Phrases.shortDistance(d), style = T.footnote.copy(color = kindColor(poi.kind)))
            }
        }
    }
}

@Composable
private fun TripCardPlan(vm: AppModel, start: LatLon?, here: Here?, now: Long) {
    val to = vm.toPlace ?: return
    val fromName = if (vm.fromHere) (vm.herePlace?.name ?: "Your location") else vm.fromPlace?.name.orEmpty()
    Text("RIDE", style = T.label)
    Text(to.name, style = T.title2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    val apart = start?.let { " · ${Phrases.shortDistance(Geo.dist(it, to.latLon))} in a straight line" }.orEmpty()
    Text("from $fromName$apart", style = T.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (vm.fromHere && here != null && (!here.sharp || now - here.at > 60_000)) {
        Text("Your location is only ${accuracyText(here, now).lowercase()}. Check the dot, or tap the right start point.", style = T.footnote.copy(color = C.label), modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LineCapsule("Clear", Modifier.weight(0.6f), height = 52.dp) { vm.setTo(null) }
        GlossCapsule("Download ride", Modifier.weight(1.4f), icon = Lucide.Download, enabled = start != null, height = 52.dp) { vm.download() }
    }
}

private val ADD_KINDS = listOf(
    "Food" to PlaceKind.FOOD, "Café" to PlaceKind.CAFE, "Shop" to PlaceKind.SHOP, "Chemist" to PlaceKind.PHARMACY, "Garage" to PlaceKind.GARAGE,
    "Salon" to PlaceKind.SALON, "Petrol" to PlaceKind.FUEL, "Clinic" to PlaceKind.HOSPITAL, "ATM" to PlaceKind.BANK, "Temple" to PlaceKind.WORSHIP,
    "School" to PlaceKind.EDUCATION, "Office" to PlaceKind.OFFICE, "Other" to PlaceKind.OTHER,
)

/** "Name, and what it is": two things are enough for a place to be found again. */
@Composable
private fun AddPlace(onDismiss: () -> Unit, onSave: (String, PlaceKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PlaceKind.FOOD) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).pressable(onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp).panel(RoundedCornerShape(26.dp)).blockMap().padding(20.dp)) {
            Text("Add a place", style = T.title3)
            Text("Saved on your phone. It shows on the map and in search.", style = T.footnote)
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(C.elevated).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("Name, e.g. Shree Durga Dosa Corner", style = T.body.copy(color = C.tertiary), maxLines = 1)
                BasicTextField(name, { name = it.take(60) }, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(C.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, k) in ADD_KINDS) {
                    val sel = k == kind
                    Row(Modifier.height(38.dp).clip(CircleShape).background(if (sel) C.label else C.elevated).pressable({ kind = k }).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(k), null, tint = if (sel) C.bg else kindColor(k), modifier = Modifier.size(15.dp))
                        Text("  $label", style = T.footnote.copy(color = if (sel) C.bg else C.label))
                    }
                }
            }
            GlossCapsule("Save place", Modifier.padding(top = 16.dp).fillMaxWidth(), enabled = name.trim().length >= 2, height = 50.dp) { onSave(name, kind) }
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
private fun SearchPanel(vm: AppModel, what: SearchFor, near: LatLon?, requestLocation: (() -> Unit) -> Unit, change: (SearchFor) -> Unit, close: () -> Unit) {
    var query by remember(what) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(what) { delay(80); runCatching { focus.requestFocus() } }
    fun choose(p: Place) {
        when (what) {
            SearchFor.FROM -> vm.setFrom(p)
            SearchFor.TO -> vm.setTo(p)
            SearchFor.HOME -> { vm.saveHome(p); change(SearchFor.TO); return }
            SearchFor.WORK -> { vm.saveWork(p); change(SearchFor.TO); return }
        }
        close()
    }
    Column(Modifier.fillMaxSize().background(C.bg).blockMap().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).height(56.dp).clip(RoundedCornerShape(20.dp)).background(C.grouped), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 4.dp).size(46.dp).pressable(close), contentAlignment = Alignment.Center) { Icon(Lucide.ChevronLeft, "Back", tint = C.label, modifier = Modifier.size(22.dp)) }
            when (what) { SearchFor.FROM -> PickupBadge(); SearchFor.TO -> DestinationBadge(); else -> {} }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (query.isEmpty()) Text(
                    when (what) { SearchFor.FROM -> "Start from…"; SearchFor.TO -> "Where to?"; SearchFor.HOME -> "Your home address"; SearchFor.WORK -> "Your work address" },
                    style = T.body.copy(color = C.tertiary), maxLines = 1,
                )
                BasicTextField(
                    value = query, onValueChange = { query = it; vm.suggest(it) }, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(C.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (vm.searching) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(18.dp), strokeWidth = 2.dp, color = C.secondary)
            else if (query.isNotEmpty()) Box(Modifier.size(46.dp).pressable({ query = ""; vm.clearResults() }), contentAlignment = Alignment.Center) { Icon(Lucide.X, "Clear", tint = C.secondary, modifier = Modifier.size(18.dp)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (query.isBlank()) {
                if (what != SearchFor.TO) item {
                    val sub = when (vm.loc) {
                        LocState.OK -> vm.herePlace?.name ?: "Ready"
                        LocState.SEARCHING -> "Finding you…"
                        LocState.SWITCHED_OFF -> "Location is switched off"
                        LocState.NO_PERMISSION -> "Tap to allow"
                    }
                    Row2(Lucide.LocateFixed, "Your current location", sub, null, MapC.me) {
                        requestLocation {
                            vm.startLive()
                            if (what == SearchFor.FROM) { vm.setFrom(null); close() } else vm.hereAsPlace()?.let { choose(it) }
                        }
                    }
                }
                item { Row2(Lucide.MapPinned, "Choose on the map", "Tap any place, or hold anywhere", null) { close() } }
                if (what == SearchFor.FROM || what == SearchFor.TO) {
                    item { SavedRow(Lucide.House, "Home", vm.home, near, { choose(it) }) { change(SearchFor.HOME) } }
                    item { SavedRow(Lucide.Briefcase, "Work", vm.work, near, { choose(it) }) { change(SearchFor.WORK) } }
                }
                if (vm.recents.isNotEmpty()) {
                    item { Text("RECENT", style = T.label, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                    items(vm.recents) { p -> Row2(if (p.kind == PlaceKind.OTHER) Lucide.History else iconFor(p.kind), p.name, p.detail, dist(near, p)) { choose(p) } }
                }
            } else {
                vm.error?.let { item { Text(it, style = T.footnote.copy(color = C.red), modifier = Modifier.padding(vertical = 10.dp)) } }
                itemsIndexed(vm.results) { _, p ->
                    val sub = listOf(p.what, vm.whereabouts(p)).filter { it.isNotBlank() && !it.equals(p.name, true) }.joinToString(" · ")
                    Row2(iconFor(p.kind), p.name, sub, dist(near, p), kindColor(p.kind)) { choose(p) }
                }
                if (!vm.searchedOnline && !vm.searching) item {
                    Row2(Lucide.Globe, if (vm.results.isEmpty()) "Look online for “${query.trim()}”" else "Not here? Look online too", "Exact addresses and newer places", null) { vm.search(query) }
                }
            }
        }
    }
}

private fun dist(near: LatLon?, p: Place) = near?.let { Phrases.shortDistance(Geo.dist(it, p.latLon)) }

@Composable
private fun SavedRow(icon: ImageVector, label: String, p: Place?, near: LatLon?, onChoose: (Place) -> Unit, onSet: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (p == null) Row2(icon, label, "Tap to set", null) { onSet() }
            else Row2(icon, label, p.name, dist(near, p)) { onChoose(p.copy(name = p.name)) }
        }
        if (p != null) Text("Edit", style = T.footnote, modifier = Modifier.clickable(onClick = onSet).padding(12.dp))
    }
}

@Composable
private fun Row2(icon: ImageVector, title: String, sub: String, right: String?, tint: Color = C.label, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(C.grouped), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = T.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (right != null) Text(right, style = T.footnote.copy(fontFeatureSettings = "tnum"))
    }
}
