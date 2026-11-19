package com.shivansh.raasta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Bike
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Headphones
import com.composables.icons.lucide.LocateFixed
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.X
import com.composables.icons.lucide.BusFront
import com.composables.icons.lucide.Building2
import com.composables.icons.lucide.Coffee
import com.composables.icons.lucide.Fuel
import com.composables.icons.lucide.GraduationCap
import com.composables.icons.lucide.Hospital
import com.composables.icons.lucide.Hotel
import com.composables.icons.lucide.Landmark
import com.composables.icons.lucide.Plane
import com.composables.icons.lucide.Route
import com.composables.icons.lucide.ShoppingBag
import com.composables.icons.lucide.TrainFront
import com.composables.icons.lucide.TramFront
import com.composables.icons.lucide.Trees
import com.composables.icons.lucide.Utensils
import com.shivansh.raasta.core.PlaceKind
import com.shivansh.raasta.AppModel
import com.shivansh.raasta.PackItem
import com.shivansh.raasta.Screen
import com.shivansh.raasta.core.Kind
import com.shivansh.raasta.core.Maneuver
import com.shivansh.raasta.core.Phrases
import com.shivansh.raasta.core.Side
import java.util.Calendar

const val APP_NAME = "Vays Maps"

@Composable
fun RaastaApp(vm: AppModel, requestLocation: (() -> Unit) -> Unit) {
    RaastaTheme {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            AnimatedContent(targetState = vm.screen, transitionSpec = Motion.screens(), label = "screen") { s ->
                Box(Modifier.fillMaxSize().background(C.bg)) {
                    when (s) {
                        Screen.Home -> HomeScreen(vm, requestLocation)
                        Screen.NewRoute -> NewRouteScreen(vm, requestLocation)
                        is Screen.Pack -> PackScreen(vm, requestLocation)
                        Screen.Navigate -> NavScreen(vm)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- home

private val twoRoadsRight = Maneuver(0, 0.0, Kind.FORK, Side.RIGHT, 10.0, branches = 2, index = 1)

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun HomeScreen(vm: AppModel, requestLocation: (() -> Unit) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
        item {
            Spacer(Modifier.statusBarsPadding().height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.appear(0)) {
                VaysMark(38.dp, animate = true)
                Spacer(Modifier.width(12.dp))
                Wordmark(20.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(C.grouped).pressable({ vm.error = null; vm.screen = Screen.NewRoute }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Lucide.Plus, "New ride", tint = C.label, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(34.dp))
            Text(greeting(), style = T.display.copy(color = C.secondary), modifier = Modifier.appear(1))
            Text("Where to, today?", style = T.displayBold, modifier = Modifier.appear(2))
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.appear(3).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(C.grouped)
                    .pressable({ vm.error = null; vm.screen = Screen.NewRoute }).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Search, null, tint = C.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Places, metro stations, restaurants…", style = T.body.copy(color = C.secondary))
            }
        }

        val packs = vm.packs
        if (packs.isEmpty()) {
            item { EmptyHome(vm) }
        } else {
            item {
                Text("CONTINUE", style = T.label, modifier = Modifier.appear(4).padding(top = 34.dp, bottom = 12.dp))
                HeroRide(packs.first(), vm, requestLocation, Modifier.appear(4, distance = 28))
            }
            if (packs.size > 1) {
                item { Text("SAVED RIDES", style = T.label, modifier = Modifier.appear(5).padding(top = 34.dp, bottom = 6.dp)) }
                itemsIndexed(packs.drop(1), key = { _, it -> it.meta.id }) { i, item ->
                    RideRow(item, vm, Modifier.appear(6 + i))
                    if (i < packs.size - 2) Box(Modifier.padding(start = 80.dp).fillMaxWidth().height(1.dp).background(C.separator))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.appear(8).padding(top = 28.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(C.good))
                    Text("   All rides work offline, including re-routing", style = T.footnote)
                }
            }
        }
        vm.error?.let { e -> item { Text(e, style = T.footnote.copy(color = C.red), modifier = Modifier.padding(top = 16.dp)) } }
    }
}

/** The most recent ride, big: a real map of it, drawn in, with the two actions. */
@Composable
private fun HeroRide(item: PackItem, vm: AppModel, requestLocation: (() -> Unit) -> Unit, modifier: Modifier) {
    val m = item.meta
    val lp = item.preview
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxWidth().height(340.dp).clip(shape).background(MapC.land).pressable({ vm.openPack(m.id) })) {
        if (lp != null && lp.paths.isNotEmpty()) MiniMap(lp.layers, lp.paths[0], Modifier.fillMaxSize(), drawIn = true, padding = 0.12f, reserveBottom = 0.52f)
        // Scrim so text reads over the map.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.42f to Color.Transparent, 0.62f to Color(0xE6101110), 1f to Color(0xFF101110))))
        Box(Modifier.align(Alignment.TopEnd).padding(14.dp).size(36.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Icon(Lucide.ArrowUpRight, null, tint = C.label, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text("FROM ${m.fromName.uppercase()}", style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.toName, style = T.title1.copy(fontSize = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Stat(Phrases.duration(m.duration).substringBefore(" "), "MIN")
                Stat(Phrases.shortDistance(m.distance).substringBefore(" "), Phrases.shortDistance(m.distance).substringAfter(" ").uppercase())
                lp?.guidance?.firstOrNull()?.let { g -> Stat("${g.count { it.kind != Kind.ARRIVE }}", "PROMPTS") }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlossCapsule("Ride", Modifier.widthIn(min = 120.dp), icon = Lucide.Bike, height = 48.dp) {
                    vm.openPack(m.id); requestLocation { vm.startNavigation(simulate = false) }
                }
                LineCapsule("Demo", Modifier.widthIn(min = 108.dp), icon = Lucide.Play, height = 48.dp) {
                    vm.openPack(m.id); vm.startNavigation(simulate = true)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = T.headline.copy(fontSize = 18.sp, fontFeatureSettings = "tnum"))
        Text(" $unit", style = T.label, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun RideRow(item: PackItem, vm: AppModel, modifier: Modifier) {
    val m = item.meta
    Row(modifier.fillMaxWidth().pressable({ vm.openPack(m.id) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))) {
            val lp = item.preview
            if (lp != null && lp.paths.isNotEmpty()) MiniMap(lp.layers, lp.paths[0], Modifier.fillMaxSize(), padding = 0.14f)
            else Box(Modifier.fillMaxSize().background(MapC.land))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(m.toName, style = T.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("from ${m.fromName}", style = T.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Phrases.duration(m.duration), style = T.headline.copy(fontFeatureSettings = "tnum"))
            Text(Phrases.shortDistance(m.distance), style = T.footnote)
        }
        Icon(Lucide.ChevronRight, null, tint = C.tertiary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

@Composable
private fun EmptyHome(vm: AppModel) {
    Column(Modifier.padding(top = 30.dp)) {
        Column(Modifier.appear(4).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.grouped).padding(24.dp)) {
            ManeuverGlyph(twoRoadsRight, 96.dp, hollow = C.grouped)
            Spacer(Modifier.height(16.dp))
            Text("“The road splits in two.\nTake the right one.”", style = T.title2.copy(fontWeight = FontWeight.SemiBold, lineHeight = 28.sp))
            Text("No road names to remember. Just which road, counted from the left.", style = T.subhead, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(26.dp))
        listOf(
            Triple(Lucide.Download, "Download a ride", "While online. Only the roads along it, about 100 KB."),
            Triple(Lucide.Headphones, "Ride with one earphone", "Short prompts, early enough to change lanes."),
            Triple(Lucide.WifiOff, "No internet needed", "GPS and your phone's own voice. Re-routes offline."),
        ).forEachIndexed { i, (icon, title, sub) ->
            Row(Modifier.appear(5 + i).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(C.grouped), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = C.label, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, style = T.headline)
                    Text(sub, style = T.footnote)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        GlossCapsule("Plan your first ride", Modifier.appear(8).fillMaxWidth(), icon = Lucide.Bike) { vm.error = null; vm.screen = Screen.NewRoute }
    }
}

// ---------------------------------------------------------------- new route

@Composable
private fun NewRouteScreen(vm: AppModel, requestLocation: (() -> Unit) -> Unit) {
    BackHandler { if (vm.busy == null) vm.screen = Screen.Home }
    var editing by remember { mutableIntStateOf(if (vm.fromPlace == null) 0 else 1) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New ride", style = T.title2, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(C.grouped).pressable({ if (vm.busy == null) vm.screen = Screen.Home }),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.X, "Close", tint = C.label, modifier = Modifier.size(18.dp)) }
        }

        AnimatedContent(targetState = vm.busy != null, label = "busy", modifier = Modifier.weight(1f)) { busy ->
            if (busy) DownloadProgress(vm) else Column(Modifier.fillMaxSize()) {
                Column(Modifier.appear(0).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.grouped)) {
                    PlaceField(
                        active = editing == 0, label = vm.fromPlace?.name, placeholder = "Start",
                        query = query, onQuery = { query = it }, focus = focus,
                        leading = { Box(Modifier.size(12.dp).clip(CircleShape).border(3.dp, C.label, CircleShape)) },
                        onClick = { editing = 0; query = ""; vm.clearResults() },
                        onSearch = { vm.search(query) }, searching = vm.searching && editing == 0,
                    )
                    Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(C.separator))
                    PlaceField(
                        active = editing == 1, label = vm.toPlace?.name, placeholder = "Destination",
                        query = query, onQuery = { query = it }, focus = focus,
                        leading = { Icon(Lucide.MapPin, null, tint = C.blue, modifier = Modifier.size(18.dp)) },
                        onClick = { editing = 1; query = ""; vm.clearResults() },
                        onSearch = { vm.search(query) }, searching = vm.searching && editing == 1,
                    )
                }
                Spacer(Modifier.height(18.dp))
                LazyColumn(Modifier.weight(1f)) {
                    if (editing == 0 && vm.results.isEmpty()) {
                        item {
                            PlaceRow(Lucide.LocateFixed, "My location", "Use GPS for the start", Modifier.appear(1)) {
                                requestLocation {
                                    if (!vm.useMyLocation()) vm.error = "No GPS fix yet. Step outside for a moment, or search for the start."
                                    else if (vm.fromPlace != null) { editing = 1; query = ""; vm.clearResults() }
                                }
                            }
                        }
                    }
                    vm.error?.let { e -> item { Text(e, style = T.footnote.copy(color = C.red), modifier = Modifier.padding(vertical = 12.dp)) } }
                    if (vm.results.isNotEmpty()) {
                        item { Text("PLACES IN DELHI", style = T.label, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) }
                        itemsIndexed(vm.results) { i, p ->
                            val sub = listOf(p.kind.takeIf { it != PlaceKind.OTHER }?.label, p.detail.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
                            PlaceRow(iconFor(p.kind), p.name, sub, Modifier.appear(i, delayStep = 35)) {
                                if (editing == 0) { vm.fromPlace = p; editing = 1 } else vm.toPlace = p
                                query = ""; vm.clearResults()
                            }
                        }
                    }
                }
                GlossCapsule("Download ride", Modifier.fillMaxWidth(), icon = Lucide.Download, enabled = vm.fromPlace != null && vm.toPlace != null) { vm.download() }
                Text(
                    "Saves only the roads along your route, usually under 200 KB.",
                    style = T.footnote, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).navigationBarsPadding(),
                )
            }
        }
    }
}

/** A Lucide icon for each kind of place in search results. */
private fun iconFor(k: PlaceKind) = when (k) {
    PlaceKind.METRO, PlaceKind.METRO_GATE -> Lucide.TramFront
    PlaceKind.RAIL -> Lucide.TrainFront
    PlaceKind.BUS -> Lucide.BusFront
    PlaceKind.AIRPORT -> Lucide.Plane
    PlaceKind.FOOD -> Lucide.Utensils
    PlaceKind.CAFE -> Lucide.Coffee
    PlaceKind.FUEL -> Lucide.Fuel
    PlaceKind.HOSPITAL -> Lucide.Hospital
    PlaceKind.SHOP, PlaceKind.MALL -> Lucide.ShoppingBag
    PlaceKind.HOTEL -> Lucide.Hotel
    PlaceKind.EDUCATION -> Lucide.GraduationCap
    PlaceKind.WORSHIP -> Lucide.Landmark
    PlaceKind.PARK -> Lucide.Trees
    PlaceKind.LANDMARK -> Lucide.Landmark
    PlaceKind.AREA -> Lucide.Building2
    PlaceKind.ROAD -> Lucide.Route
    PlaceKind.OTHER -> Lucide.MapPin
}

@Composable
private fun PlaceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().pressable(onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(C.grouped), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.label, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = T.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaceField(
    active: Boolean, label: String?, placeholder: String, query: String, onQuery: (String) -> Unit, focus: FocusRequester,
    leading: @Composable () -> Unit, onClick: () -> Unit, onSearch: () -> Unit, searching: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(16.dp))
        if (active) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(label ?: "${placeholder}: place, metro, restaurant…", style = T.body.copy(color = C.tertiary), maxLines = 1)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = T.body, cursorBrush = SolidColor(C.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.secondary)
            else if (query.isNotEmpty()) Icon(Lucide.Search, "Search", tint = C.label, modifier = Modifier.size(20.dp).clickable(onClick = onSearch))
        } else {
            Text(label ?: placeholder, style = T.body.copy(color = if (label == null) C.tertiary else C.label), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DownloadProgress(vm: AppModel) {
    val steps = listOf("Finding routes", "Downloading Delhi road tiles", "Counting the roads at every split")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        VaysMark(64.dp, animate = true, modifier = Modifier.appear(0))
        Spacer(Modifier.height(22.dp))
        Text("Getting your ride ready", style = T.title1, modifier = Modifier.appear(1))
        Text("${vm.fromPlace?.name} → ${vm.toPlace?.name}", style = T.subhead, modifier = Modifier.appear(2).padding(top = 4.dp, bottom = 26.dp))
        steps.forEachIndexed { i, s ->
            Row(Modifier.appear(3 + i).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(targetState = (i < vm.busyStep) to (i == vm.busyStep), label = "step") { (done, now) ->
                        when {
                            done -> Icon(Lucide.CircleCheck, null, tint = C.good, modifier = Modifier.size(24.dp))
                            now -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.blue)
                            else -> Icon(Lucide.Circle, null, tint = C.tertiary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(s, style = T.body.copy(color = if (i <= vm.busyStep) C.label else C.tertiary))
                    if (i == vm.busyStep) Text(vm.busy.orEmpty(), style = T.footnote)
                }
            }
        }
    }
}
