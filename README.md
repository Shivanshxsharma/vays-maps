<p align="center">
  <img src="docs/art/hero.png" alt="Vays Maps: offline navigation for Delhi, built for riders" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Shivanshxsharma/vays-maps/releases/latest"><img src="https://img.shields.io/github/v/release/Shivanshxsharma/vays-maps?style=flat-square&color=B0E454&labelColor=0D0D0D&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8%2B-B0E454?style=flat-square&labelColor=0D0D0D" alt="Android 8+">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-B0E454?style=flat-square&labelColor=0D0D0D" alt="Kotlin, Jetpack Compose">
  <img src="https://img.shields.io/badge/works-offline-B0E454?style=flat-square&labelColor=0D0D0D" alt="Works offline">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Shivanshxsharma/vays-maps?style=flat-square&color=B0E454&labelColor=0D0D0D" alt="MIT licence"></a>
</p>

<h3 align="center">Navigation that tells riders <i>which road</i> to take, by position, never by name.</h3>

<p align="center">
  <a href="https://github.com/Shivanshxsharma/vays-maps/releases/latest/download/VaysMaps.apk"><b>Download the APK</b></a>
  &nbsp;·&nbsp; <a href="#what-youll-hear">What you'll hear</a>
  &nbsp;·&nbsp; <a href="#screens">Screens</a>
  &nbsp;·&nbsp; <a href="#how-it-works">How it works</a>
  &nbsp;·&nbsp; <a href="#build-it">Build it</a>
</p>

<br>

## Why

Riding through Delhi with one earphone in, "turn onto Sri Aurobindo Marg" doesn't help. You can't read signs at 50 km/h, and half the roads have three names. What you actually need to know is simpler: **the road ahead splits, which one do I take?**

Vays Maps counts the roads you'll see at every split and tells you which one, left to right. It also knows when one of them climbs a flyover or dips into an underpass. Download a ride while you're online; ride it with no internet at all.

<br>

## What you'll hear

<table>
  <tr>
    <td width="50%"><img src="docs/art/banner-ride-split.png" alt="Banner: 430 m, Right road, 2 roads ahead, going up"></td>
    <td width="50%"><img src="docs/art/banner-ride-speed.png" alt="Banner: 340 m, Left road, 2 roads ahead"></td>
  </tr>
  <tr>
    <td><i>"In 400 metres, the road splits in two. Take the right one, going up."</i></td>
    <td><i>"The road splits in two. Take the left one."</i></td>
  </tr>
</table>

| At the split | Vays Maps says |
|---|---|
| Two roads, one goes up a flyover | "The road splits in two. Take the right one, going up." |
| Three roads (service lane, flyover, service lane) | "Three roads ahead. Take the middle one." |
| Four roads | "Four roads ahead. Take the second one from the left." |
| A junction with a signal | "At the signal, turn left." |
| A roundabout / Connaught Place | "At the roundabout, take the second exit." / "Join the circle and keep left." |
| Over the limit | "Slow down. The limit here is 60." (once, then quiet) |

The icon on screen draws every road at the split and fills in yours, so a one-second glance says the same thing as the voice.

<br>

## Screens

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/screens/home.png" alt="Home" width="260"><br><sub><b>Home</b><br>Your last ride on a real mini map, one tap to ride</sub></td>
    <td align="center" width="33%"><img src="docs/screens/search.png" alt="New ride" width="260"><br><sub><b>New ride</b><br>Search any place in Delhi</sub></td>
    <td align="center" width="33%"><img src="docs/screens/download.png" alt="Download" width="260"><br><sub><b>Download</b><br>Only the roads along your route, ~100 KB</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screens/preview.png" alt="Ride preview" width="260"><br><sub><b>Ride preview</b><br>Route options and every prompt you'll hear</sub></td>
    <td align="center"><img src="docs/screens/ride-split.png" alt="Riding" width="260"><br><sub><b>Riding</b><br>3D view, the split, your speed</sub></td>
    <td align="center"><img src="docs/screens/ride-speed.png" alt="Riding with speed limit" width="260"><br><sub><b>Speed limits</b><br>AIIMS interchange, 60 km/h zone</sub></td>
  </tr>
</table>

<br>

## Features

<table>
  <tr>
    <td width="50%" valign="top">
      <h4>Built for riders</h4>
      Prompts by position (left, middle, right, second from the left), short enough for one earphone and early enough to change lanes. Timing scales with your speed: about 40, 13 and 4 seconds ahead.
    </td>
    <td width="50%" valign="top">
      <h4>Offline, for real</h4>
      Download a ride once; GPS, voice, re-routing, speed and arrival time all work with no internet. Rides are usually 60–200 KB because only a ~250 m strip around the route is saved.
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h4>Knows Delhi's flyovers</h4>
      Every split is checked for a flyover, an underpass or a service lane within 230 m. If you stay below when you should have gone up, it notices within a few seconds and re-routes, even though the two roads are only ~15 m apart.
    </td>
    <td valign="top">
      <h4>Speed aware</h4>
      Live speedometer, the road's limit sign where OpenStreetMap has it, one calm warning when you're over, and an arrival time that learns your real pace, stops at signals included.
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h4>A map you can read at a glance</h4>
      Sand main roads, grey local roads, cream flyover decks, teal underpasses, and a lime route that appears nowhere else. Only nearby roads are drawn, and the camera glides between GPS fixes.
    </td>
    <td valign="top">
      <h4>No backend to run</h4>
      Delhi's roads live in this repo as 2,521 small tiles (21 MB in total). The app downloads the few it needs straight from GitHub. Nothing to deploy.
    </td>
  </tr>
