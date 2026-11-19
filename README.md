# Vays Maps

Offline navigation for Delhi, built for **riders**. The voice tells you *which road to take by position*, so you never need to know a road's name:

> "In 300 metres, the road splits in two. Take the right one, going up."
> "Three roads ahead. Take the middle one."
> "At the signal, turn left." · "At the roundabout, take the second exit."

You download a ride while you're online. Vays Maps saves only the roads in a ~250 m strip around it (usually 60–200 KB, never all of Delhi), then guides you by GPS with no internet.

**Design:** minimal jet-black UI (#0D0D0D) with soft-ivory text (#EAFFD0). Lime green (#B0E454) is the one accent — the main button, the route and the road to take — with forest green (#224F10) for the logo tile and selected states. Lime also marks good states; red marks bad ones (end, delete, off route). Maneuver icons are keyline drawings that show every road at the split, with yours filled in.

**Map colours** follow dark navigation-map practice (road classes separated by colour *and* width, dark casings, a route colour used nowhere else): warm sand main roads, cool grey local roads, cream flyover decks, teal dashed underpasses, lime route.

**Look and motion (v0.4):** Vays mark logo (a V made of two splitting roads: the skipped road outlined, the taken road solid with an arrow) on a forest-green tile, used for the app icon and splash. Lucide outline icons. iOS-style push/pop and modal transitions, staggered entrances, spring press feedback, routes that draw themselves in, real mini maps on the home cards, an animated instruction banner with a smoothly counting distance, and a timeline of "what you'll hear".

**Speed (v0.5):** live speedometer; the road's speed limit sign when OpenStreetMap has one (about 1 in 8 Delhi roads); the speed turns red and one calm "Slow down. The limit here is 60." is spoken if you stay 5+ km/h over for 3 seconds (then quiet for ~45 s). Arrival time learns your real pace over the last couple of minutes, stops included. Prompt timing scales with speed (~40 s / 13 s / 4 s ahead), and off-route detection needs only 2 fixes above ~45 km/h.

**Smoothness:** the map glides between GPS fixes at the display's frame rate, only roads within ~750 m of the camera are drawn (300 m grid), map geometry is built off the UI thread, and there's no live blur. On the emulator the app's own work per frame is ~1 ms compose + ~1 ms draw recording + ~11 ms GPU.

## How it works

```
 online, once per route                               offline, while driving
 ─────────────────────                               ──────────────────────
 Nominatim  ── search places (Delhi only)            GPS ─► Navigator ─► speech (phone's offline TTS)
 OSRM       ── 1–3 route options                        │      ├─ snaps you to the route
 GitHub     ── Delhi road tiles along the route         │      ├─ watches every split: flyover or below?
            ▼                                           │      └─ re-routes inside the downloaded strip
 PackBuilder ─► route pack (.rpk) ──────────────────────┘
```

* **Delhi road tiles** (`data/`): Delhi's drivable roads, cut from OpenStreetMap into ~1.1 km tiles (2,521 tiles, 21 MB total, ~8 KB each). A route fetches only the tiles it passes through, from this GitHub repo (raw.githubusercontent.com, jsDelivr CDN as a backup). If the tiles can't be reached, it falls back to the Overpass API.
* **Guidance engine** (`core/Guidance.kt`): at every split it counts the roads a rider will actually see (2–4, left to right, ignoring driveways) and picks yours by position; `Phrases.kt` turns that into "take the right / middle / second-from-left one". It also compares the branches by *structure*: does a branch climb onto a bridge longer than 100 m (or one named "Flyover") within 230 m, drop into an underpass, or run onto a service road? It then says take or skip, and which side to keep to. Short bridges over nallahs and railway lines are ignored. Big circles like Connaught Place get "join the Outer Circle"; normal roundabouts get exit numbers; two quick turns across a median become a U-turn. Metro stations, petrol pumps and traffic signals are used as landmarks.
* **Wrong-branch detection** (`core/Navigator.kt`): the road under a flyover is ~15 m from the flyover, too close for "distance from route" alone. For 450 m after each split, Raasta also tracks the branches you weren't meant to take. If you're consistently closer to one of them, it re-routes immediately.
* **Route pack** (`core/Pack.kt`): gzip'd binary with the road graph, planned routes and landmarks.

## Layout

| Path | What |
|---|---|
| `core/` | Pure Kotlin engine, no dependencies: JSON, geo, road graph + A*, guidance, phrases, navigator, pack I/O, tile loader. Runs on Android and on a PC. |
| `app/` | Android app "Vays Maps" (`com.shivansh.vaysmaps`, Jetpack Compose): saved rides, new-ride download, preview with "what you'll hear", 3D riding view, demo ride. |
| `tools/` | PC CLI: cut tiles from an OSM extract, build/print/render packs, simulate drives. |
| `data/` | The Delhi road tiles that get hosted on GitHub. |
| `testdata/` | Sample packs + PNG previews (AIIMS→Dhaula Kuan, CP→Nizamuddin, ITO→Ashram, Saket→Karol Bagh). |
| `dist/` | Built APK. |

## Build

JDK 17 and the Android SDK at `D:\Android\Sdk`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
.\gradlew :core:test                 # engine tests on real Delhi data
.\gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
.\gradlew :tools:installDist         # tools/build/install/tools/bin/tools
```

## Tools

```powershell
# Refresh the Delhi tiles (northern India extract from Geofabrik, ~225 MB)
curl.exe -L -o data-src/northern-zone.osm.pbf https://download.geofabrik.de/asia/india/northern-zone-latest.osm.pbf
tools\build\install\tools\bin\tools tiles data-src\northern-zone.osm.pbf data

# Build a pack from the local tiles and print what will be said
tools\build\install\tools\bin\tools build 28.5672,77.2100 28.5918,77.1615 testdata\aiims-dhaulakuan.rpk AIIMS "Dhaula Kuan"
tools\build\install\tools\bin\tools render testdata\aiims-dhaulakuan.rpk testdata\aiims-dhaulakuan.png
tools\build\install\tools\bin\tools sim testdata\aiims-dhaulakuan.rpk
```

## Status (v0.5.0)

* Engine verified on four real Delhi routes; 12 unit tests, including "stayed below instead of taking the Raj Nagar Flyover → re-routed offline → still arrived".
* App runs on the Android emulator (Pixel 8, Android 16): home, ride preview and a full demo ride verified with screenshots. **Not yet run on a real phone or ridden.**
* Road tiles are served from this repo's `data/` folder (raw.githubusercontent.com, jsDelivr as backup; see `Config.TILE_BASES` in `app/.../Platform.kt`). Overpass is only a fallback.

## Known limits / next

* Public OSRM and Nominatim servers are for light use only. A public release needs our own OSRM (Delhi only, a small VPS) or on-phone routing over the tiles.
* Turn restrictions (OSM relations) aren't used when re-routing offline yet.
* Screen must stay on while navigating (no background foreground-service yet).
* English (India) phrases only; a Hindi phrasebook can slot into `Phrases.kt`.
* The barometer could help with "flyover vs below" detection on phones that have one.

## Licences

* Code: MIT (`LICENSE`).
* Road tiles in `data/`: derived from OpenStreetMap, © OpenStreetMap contributors, ODbL 1.0 (`data/README.md`).
* Inter font: SIL Open Font License 1.1 (`LICENSES/Inter-OFL.txt`). Icons: Lucide (ISC).