</table>

<br>

## How it works

<p align="center"><img src="docs/art/how-it-works.png" alt="Online once per ride: search, route options, road tiles from this repo, ride pack. Offline while riding: GPS, navigator, voice, 3D map." width="100%"></p>

<details>
<summary><b>Under the hood</b></summary>
<br>

<p align="center"><img src="testdata/aiims-dhaulakuan.png" alt="Engine view of the AIIMS to Dhaula Kuan corridor: roads, flyovers in amber, the route and every decision point" width="720"><br>
<sub>What the engine sees for AIIMS → Dhaula Kuan: the downloaded corridor, flyover decks, the route and each decision point.</sub></p>

* **Guidance** (`core/Guidance.kt`, `core/Phrases.kt`): at every split, count the look-alike roads a rider will see (2–4, left to right, driveways ignored) and pick yours by position. Check each branch for a real flyover (a bridge longer than 100 m, or one named "Flyover") or an underpass within 230 m; short bridges over nallahs and railway lines are ignored. Big circles like Connaught Place get "join the circle"; two quick turns across a median become a U-turn.
* **Navigator** (`core/Navigator.kt`): snaps GPS to the route, speaks at speed-scaled distances, watches the roads you weren't meant to take for 450 m after each split (that's how "stayed below the flyover" is caught), and re-routes offline with an edge-based A* that knows India drives on the left.
* **Route matching**: OSRM's JSON rounds long OSM node ids and sometimes uses private lanes the tiles leave out, so routes are aligned to the map by coordinate, with detours and a corridor fallback.
* **Tiles** (`tools/`): a small dependency-free PBF reader cuts Delhi out of the Geofabrik northern-India extract into ~1.1 km gzip'd JSON tiles.
* **App** (`app/`): Jetpack Compose, a custom Canvas map with grid culling and a gentle 3D tilt, Lucide icons, Inter type, iOS-style transitions.

</details>

<br>

## Project layout

| Path | What's inside |
|---|---|
| [`core/`](core) | Pure Kotlin engine, no dependencies: road graph + A*, guidance, phrases, navigator, ride packs, tile loader. Runs on Android and on a PC. |
| [`app/`](app) | The Android app (Jetpack Compose). |
| [`tools/`](tools) | PC command line: cut tiles, build and print ride packs, render previews, simulate rides. |
| [`data/`](data) | Delhi road tiles the app downloads (ODbL). |
| [`testdata/`](testdata) | Sample rides around Delhi and their engine renders. |

<br>

## Build it

Needs JDK 17 and the Android SDK.

```powershell
.\gradlew :core:test                 # 12 engine tests on real Delhi data
.\gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
.\gradlew :tools:installDist         # tools/build/install/tools/bin/tools
```

<details>
<summary><b>PC tools</b></summary>

```powershell
# Refresh the Delhi tiles (northern India extract from Geofabrik, ~225 MB)
curl.exe -L -o data-src/northern-zone.osm.pbf https://download.geofabrik.de/asia/india/northern-zone-latest.osm.pbf
tools\build\install\tools\bin\tools tiles data-src\northern-zone.osm.pbf data

# Build a ride from the tiles and print everything it will say
tools\build\install\tools\bin\tools build 28.5672,77.2100 28.5918,77.1615 testdata\aiims-dhaulakuan.rpk AIIMS "Dhaula Kuan"

# Draw the engine's view of a ride, or simulate riding it
tools\build\install\tools\bin\tools render testdata\aiims-dhaulakuan.rpk testdata\aiims-dhaulakuan.png
tools\build\install\tools\bin\tools sim testdata\aiims-dhaulakuan.rpk
```

Set `RAASTA_TILES` to a folder or URL to build from other tiles (default: `data/`).

</details>

<br>

## Status

- [x] Ride download end to end in the app: search → OSRM → tiles from this repo
- [x] Position-based voice, flyover / underpass cues, offline re-routing
- [x] Speedometer, speed limits, pace-based arrival time
- [x] 12 engine tests on real Delhi routes, including "stayed below instead of taking the Raj Nagar Flyover → re-routed → still arrived"
- [ ] Road-tested on a real phone
- [ ] Background navigation (today the screen stays on)
- [ ] Hindi phrasebook
- [ ] Turn restrictions when re-routing offline
- [ ] Own routing (or on-phone routing) instead of the public OSRM demo server

<br>

## Licences

* Code: [MIT](LICENSE).
* Road tiles in `data/`: derived from OpenStreetMap, © OpenStreetMap contributors, [ODbL 1.0](data/README.md).
* Inter font: [SIL Open Font License 1.1](LICENSES/Inter-OFL.txt). Icons: [Lucide](https://lucide.dev) (ISC).

<p align="center"><br><img src="docs/art/logo.svg" width="56" alt="Vays Maps logo"><br><sub>Made for Delhi's riders.</sub></p>
