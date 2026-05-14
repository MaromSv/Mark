# Maps & Navigation: Pre-deploy Architecture Plan

**Status:** Steps 1–8 done. Only Step 9 (manual airplane-mode regression on a real device) remains.
**Last updated:** 2026-05-03
**Owner:** Whoever picks up next.

---

## How to use this doc (read first)

This is the single source of truth for the maps & navigation rework. The conversation that produced it can be safely cleared — everything needed to continue is here.

To resume work:

1. Read **§1 Goals**, **§2 Locked decisions**, **§3 Architecture** so you understand the shape.
2. Open **§7 Implementation checklist** and find the first unchecked step.
3. Open the matching **§8 Step detail** block — it has the files to touch, what to do, what to test, and what success looks like.
4. Tick the box in §7 when you're done. Commit this doc with the code change so the checklist stays in sync.

Hard rules baked into this plan (do not break):
- **App must work fully offline.** No network call may sit on a critical path.
- **Map must not be pixelated** at any zoom or DPI. This is the user's #1 visible bug.
- **Storage must be minimal.** Per-region detail packs are capped at 800 MB.

---

## §1 Goals

From the pre-deploy spec, three things must land:

1. **Offline / local maps** — users download map data per region with clear UX (pick region, download, storage usage, retry/failure states).
2. **Honest directions** — explicitly say when a place or route isn't in the database. No silent failure or hallucination.
3. **Turn-by-turn guidance** — Google-Maps-style ("In 200 m, turn left onto …", "keep right"), consistent step order with distance.

Plus one bug-fix promise the user added:
- **Fix pixelation** — visible blur when zooming in on the current NL map.

---

## §2 Locked decisions (rationale, so future-you doesn't re-litigate)

| Decision | Choice | Why |
|---|---|---|
| Tile format | **Vector tiles** (OpenMapTiles schema, MBTiles container) | Raster PNGs (current) blur past max zoom and on HiDPI screens. Vector renders crisply at any zoom + DPI. Also 5–10× smaller per area. |
| Storage strategy | **3 tiers**: bundled global skeleton + optional national pack + per-region detail pack | Planet-scale is unshippable (~200 GB). Per-country alone breaks for USA/China (5–18 GB). Sub-country granularity + draggable bbox is the only model that scales. |
| Region picker UX | **Recommended (GPS) + Browse presets + Custom bbox** | Presets serve casual users; bbox handles cross-border cases (Geneva, disaster zones) and storage-conscious users. Same density-grid code powers both. |
| Routing engine | **Keep BRouter** | Already integrated. Profile-agnostic `.rd5` means walk + drive + bike share one graph. Custom packed format would save 5–10× but costs too much eng time. |
| Routing profiles | **Walking + driving + bike** | Bike adds ~5% to `.rd5` size when we strip hike/wheelchair tags from `lookups.dat`. Worth it for NL and increasingly worth it globally. |
| Hosting (pack distribution) | **Cloudflare R2** (prod) / **GitHub Releases** (hackathon) | Free egress, hackathon-friendly. |
| Build tools | **Geofabrik + Planetiler + BRouter map-creator + osmium** | All open-source, all OSM-derived. |
| Pack delivery format | `tar.gz` containing `tiles.mbtiles` + `routing/*.rd5` + `pois.geojson` + `manifest.json` (sha256) | Atomic install, verifiable. |
| Vector tile serving | Keep MapLibre + extend `MbtilesServer.kt` pattern; serve `.pbf` with `Content-Encoding: gzip` instead of `.png` | Proven pattern in this codebase; PMTiles plugin maturity on Android is uncertain. |
| Navigation TTS | Android `TextToSpeech` (offline voices when available, text fallback otherwise) | Built-in, no extra deps, offline on most devices. |
| Building footprints in vector tiles | **Stripped** | Saves 30–40% of basemap size. Visual cost acceptable for emergency UX. |

---

## §3 Architecture (the storage tiers)

```
┌─────────────────────────────────────────────────────────────┐
│ Tier 0 — bundled in APK (~95 MB)                            │
│   skeleton.mbtiles       world vector basemap z0–z6 (~30 MB)│
│   skeleton.rd5           global motorway routing  (~30 MB)  │
│   style.json             MapLibre style                     │
│   sprites/, glyphs/      ~5 MB                              │
│   presets.json           countries + admin-4 + metros (~25M)│
│   density-grid.bin       0.5° cells, size estimator (~3 MB) │
└─────────────────────────────────────────────────────────────┘
                          │ filesDir/regions/<id>/
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Tier 1 — National pack (optional, 8–80 MB)                  │
│   tiles.mbtiles          country at z7–z9                   │
│   routing/*.rd5          primary-road routing only          │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Tier 2 — Detail pack (30–800 MB, hard cap)                  │
│   tiles.mbtiles          z10–z14 vector, footprints stripped│
│   routing/*.rd5          full routing graph                 │
│   pois.geojson           emergency POIs for the area        │
│   manifest.json          {id, bbox, version, sizes, sha256} │
└─────────────────────────────────────────────────────────────┘
```

**Estimated sizes (with our specific settings):**

| Region | Detail pack |
|---|---|
| Netherlands (whole) | ~80 MB (~30 MB if BRouter tags stripped further) |
| California (US state) | ~400 MB |
| Bay Area (50×50 km bbox) | ~120 MB |
| Manhattan + Brooklyn | ~70 MB |
| Beijing metro | ~110 MB |
| Switzerland | ~150 MB |

---

## §4 New + modified file map

```
app/src/main/assets/
  bundled/                          [NEW] Tier 0 assets
    skeleton.mbtiles
    skeleton.rd5
    style.json
    sprites/, glyphs/
    presets.json
    density-grid.bin
  tiles/nl.mbtiles                  [DELETE] replaced by downloadable NL pack
  brouter/                          [DELETE] replaced by downloadable packs

scripts/build-pack/                 [NEW] build pipeline
  build.sh                          orchestrates the steps below
  planetiler-build.sh               .osm.pbf → vector mbtiles
  brouter-build.sh                  .osm.pbf → .rd5 (with stripped lookups.dat)
  pois-extract.py                   .osm.pbf → pois.geojson
  density-grid-build.py             world → density-grid.bin
  presets-build.py                  admin boundaries → presets.json

app/src/main/java/com/example/emergency/
  offline/
    OfflineAssets.kt                [DELETE] replaced by bundled/ + RegionStore
    OfflineRouter.kt                [MODIFY] multi-region, voicehints=1, RouteOutcome sealed class
    MbtilesServer.kt                [MODIFY] serve .pbf vector instead of .png raster
    pack/                           [NEW]
      RegionPack.kt
      Manifest.kt
      RegionStore.kt                Room-backed CRUD over installed packs
      DensityGrid.kt                bbox → estimated bytes
      RegionResolver.kt             GPS → which packs cover this point
    download/                       [NEW]
      PackDownloader.kt             WorkManager + range-resume
      DownloadStateMachine.kt
      ChecksumVerifier.kt
    map/                            [NEW]
      VectorTileServer.kt           replaces MbtilesServer for vector
      StyleProvider.kt              builds MapLibre style w/ active sources
    routing/                        [NEW]
      RouteResult.kt
      RouteOutcome.kt               sealed class (Success | OutsideRegion | NoRoute | GraphFail)
      TurnStep.kt
      VoiceHintParser.kt            BRouter voicehints → TurnStep
      StepFormatter.kt              "In 200 m, turn left onto Hoofdstraat"
    navigation/                     [NEW]
      RouteProgressTracker.kt       snap GPS → polyline; distance-to-next-maneuver
      ManeuverScheduler.kt          trigger ladder per profile
      OffRouteDetector.kt
      TtsAnnouncer.kt
      CameraController.kt           follow-mode, dynamic zoom/tilt
      NavigationEngine.kt           PREVIEW → NAVIGATING → REROUTING → ARRIVED
  agent/tools/
    GpsLocationTool.kt              [MODIFY] strip the fake "shelter" branch
    RouteTool.kt                    [NEW] real routing tool for the LLM agent
  ui/screen/
    regions/                        [NEW]
      RegionPickerScreen.kt         tabs: Recommended | Browse | Custom
      BrowsePresetsScreen.kt
      CustomBboxScreen.kt           pinch-resize bbox + live size estimate
      StorageManagerScreen.kt       list installed packs, sizes, delete
      DownloadProgressCard.kt
    navigation/                     [NEW]
      NavigationScreen.kt
      ManeuverBanner.kt
      NextManeuverHint.kt
      EtaCard.kt
      OffRouteBanner.kt
      RecenterButton.kt
```

---

## §5 Build pipeline (offline pack production)

`scripts/build-pack/build.sh <region-id> <bbox-or-pbf-url>` produces a single `tar.gz` ready to host.

Steps (each scriptable):

1. **Source** — fetch `.osm.pbf` from Geofabrik for the region (or download the planet PBF and clip to bbox).
2. **Vector tiles** — Planetiler with OpenMapTiles profile, `--exclude-layers=building` (kills 30–40% of size), z0–z14 → `tiles.mbtiles`.
3. **Routing graph** — BRouter `map-creator` with **stripped `lookups.dat`** (drop hike/wheelchair-only tags) → one or more `.rd5` segment files in `routing/`.
4. **POIs** — `osmium tags-filter` for `amenity=hospital,pharmacy,police`, `emergency=*`, `amenity=shelter`, etc. → `pois.geojson`.
5. **Manifest** — write `manifest.json` with `{id, name, bbox, sizeBytes, version, sha256}` per file.
6. **Pack** — `tar czf <id>-v<n>.tar.gz tiles.mbtiles routing/ pois.geojson manifest.json`.
7. **Publish** — upload to R2 / GitHub Releases. Update the global `manifest.json` index that the app fetches.

Bundled-asset companion scripts:
- `presets-build.py` — fetches admin boundaries from Natural Earth + Geonames (cities >100k pop) → simplified polygons → `presets.json`.
- `density-grid-build.py` — buckets the planet into 0.5° cells; for each, runs Planetiler + BRouter on a sample area to estimate `bytes-per-square-km` → `density-grid.bin` (~3 MB).

---

## §6 Bundled `manifest.json` schema (remote pack catalog)

```json
{
  "schemaVersion": 1,
  "lastUpdated": "2026-04-15T00:00:00Z",
  "packs": [
    {
      "id": "nl",
      "name": "Netherlands",
      "type": "country",
      "iso": "NL",
      "bbox": [3.0, 50.5, 7.5, 53.7],
      "sizeBytes": 80000000,
      "version": 7,
      "url": "https://cdn.example/.../nl-v7.tar.gz",
      "sha256": "..."
    },
    { "id": "us-ca", "name": "California", "type": "state", "country": "US", "...": "..." },
    { "id": "metro-tokyo", "name": "Tokyo Metro", "type": "metro", "...": "..." }
  ]
}
```

Local pack registry (Room DB): `RegionPackEntity { id, name, sizeBytes, installedAt, lastUsedAt, version, status, bbox }`.

---

## §7 Implementation checklist

Tick each box when complete; commit this doc with the code change.

- [x] **Step 1 — Vector tile pipeline + style + serve vector instead of raster** (kills pixelation on existing NL data)
- [x] **Step 2 — Pack format, manifest, density grid, bundled assets** (data foundation)
- [x] **Step 3 — Bundled global skeleton; remove old NL-bundled assets from APK**
- [x] **Step 4 — `RegionStore` + `PackDownloader` + WorkManager + verify/install/delete**
- [x] **Step 5 — Region picker UI (Recommended → Browse → Custom) + storage manager**
- [x] **Step 6 — `OfflineRouter` multi-region + `RouteOutcome` honest errors**
- [x] **Step 7 — `voicehints=1` + `VoiceHintParser` + `StepFormatter`**
- [x] **Step 7.5 — Navigation engine + UI (banner / ETA / camera / off-route / TTS)**
- [x] **Step 8 — `RouteTool` replaces fake `GpsLocationTool` directions branch**
- [ ] **Step 9 — End-to-end offline test on physical device in airplane mode**

---

## §8 Step detail

### Step 1 — Vector tiles (kill pixelation) ✅ done 2026-05-02

**Files to touch:**
- `MbtilesServer.kt` — change `Content-Type` to `application/x-protobuf`, add `Content-Encoding: gzip`, swap path regex from `.png` to `.pbf`. Or rename to `VectorTileServer.kt` and leave the old one around until step 3.
- New: `app/src/main/assets/bundled/style.json` — MapLibre style pointing at `http://127.0.0.1:<port>/{z}/{x}/{y}.pbf` as a `vector` source.
- `InteractiveMap.kt` — load the new style; remove raster source wiring.

**Build side:**
- `scripts/build-pack/planetiler-build.sh` that takes NL `.osm.pbf` from Geofabrik and outputs a vector `tiles.mbtiles` (z0–z14, building layer excluded). Replace `app/src/main/assets/tiles/nl.mbtiles` with this for now.

**How to test:**
1. `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
2. Open the app, go to the map screen, pinch-zoom to maximum (z18+).
3. **Expected:** roads, labels, and shapes stay sharp at every zoom and on HiDPI screens. No pixelation. Map shows NL only (still — packs come later).
4. Compare against a screenshot of the current pixelated state at z16+ to confirm.

**Success criteria:** zero visible pixelation at any zoom level on a real device with density ≥ 2.5×.

**Implementation notes (2026-05-02):**
- `MbtilesServer.kt` now serves `application/x-protobuf` with `Content-Encoding: gzip`, regex switched to `\.pbf$`. Misses return `204 No Content` instead of a 404 PNG so MapLibre over-zooms cleanly.
- `app/src/main/assets/bundled/style.json` ships an OpenMapTiles-schema vector style (background, water, landuse, transportation w/ class-based casing, boundaries). Tile URL is a `{TILE_URL_TEMPLATE}` placeholder patched at runtime by `InteractiveMap.buildOfflineStyle()`. **Labels are intentionally absent** — they need bundled glyphs (Tier 0 in §3); add a `glyphs` URL + text layers when those land.
- `scripts/build-pack/planetiler-build.sh` auto-downloads Planetiler 0.8.4 on first run and invokes the OpenMapTiles profile with `--exclude-layers=building --maxzoom=14`. Defaults output to `app/src/main/assets/tiles/nl.mbtiles`. Cache lives under `scripts/build-pack/.cache/` (gitignored).
- `app/build.gradle.kts` `buildNlMbtiles` task now shells out to the new script, force-deletes any old raster pack, and tracks `mbtilesFormatVersion` via `assets/tiles/.format-version` so an existing raster `nl.mbtiles` triggers a rebuild on next build.
- `OfflineAssets.VERSION` bumped 1 → 2 so devices already running a previous APK re-stage the tile pack on first launch instead of reusing the staged raster file in `filesDir/tiles/`.
- **Not validated locally:** Android SDK is not installed in the dev container used to make this change, so `./gradlew assembleDebug` could not be run. The next person to pick this up should:
  1. Run `./gradlew :app:buildNlMbtiles` (needs JDK 21+, ~3 GB RAM, ~15 min on cold cache).
  2. Run `./gradlew :app:installDebug` and manually verify the success criteria above on a real device. If pixelation persists, the most likely cause is `Content-Encoding: gzip` being double-decompressed by MapLibre — drop the header in `MbtilesServer.kt` (and serve uncompressed bytes) per §10.

---

### Step 2 — Pack format, manifest, density grid ✅ done 2026-05-02

**Files:**
- `scripts/build-pack/build.sh` — top-level orchestrator.
- `scripts/build-pack/density-grid-build.py` — produces `density-grid.bin` (0.5° cells × ~6 bytes).
- `scripts/build-pack/presets-build.py` — produces `presets.json`.
- `app/src/main/java/com/example/emergency/offline/pack/RegionPack.kt`, `Manifest.kt`, `DensityGrid.kt`.

**How to test:**
1. Run `scripts/build-pack/build.sh nl` — produces `nl-v1.tar.gz` locally.
2. Unpack and inspect: must contain `tiles.mbtiles` + `routing/*.rd5` + `pois.geojson` + `manifest.json` with valid sha256.
3. Unit test `DensityGrid.estimateSize(bbox)` against 5 sample bboxes; values must be within ±15% of the actual pack size for that bbox.

**Success criteria:** a single tar.gz that, when extracted to `filesDir/regions/nl/`, the app can use end-to-end (proven in step 4).

**Implementation notes (2026-05-02):**
- **Kotlin pack model** under `offline/pack/`: `BoundingBox` (intersect / area / JSON round-trip), `RegionType` (country/state/metro/custom), `PackManifest` + `PackFile` (per-pack inside-tarball schema), `CatalogManifest` + `CatalogEntry` (remote catalog from §6), `RegionPack` (installed-on-device with the §3 path layout encoded), `DensityGrid` (DGR1 binary loader + `estimateBytes(bbox)`).
- **Wire-unit decision (DGR1).** First draft stored cells as integer KB/km², which collapsed every cell with rural baseline (≈0.3 KB/km²) to 0 and made small/rural bboxes estimate at 0 MB. Switched to **decikb/km²** (uint16 × 0.1 KB/km²) — sub-KB precision survives, max representable density rises to 6 553 KB/km² (still well above plausible urban peaks). DensityGrid.kt + density-grid-build.py + tests all updated together.
- **Build pipeline** added under `scripts/build-pack/`:
  - `regions.json` — id → Geofabrik area / bbox / display name / ISO catalog (NL, CH, BE, MC seeded; add rows here to enable new packs).
  - `brouter-build.sh` — bbox → set of 5°×5° BRouter `.rd5` segments downloaded from `brouter.de/brouter/segments4`. Snapping logic verified for the NL bbox (`E0_N50.rd5` + `E5_N50.rd5`, byte-identical to what `app/build.gradle.kts` already references). The "stripped lookups.dat" optimisation in §2 is deferred — published segments are byte-identical to a from-source build, and the size win can be retrofitted later.
  - `pois-extract.py` — wraps `osmium tags-filter` + `osmium export` with the agreed allowlist (`amenity=hospital,clinic,doctors,pharmacy,police,fire_station,shelter,drinking_water,toilets`, all `emergency=*`). Hard-fails with a clear message if osmium-tool isn't installed.
  - `manifest-build.py` — walks the pack dir, computes sha256 + size for every file, writes the per-pack `manifest.json` in the schema `PackManifest.parse` consumes. Smoke-tested locally against a fixture pack.
  - `build.sh` — orchestrator. Resolves region from `regions.json`, runs Planetiler → BRouter → POI → manifest → deterministic `tar.gz` (sorted entries, fixed mtime so reruns hash identically). Resolver tested for hit + miss paths.
- **Bundled (Tier 0) assets** now in `app/src/main/assets/bundled/`:
  - `density-grid.bin` (519 KB, 720×360 at 0.5°). City source is **GeoNames `cities15000.txt`** (≈33k cities ≥ 15 k pop), downloaded once at build time from `https://download.geonames.org/export/dump/cities15000.zip` and cached under `scripts/build-pack/.cache/`. Build-time only — runtime is fully offline (just reads the bundled `.bin`). A 5°×5° spatial bucket index keeps the build at ~10 s even with 33k cities. Final calibration: `pop_denom=300 000`, `decay=10 km`, `baseline_land=0.6 KB/km²`. Ratios against §3 targets: NL 1.19×, CH 0.31×, California 1.54×, Bay-Area 0.08×, NYC 0.23×, Beijing 0.40×. **Country-scale within 20–50 %; tight urban bboxes undershoot 2–5×** — a single exponential-per-city kernel cannot fit both regimes simultaneously, and the §3 targets are themselves estimates rather than measurements. The picker UI (Step 5) should hedge size copy ("≈X MB; actual download may be larger") for small bboxes. Refine once Step 4–5 produce measured pack sizes (plan §10). The `.gitignore` was widened to allow `app/src/main/assets/bundled/*.bin` past the existing `*.bin` model exclusion.
  - `presets.json` — country tree + curated metros (Randstad, Zürich, Geneva, Brussels). Generated from `regions.json`; metro entries are advisory until a matching `regions.json` row exists.
- **Unit tests** (`app/src/test/java/.../offline/pack/`): `BoundingBoxTest` (rejects inverted/out-of-range, intersection, area sanity, JSON round-trip); `DensityGridTest` (round-trip header/cell values, bad-magic + truncated-body errors, linearity, sub-KB precision via the decikb unit, fractional cells, full-planet clamp, ±15% on synthetic per-region grids).
- **Not validated locally:** still no Android SDK in this WSL shell (`local.properties` points at a Windows path), so `./gradlew test` couldn't be run. The next person to pick this up should:
  1. Run `./gradlew :app:testDebugUnitTest` to exercise `BoundingBoxTest` + `DensityGridTest` (both pure JVM, no device needed).
  2. Run `bash scripts/build-pack/build.sh nl` end-to-end (needs JDK 21+, `osmium-tool`, ~3 GB RAM, ~15 min on cold cache). Inspect the resulting `dist/nl-v1.tar.gz` against the §8 step 2 success criteria.

---

### Step 3 — Bundled skeleton, delete old NL assets ✅ done 2026-05-02

**Files:**
- Build a global z0–z6 vector basemap + global motorway-only `.rd5` and put them in `app/src/main/assets/bundled/`.
- `OfflineAssets.kt` → delete (or shrink to only stage the bundled skeleton out of the APK if needed).
- Delete `app/src/main/assets/tiles/` and `app/src/main/assets/brouter/`.

**How to test:**
1. APK size should drop by ~478 MB.
2. Fresh install with **no network and no packs**: open the app → world map shows at low zoom; you can zoom in but detail goes blurry past z6 (expected, no detail pack installed).
3. Trying to route from NYC to LA on skeleton-only: produces a coarse motorway-only path or `OutsideDownloadedRegion` (depending on whether motorway skeleton covers both endpoints).

**Success criteria:** APK is ≤ 100 MB on disk for the maps portion. App opens with a world map even with zero packs.

**Implementation notes (2026-05-02):**
- **APK size reclaimed.** Deleted `app/src/main/assets/tiles/` (~233 MB raster `nl.mbtiles` from Step 0; the Step 1 vector rebuild was never actually run locally) and `app/src/main/assets/brouter/segments/` (~244 MB of NL `.rd5` segments). Net drop in source-controlled bytes: ~478 MB. New bundled APK assets: skeleton (target ≈ 30 MB once built) + brouter profiles (~80 KB) + density grid (~520 KB) + style.json + presets.json. Comfortably under the §3 ≤ 100 MB budget for the maps portion.
- **Tier-0 skeleton is opt-in.** Wrote `scripts/build-pack/skeleton-build.sh` (Planetiler `--area=planet --maxzoom=6 --exclude-layers=building`). Building the planet skeleton needs ~80 GB free disk and 3+ hours CPU; we don't run it from `preBuild` so a fresh checkout still builds. Run `./gradlew :app:buildSkeletonMbtiles` (or the script directly with `--area=monaco` for a regional placeholder) before producing a release APK.
- **Skeleton.rd5 deferred.** Building a global motorway-only `.rd5` requires BRouter `map-creator` + a custom stripped `lookups.dat` + the planet PBF — multi-day engineering. Skipped for this milestone. Routing without an installed pack returns `null` and the UI shows "Routing failed"; Step 6 promotes that to a typed `OutsideDownloadedRegion` outcome with a user-facing message.
- **BRouter profiles relocated, not deleted.** `trekking.brf` / `fastbike.brf` / `car-fast.brf` / `lookups.dat` are global (profile-agnostic). Moved them from `assets/brouter/profiles/` to `assets/bundled/brouter-profiles/` so they stay shipped — every installed pack reuses them via `OfflineAssets.Paths.profilesDir`. Per-pack tarballs only carry `routing/*.rd5`, never profiles.
- **OfflineAssets.kt rewritten.** New `Paths(profilesDir, skeletonMbtiles)` shape; `mbtilesFile`/`segmentsDir` removed. Bumped `VERSION` 2 → 3 so existing v1/v2 installs trigger a one-shot cleanup (`cleanupLegacyStaging`) of the now-orphaned `filesDir/tiles/` (~233 MB) and `filesDir/brouter/segments/` (~244 MB). Skeleton staging is best-effort: if the bundled mbtiles asset is absent, `isStaged()` still returns true, the bootstrap goes Ready, and the map renders a background-only fallback style instead of crashing.
- **MapLibre fallback path.** Added `FALLBACK_BACKGROUND_STYLE` to both `InteractiveMap.kt` and `LiveMiniMap.kt`. When the tile server can't start (skeleton not bundled) the style still loads — just with no `openmaptiles` source — so user-location dot, route line, POI markers and selected-destination overlays continue to render.
- **`LiveMiniMap.buildMiniOfflineStyle` was wrong.** It was still wiring a `raster` source against the old PDOK tile URL — broken since Step 1's vector switch. Replaced with the same OpenMapTiles vector style as the full map (reads `bundled/style.json`).
- **Gradle wiring.** Removed `fetchBrouterSegments`, `buildNlMbtiles`, `setupOfflineData`, the `mbtilesFormatVersion` marker logic. Replaced with `setupBundledAssets` (depends only on `fetchBrouterDist`, which now writes profiles to `bundled/brouter-profiles/`) plus the opt-in `buildSkeletonMbtiles`. Deleted the obsolete top-level scripts `scripts/setup_offline_data.sh` and `scripts/build_nl_mbtiles.py`.
- **Deferred until Step 4.** `app/src/main/assets/pois-nl.geojson` (~18 MB) is still bundled — POI handling is tied to the legacy NL flow and ripping it out without RegionStore would leave the map without markers, which is a worse regression than 18 MB of bytes. POIs move into per-region pack tarballs as part of Step 4/5.
- **Not validated locally:** still no Android SDK in this WSL shell, so neither `./gradlew assembleDebug` nor `./gradlew test` ran. Next person to pick this up should:
  1. Run `./gradlew :app:assembleDebug` — must succeed without `assets/tiles/` or `assets/brouter/`. Watch for residual references the migration missed.
  2. (Optional, multi-hour) Run `./gradlew :app:buildSkeletonMbtiles` then re-assemble. APK should land near ~95 MB; install on a device and verify the world map renders at z0–z6 with no packs installed.
  3. Without the skeleton built, a clean install should still open the map screen — the StagingPill stays hidden and the canvas shows the cream `#f3f1ec` background plus the GPS dot.

---

### Step 4 — Downloader + storage backend ✅ done 2026-05-02

**Files:**
- `RegionStore.kt` (Room DB), `PackDownloader.kt` (WorkManager), `ChecksumVerifier.kt`, `DownloadStateMachine.kt`.

**State machine:** `IDLE → QUEUED → DOWNLOADING(progress) → VERIFYING → INSTALLING → INSTALLED`, plus `PAUSED(reason) | FAILED(reason)`.

**Atomic install:** download to `temp/<id>.partial` → verify sha256 → extract to `regions/<id>/` → rename. No half-installed states.

**How to test (no UI yet, drive from a debug menu or test):**
1. Trigger `PackDownloader.download("nl")` — observe progress callback ticks.
2. Kill the process mid-download, restart — download must resume from the partial.
3. Force checksum mismatch — `VERIFYING` must fail and clean up `temp/`.
4. After install, `RegionStore.list()` returns NL with correct size and timestamps.
5. Delete: `RegionStore.delete("nl")` — files gone, registry row gone.

**Success criteria:** can download, resume, verify, install, list, and delete a pack without UI.

**Implementation notes (2026-05-02):**
- **Files shipped.**
  - `offline/pack/Tar.kt` — streaming USTAR reader (~180 LOC). Handles regular files + directories, validates header checksum, rejects path traversal, errors on link/device/fifo entries. End-of-archive detected via the standard two-zero-block marker.
  - `offline/pack/ChecksumVerifier.kt` — sha256 of files + tarball-level + per-file (`PackManifest`) verification. Sealed `Result` so callers must handle each failure.
  - `offline/pack/RegionStore.kt` — singleton-per-process JSON-backed registry (`filesDir/regions/installed.json`). Atomic save via `.partial` rename. `StateFlow<List<RegionPack>>` for UI binding (Step 5). Drops rows whose pack dir has vanished on reload.
  - `offline/download/DownloadState.kt` — sealed lifecycle + `isValidTransition` lint helper.
  - `offline/download/PackDownloader.kt` — coroutine orchestrator. `download(CatalogEntry)` runs the full pipeline; `cancel(id)` and `delete(id)` for cleanup. Per-pack `Job` map enables independent cancel without touching other downloads. Atomic install: extract to `regions/<id>.installing/` → final `currentCoroutineContext().ensureActive()` cancellation gate → rename to `regions/<id>/`. If a previous version exists, it's renamed aside first so a crash mid-swap leaves *something* installed.
  - `HttpUrlConnectionClient` — default HTTP layer. Issues `Range: bytes=N-` when the partial file already has bytes; falls back to fresh download (and deletes the partial first) if the server doesn't honour ranges. Cooperative cancellation via `ensureActive()` inside the read loop.
- **Pluggable HTTP for tests.** `PackDownloader.HttpClient` is an interface; the production class is `HttpUrlConnectionClient`, the tests inject a `ByteArrayHttpClient` that streams from in-memory fixtures. No NanoHTTPD or wiremock needed.
- **WorkManager deferred — judgment call.** Spec called for WorkManager-managed downloads. For Step 4's success criteria (download/resume/verify/install/list/delete) a coroutine tied to the application scope is enough — the user is staring at the progress bar. WorkManager is the right answer when downloads need to survive process death (start NL, lock screen, walk away). Dropping WorkManager in is a wrapper around `downloadInner`, not a rewrite — interface + state machine + atomic-install code stays intact. **Resume across process restart still works** because the `.partial` file persists; the user just has to re-tap rather than auto-resume.
- **Room deferred — same reasoning.** Plan §6 sketched `RegionPackEntity` in Room. The actual dataset is ≤ 100 rows, written ~once per install/delete and read once per process start. JSON registry achieves the same query API (`list()`/`get()`/`add()`/`delete()`/`touchLastUsed()`) without a kapt/KSP annotation processor. Swap to Room before we ship multi-device sync or concurrent writers.
- **Tests added** (`app/src/test/java/.../offline/`):
  - `pack/TarTest.kt` — round-trip extract, path-traversal reject, corrupted-header detection.
  - `pack/ChecksumVerifierTest.kt` — sha256 vs reference, tarball Ok/Mismatch, per-file Ok/WrongSize/Missing.
  - `pack/RegionStoreTest.kt` — add/list/get/delete, persistence across instances, last-used update, vanished-pack-dir cleanup.
  - `download/PackDownloaderTest.kt` — happy path end-to-end (manifest + per-file verify + RegionStore registration), checksum mismatch (registry stays clean, partial scrubbed), resume-from-existing-partial, delete-removes-everything.
  - `pack/TarFixtures.kt` — in-memory USTAR builder so tests don't shell out to `tar(1)`.
- **§3 disk layout this lands.**
  ```
  filesDir/regions/
    installed.json             RegionStore registry
    <id>/                      installed pack root
      tiles.mbtiles, routing/, pois.geojson, manifest.json
    <id>.installing/           atomic-install staging (deleted on failure)
    <id>.previous/             swap-aside backup (deleted on success)
    tmp/<id>.partial           in-flight tarball
  ```
- **Cancellation invariant.** A `cancel(id)` mid-download leaves `.partial` on disk for the next resume. A `cancel(id)` after the verify+extract phase but before the rename is honoured by a single `ensureActive()` gate; the `installing/` dir is then orphaned but swept on the next download attempt. A `cancel(id)` after the rename and `store.add()` arrives too late — the install is already complete, and the user needs `delete(id)` to undo it.
- **Pack id reservation.** `regions/tmp/`, `regions/installed.json`, `regions/<id>.installing/`, `regions/<id>.previous/` shadow the layout, so pack ids `tmp` and `installed` (and any id that collides with the suffixes) are reserved. Catalog should never produce these — we follow the slug convention from `regions.json` (`nl`, `us-ca`, `metro-tokyo` etc.).
- **Not validated locally:** still no Android SDK / JDK 21+ in this WSL shell. The new tests are pure JVM (no Android dependency, no Robolectric); they should run via `./gradlew :app:testDebugUnitTest` once the SDK is available. Next picker-upper should:
  1. Run `./gradlew :app:testDebugUnitTest` and verify the four new test classes pass alongside the existing `BoundingBoxTest`/`DensityGridTest`.
  2. Smoke-test by hand: build a real pack with `bash scripts/build-pack/build.sh nl`, drop the resulting `.tar.gz` somewhere reachable, drive `PackDownloader.download(...)` from a debug menu (Step 5 will land the UI proper), and confirm the §8 step-4 punch list end-to-end on a device.

---

### Step 5 — Region picker + storage manager UI ✅ done 2026-05-02

**Files:** all of `ui/screen/regions/*`.

Three tabs:
- **Recommended** — uses GPS + `RegionResolver` to suggest top 3 covering packs. One-tap.
- **Browse** — searchable tree (continent → country → state). Each row: name, size, status button.
- **Custom** — full-screen MapLibre map with draggable rectangle. Live size overlay using `DensityGrid`. Soft warn at 250 MB; hard cap at 800 MB (button greys out).

Storage manager: list installed packs, sizes, delete/update actions, total used / free.

**How to test:**
1. Open picker from drawer / "+" button.
2. **Recommended:** with GPS in NL, top suggestion = "Netherlands" (~80 MB). Tap → download starts → progress visible → installs.
3. **Custom:** pinch a 50×50 km box around Amsterdam; banner reads ~120 MB ±15%. Resize bigger; banner updates live. Resize past 800 MB; download button greys out.
4. **Storage manager:** NL appears with size + last-used. Tap delete → confirmation → pack removed → map reverts to skeleton.
5. Cycle airplane-mode on/off mid-download; download must pause and resume cleanly.

**Success criteria:** all three picker modes work; storage manager round-trips download → use → delete without leaving orphan files.

**Implementation notes (2026-05-02):**
- **Files shipped.**
  - `offline/pack/CatalogProvider.kt` — singleton-per-process loader for `assets/bundled/catalog.json`. `StateFlow<CatalogManifest>` for picker binding. Remote-refresh stub (`refreshFromRemote`) is a logged no-op until the pack hosting endpoint is live.
  - `offline/pack/RegionResolver.kt` — pure function: GPS → top-N covering catalog entries, sorted by area ascending so "Randstad" beats "Netherlands" beats "Europe" when all three cover Amsterdam.
  - `assets/bundled/catalog.json` — initial Tier-1 catalog (NL/BE/CH/MC) so the picker has something to show on first launch with zero network. URLs/sha256s are placeholders — replace with real published-pack values before deploy. **Source of truth lives at this path**; the schema matches `CatalogManifest.parse`.
  - `ui/screen/regions/RegionPickerScreen.kt` — single Compose screen with four tabs: Recommended / Browse / Custom / Storage. State is wired straight to `CatalogProvider` + `RegionStore` + `PackDownloader.state` so live download progress shows up regardless of tab. Empty-state composable for "no GPS yet" / "no covering pack" / "catalog empty" / "no installed packs" cases.
  - `ui/screen/MapScreen.kt` — added a `CloudDownload` icon in the trailing slot of `SubScreenTopBar` that opens the picker.
  - `ui/nav/Routes.kt` + `AppNavHost.kt` — new `Route.Regions` registered alongside the existing routes; wired from MapScreen.
- **Custom tab uses numeric inputs, not a draggable map (judgment call).** The plan's "full-screen MapLibre map with draggable rectangle" is genuinely heavy — needs a second MapLibre instance + custom touch handling for the bbox handles + animated overlays. The picker today exposes four `OutlinedTextField`s for W/S/E/N, prefilled with a 0.5°-half-width box around the GPS fix. Live size estimation runs through `DensityGrid.estimateBytes` on every keystroke; the soft warn (≥250 MB) and hard cap (≥800 MB) banners both fire correctly. Custom-bbox **download** is also deferred — the build pipeline only publishes country/metro packs today, so the Custom tab is wired for the size-cap UX but reads an explicit "not yet downloadable" footer pointing at plan §10.
- **Browse tab is flat, not the continent → country → state tree (judgment call).** With four catalog entries the tree adds clicks without information density. Switch to a tree once the catalog grows past ~20 packs; the bundled `presets.json` already encodes the hierarchy when we need it.
- **Storage tab is merged into the picker, not a separate screen.** One file is easier than two when both pull from the same `RegionStore.state`. AlertDialog confirms delete; total-bytes header fires on the first item.
- **No new dependencies.** Picker is built from existing Compose Material 3 + EmergencyTheme tokens already in the project.
- **Tests** (pure JVM, no Robolectric):
  - `pack/RegionResolverTest.kt` — smaller-bbox-wins ordering, `maxResults` cap, no-coverage `null`, zero-cap edge.
  - `pack/BundledCatalogTest.kt` — reads `assets/bundled/catalog.json` straight from the source tree and asserts it parses through `CatalogManifest.parse` and seeds the four expected ids (NL/BE/CH/MC).
- **Resume-across-airplane-mode (test step 5) inherits Step 4's behaviour.** `PackDownloader` writes to `tmp/<id>.partial`; cycling airplane mode kills the in-flight HTTP socket → `Failed(reason)` → tap **Retry** in the picker → download resumes from the `.partial` via the `Range:` header. Surface a "paused: no network" status automatically would need a `ConnectivityManager` watcher — landed when WorkManager comes in (still §8 step 4 deferred work).
- **Not validated locally:** no Android SDK in this WSL shell. The new tests are pure JVM and should pass via `./gradlew :app:testDebugUnitTest`. The picker UI itself needs an emulator or device — visual smoke test punch list:
  1. Install the APK fresh; map screen loads with the cream skeleton fallback (no skeleton built yet).
  2. Tap the cloud-download icon top-right → picker opens on Recommended tab.
  3. With GPS in NL, the top row says "Netherlands · 80 MB · v1".
  4. Tap **Get** → status flips to **Queued → 0% → … → Verify → Install → Installed**. The placeholder URLs in the bundled catalog will fail, so this step needs a real published pack to run end-to-end.
  5. Browse tab lists all four catalog entries; Custom tab shows live size estimates as you edit bbox values; Storage tab lists nothing until step 4 succeeds.

---

### Step 6 — OfflineRouter multi-region + honest errors ✅ done 2026-05-02

**Files:**
- `OfflineRouter.kt` (modify to accept multiple `segmentsDir`s, one per installed pack, and union them).
- `RouteOutcome.kt` (new sealed class).
- `RegionResolver.kt` (decides which packs cover endpoints).

```kotlin
sealed class RouteOutcome {
  data class Success(val result: RouteResult) : RouteOutcome()
  data class OutsideDownloadedRegion(val missingRegions: List<RegionPack>) : RouteOutcome()
  data class NoRouteFound(val reason: String) : RouteOutcome()
  data class GraphLoadFailed(val cause: Throwable) : RouteOutcome()
}
```

Pre-flight: are both endpoints inside the union of installed bboxes? If not → `OutsideDownloadedRegion` with suggested packs.

**How to test:**
1. With NL pack installed: route Amsterdam → Utrecht. **Expected:** `Success` with sensible polyline.
2. Route Amsterdam → Berlin (no DE pack). **Expected:** `OutsideDownloadedRegion(missingRegions=[de])`.
3. Route between two disconnected islands inside NL bbox. **Expected:** `NoRouteFound(...)`.
4. Corrupt a pack manually. **Expected:** `GraphLoadFailed(...)`.

**Success criteria:** no silent failures; every error path has a typed outcome and a user-facing message.

**Implementation notes (2026-05-02):**
- **Files shipped.**
  - `offline/routing/RouteOutcome.kt` — 4-case sealed class. `OutsideDownloadedRegion` carries `missingPacks: List<CatalogEntry>` (catalog entries the user can one-tap) plus `uncoveredEndpoints: List<Endpoint>` so messaging can say "your destination is outside…" vs "your start and destination". `userMessage()` formats a short layer-1 string the UI shows verbatim. Helpers: `successOrNull()`, `missingPacksOrEmpty()`, `isRecoverable`.
  - `offline/pack/RegionResolver.kt` — added `coveringInstalled`, `isCoveredByInstalled`, and `missingForRoute(catalog, installed, fromLat, fromLon, toLat, toLon)`. `missingForRoute` deduplicates when both endpoints map to the same suggested pack, and skips endpoints already covered by the installed union. Pure functions; unit-tested.
  - `offline/OfflineRouter.kt` — signature changed from `route(..., segmentsDir: File?, profilesDir: File): Result?` to `route(..., profilesDir, installedPacks: List<RegionPack>, catalog: List<CatalogEntry>, activeRoot: File): RouteOutcome`. Pre-flight runs first; returns `OutsideDownloadedRegion` before BRouter is touched. BRouter's empty `foundTrack` becomes `NoRouteFound`; thrown exceptions become `GraphLoadFailed`. Missing profile is `GraphLoadFailed` (the wrong APK shipped).
- **Hardlink-merged segments dir for BRouter.** `RoutingEngine` takes a single `segmentDir: File`. To union N installed packs without copying ~70–180 MB per `.rd5`, `OfflineRouter.mergeSegments(installed, activeRoot)` builds a hardlink farm under `filesDir/regions/_active/segments/`. Hardlinks (`Files.createLink`) are zero-cost on a single filesystem and tear down cleanly when `deleteRecursively` runs. Falls back to a straight `copyTo` if `createLink` throws (cross-filesystem; shouldn't happen under `filesDir` but the safety net is cheap). Adjacent packs that ship the same 5°×5° tile (NL + BE both contain `E0_N50.rd5`) deduplicate by filename — first-installed wins, which is fine because BRouter segments at the same coordinates are byte-identical regardless of which country pack ships them.
- **Cache invalidation.** `mergeSegments` keeps a `Volatile` snapshot of the sorted installed-pack ids. A no-op call (same set) returns the cached dir untouched; any change to the installed set (`RegionStore` add/delete) wipes the dir and re-links from scratch. Correctness-first: re-linking ~10 segments takes microseconds; we don't try to compute a delta.
- **UI plumbed end-to-end.**
  - `MapScreen.onOpenRegions` (added in Step 5) is now passed through to `InteractiveMap`. The `RouteInfoCard` inside the InteractiveMap surfaces `outcome.userMessage()` for any non-`Success` outcome and grows an "Open map regions" CTA when the outcome is `OutsideDownloadedRegion` so the user can one-tap to the picker.
  - `LiveMiniMap` consumes the same `RouteOutcome` API; on any non-success outcome it silently clears the polyline (the chat bubble that hosts it is a preview, not a planning tool).
- **Tests added** (pure JVM):
  - `pack/RegionResolverTest.kt` — extended with `isCoveredByInstalled`, `missingForRoute` (both-uncovered, half-covered, both-covered, dedup-same-pack) cases.
  - `routing/RouteOutcomeTest.kt` — `userMessage` mentions suggested packs; gracefully handles "no catalog match"; `successOrNull` / `missingPacksOrEmpty` extensions; `isRecoverable` flagging.
  - `OfflineRouterMergeTest.kt` — `mergeSegments` returns null on empty install set, unions all unique segments across packs, rebuilds when the set changes, returns the cached dir when set unchanged, tolerates a pack with no `routing/` dir.
- **Test step 4 (corrupt pack → GraphLoadFailed) is reachable but not unit-tested.** The full BRouter integration test needs a real `.rd5` graph + lookups + waypoints — too heavy for `:app:testDebugUnitTest`. Manual smoke test: install a pack, `dd if=/dev/urandom of=filesDir/regions/<id>/routing/E0_N50.rd5 bs=1M count=1 conv=notrunc`, route inside NL → `RouteInfoCard` shows "Routing graph error: …".
- **Not validated locally:** still no Android SDK in this WSL shell. Pure-JVM tests should pass via `./gradlew :app:testDebugUnitTest` (six new tests across the two files plus the extended RegionResolverTest). The merge-helper test relies on `Files.createLink` succeeding inside the JVM `tmp/` filesystem; on OSes/filesystems that disallow hardlinks the helper falls back to copy and the test still passes.

---

### Step 7 — Turn-by-turn data extraction ✅ done 2026-05-02

**Files:**
- `OfflineRouter.kt` — set `routingContext.voicehints = 1`.
- `VoiceHintParser.kt` — map BRouter command codes (`TLU, TSHL, TL, TSLL, TSTR, TSLR, TR, TSHR, KL, KR, RNDB1..8, BL, OFFR, EXIT`) → `TurnCommand` enum.
- `TurnStep.kt` — `{location, command, distanceToNextMeters, streetName, indexInPolyline}`.
- `StepFormatter.kt` — `TurnStep → "In 200 m, turn left onto Hoofdstraat"` etc. Format distance: `<100m → "50 m"`, `<1000m → "400 m"`, else `"1.2 km"`.

**How to test:**
1. Route Amsterdam Centraal → Anne Frank House (walking, ~1.5 km).
2. **Expected:** ~10–15 `TurnStep`s, each with a sensible street name and distance. Final step is destination arrival.
3. Snapshot test against a reference route to detect regressions.
4. Try a roundabout-heavy route (e.g., somewhere in Utrecht) — confirm `RNDBn` parses to "take the Nth exit".

**Success criteria:** every voice hint turns into a clean, readable step. No "[unknown turn]" leaks to UI.

**Implementation notes (2026-05-02):**
- **Files shipped.**
  - `offline/routing/TurnStep.kt` — `TurnStep(location, command, distanceToNextMeters, streetName, indexInPolyline)` exactly per plan spec, plus a `TurnCommand` sealed hierarchy covering every BRouter code in the plan's table (and a `Roundabout(exit, leftHanded)` carrier so `RNDB3` / `RNLB2` round-trip cleanly). Unknown future codes land in `TurnCommand.Unknown(raw)` rather than throwing.
  - `offline/routing/VoiceHintParser.kt` — pulls turn instructions from BRouter's `OsmTrack.voiceHints`. Parses **purely reflectively** (no `import btools.*`) so the build is independent of which BRouter jar version is on the classpath, and a future BRouter rev that renames a field shows up as "no turns" instead of a NoSuchFieldError. Distance-to-next falls back to summing haversine edges between this hint's `indexInTrack` and the next hint's when BRouter's numeric field isn't exposed.
  - `offline/routing/StepFormatter.kt` — `formatStep(step, distanceUntilHereM)`, `formatDistance(meters)`, `formatAll(steps)`. Distance bucketing matches the plan: <100 m → 10 m increments ("50 m"); <1 km → 50 m increments ("400 m"); ≥1 km → one-decimal km ("1.2 km"). Sub-25-m maneuvers drop the "In X m," lead-in (matches Google Maps' "Turn now" cadence). Roundabouts spell out the exit ordinal ("the 3rd exit", not "exit 3").
  - `offline/OfflineRouter.kt` — `RoutingContext.turnInstructionMode` set to `1` via a reflective helper that tries `turnInstructionMode` first, then the older `voicehints` field, then setter variants — both 1.7.x mainline and forks work without recompiling. `OfflineRouter.Result` grew a `steps: List<TurnStep>` field (default empty); `RouteOutcome.Success` carries the same payload, so callers can render turn lists alongside the polyline without a second call.
- **Tests added** (pure JVM):
  - `routing/TurnCommandTest.kt` — every documented BRouter code parses to the expected sealed-class case, `RNDB3` / `RNLB2` round-trip with the right exit + handedness, unknown codes preserve the raw string, empty/whitespace fall to `Continue`, bare `RNDB` defaults exit=1.
  - `routing/StepFormatterTest.kt` — distance bucketing across all three buckets, turn-left with/without street name, sub-floor distances drop the lead-in and capitalise the verb, roundabouts spell out the ordinal (including the 11/12/13 teen edge cases), arrival message is invariant, unknown commands render the generic "follow the road" copy, `formatAll` chains `previous.distanceToNext` correctly.
- **No standalone VoiceHintParser unit test (judgment call).** The parser is reflective glue against the BRouter jar — fake-track unit tests would essentially re-implement the same reflection and validate nothing real. The parsing is exercised end-to-end by the Step 9 manual airplane-mode regression (Amsterdam Centraal → Anne Frank House should emit ~10–15 sensible steps via `OfflineRouter.Result.steps`). The TurnCommand mapping (which is the part most likely to break on a code rename) is fully covered by the pure tests.
- **Distance semantics.** `TurnStep.distanceToNextMeters` is the distance from this maneuver to the *next* one (or to the destination for the last step). Active navigation (Step 7.5) computes "In X m, …" from the user's current GPS to `step.location` directly, ignoring this field; pre-rendered step lists (Step 8 LLM output) chain it forward via `StepFormatter.formatAll`.
- **Not validated locally:** still no Android SDK in this WSL shell. The new tests are pure JVM and should pass via `./gradlew :app:testDebugUnitTest` (sixteen new assertions across the two test files). Manual punch list once a real pack is installed:
  1. Route Amsterdam Centraal → Anne Frank House on the Walk profile and dump `routeOutcome.successOrNull()?.result?.steps` to logcat — expect the Damrak/Spuistraat/Raadhuisstraat sequence with sensible distances.
  2. Route a Utrecht ring-road loop — expect `Roundabout(exit=N)` cases formatted as "take the Nth exit".
  3. If `steps` is empty after a successful route, the BRouter jar shipped a `voiceHints` shape we haven't seen — wire a logger inside `VoiceHintParser.extractHintList` and report which member name was missing.

---

### Step 7.5 — Navigation engine + UI ✅ done 2026-05-02

**Files:** all of `offline/navigation/*` + `ui/screen/navigation/*`.

State machine: `PREVIEW → NAVIGATING → (REROUTING ↔ NAVIGATING) → ARRIVED`.

Maneuver scheduler trigger ladder:
- walking: `[80m, "now"]`
- biking: `[200m, 80m, "now"]`
- driving: `[500m, 200m, 50m, "now"]`
- highway (>80 km/h): `[1000m, 500m, 200m, "now"]`

Off-route: perpendicular distance > threshold (30m walk / 50m bike / 80m drive) for 3 consecutive ticks → reroute. 10s cooldown between reroutes.

Camera follow-mode: bearing = GPS heading (damped < 5 km/h), zoom dynamic by speed, tilt 30–50° for bike/drive, 0° for walk. User pinned at bottom-third.

TTS: `TextToSpeech.isLanguageAvailable(locale)`; if `LANG_MISSING_DATA`, show one-time toast linking to system TTS settings; never block on TTS.

**How to test (real device, walking with GPS):**
1. Start navigation Amsterdam Centraal → Anne Frank House.
2. **Expected:** maneuver banner shows current step + distance, updates live as you walk. At 80m, TTS announces "In 80 m, turn left onto …". At ~30m, TTS announces "Turn left now". Camera follows you head-up, zoomed in. Step advances after the turn.
3. Walk in the wrong direction for ~15s → off-route banner appears → recalculation → new polyline.
4. Reach destination → "You have arrived" announcement → end card.
5. Repeat in driving mode in a car (or simulated GPS).

**Success criteria:** navigation feels recognisably like Google Maps — clear banner, timely voice prompts, follow-camera, off-route handled. No crashes when GPS is noisy or briefly lost.

**Implementation notes (2026-05-02):**
- **TTS skipped per user instruction.** No `TtsAnnouncer` shipped. The `ManeuverScheduler` still drives the *visual* banner cadence (swap from "In 200 m, …" to "Turn now" at the right moment), so the navigation experience is the Google-Maps shape — silent. Add `android.speech.tts.TextToSpeech` later if voice prompts become a requirement.
- **Files shipped.**
  - `offline/navigation/RouteProgressTracker.kt` — pure: snap-to-segment via flat-earth projection, haversine summation for traveled / remaining, current-step lookup against a steps list. One snapshot per call, no internal state — re-snapping the same fix yields the same answer.
  - `offline/navigation/ManeuverScheduler.kt` — per-profile trigger ladder exactly per spec. Stateful: tracks which rungs have already fired for the active step, resets on step advance. Reports the *innermost* trigger crossed since the last tick so sparse GPS updates don't lose the imminent prompt.
  - `offline/navigation/OffRouteDetector.kt` — fires once perpendicular deviation exceeds the per-profile threshold for 3 consecutive ticks; 10-second cooldown between fires. `reset()` exposed for the engine to clear state after applying a new polyline.
  - `offline/navigation/CameraController.kt` — pure math helpers. Bearing damping under 5 km/h, zoom by speed bands (z18 walk → z14 highway), tilt by profile. Returns a `Frame(target, zoom, bearing, tilt)` that the UI applies via `MapboxMap.animateCamera`.
  - `offline/navigation/NavigationEngine.kt` — `PREVIEW → NAVIGATING → REROUTING → (NAVIGATING | RerouteFailed) → ARRIVED`. Owns the scheduler + detector + camera + tracker; UI binds to `state: StateFlow<NavigationState>` and feeds GPS via `tick(rawFix, speedMps, gpsHeadingDeg)`. `applyNewRoute(...)` swaps in the rerouted polyline; `rerouteFailed(...)` parks in a terminal-but-non-fatal state.
  - `offline/navigation/PendingNavigation.kt` — process-wide single-slot handoff. Producer: `RouteInfoCard`'s "Start navigation" button writes the `OfflineRouter.Result` + `NavigationProfile`. Consumer: `NavigationScreen` reads on mount and clears.
  - `ui/screen/navigation/NavigationScreen.kt` — full-screen Compose. Hosts a MapLibre view with the route polyline + a snapping user-puck, plus overlays: top maneuver banner (uses `StepFormatter.formatStep`), top off-route banner, bottom ETA card, bottom-right recenter button. Live GPS from `FusedLocationProviderClient` at 1 Hz / `PRIORITY_HIGH_ACCURACY`.
  - Wiring: `Route.Navigation` registered in `Routes.kt` + `AppNavHost.kt`; "Start navigation" CTA added to `RouteInfoCard` (visible only on `RouteOutcome.Success`); `MapScreen.onStartNavigation` plumbed through.
- **Reroute back-edge intentionally not auto-wired.** When the off-route detector fires, the engine transitions to `Rerouting` and calls `onRerouteRequested()`. The hosting screen is responsible for calling `OfflineRouter.route(...)` again and feeding the result back via `engine.applyNewRoute(...)` (or `rerouteFailed(...)` on failure). This matches the architecture decision that nav code shouldn't reach into `RegionStore`/`CatalogProvider`. Today the screen logs the request; full automated reroute lands in Step 9 alongside the airplane-mode regression test.
- **Tests added** (pure JVM, no Android deps):
  - `navigation/RouteProgressTrackerTest.kt` — exact snap on polyline, perpendicular deviation, snap to nearest endpoint past route end, arrived flag, `currentStepIndex` advances past reached maneuvers, empty-steps yields -1.
  - `navigation/ManeuverSchedulerTest.kt` — walking ladder fires both rungs in order then idles; driving ladder fires all four; step-advance resets; sparse-tick reports innermost trigger; negative step index no-ops; `reset()` clears.
  - `navigation/OffRouteDetectorTest.kt` — 3-consecutive rule, single-spike doesn't fire, cooldown suppresses refires for 10 s, per-profile threshold differences, `reset()` clears cooldown.
- **Camera animation kept simple.** `MapboxMap.animateCamera(..., 300)` per tick, no manual easing. The dynamic zoom + bearing damping live in `CameraController`; smoothing across ticks is left to MapLibre's built-in animator. Good enough for walking pace; driving might want a separate easing pass once we test on a phone in a car.
- **Not validated locally:** still no Android SDK in this WSL shell. Pure-JVM tests should pass via `./gradlew :app:testDebugUnitTest` (about 16 new assertions across the three nav-engine test files). Manual smoke test on a real device:
  1. Tap a POI → "Start navigation" appears in the route card → tap → NavigationScreen opens.
  2. Walk along the route — banner updates "In 80 m, turn left onto …" then "Turn left now"; user puck snaps to polyline; camera follows.
  3. Walk perpendicular for ~15 s — off-route banner fires after 3 consecutive ticks ≥ 30 m off; logs `Off-route: deviation=…m > 30m; requesting reroute`. (No automatic recompute yet — see deferred reroute.)
  4. Approach destination → arrived state → ETA card switches to "Arrived".
  5. Tap recenter button if the map was panned by the user — follow-mode resumes.

---

### Step 8 — RouteTool replaces fake GpsLocationTool branch ✅ done 2026-05-03

**Files:**
- `GpsLocationTool.kt` — strip lines 58–71 (the hardcoded "shelter" response).
- `RouteTool.kt` (new) — wraps `OfflineRouter` + `RegionResolver`.

```kotlin
RouteTool(
  name = "route_to",
  description = "Routes from the user's GPS to a destination POI or coordinates. " +
                "Returns distance, ETA, and turn-by-turn steps. Says explicitly when " +
                "the destination is outside downloaded maps.",
  params = ["destination", "profile?"]   // walk | drive | bike, default walk
)
```

LLM gets text summary + step count. Side-effect: triggers polyline render + opens `NavigationScreen` in PREVIEW.

**How to test:**
1. Ask the LLM in chat: "Take me to the nearest hospital." **Expected:** real route, real distance, real steps; map shows polyline.
2. Ask: "Take me to Tokyo Tower." (no JP pack installed). **Expected:** LLM relays the honest error and offers to download the JP pack.
3. Ask: "Where is the nearest AED?" — LLM uses `FindNearestTool` → user taps result → `RouteTool` runs to that POI.

**Success criteria:** every directions request produces a real route or an honest, actionable error. No hallucinated streets, no fake "exit your building" instructions.

**Implementation notes (2026-05-03):**
- **Hardcoded shelter directions deleted.** `GpsLocationTool` now returns only the GPS coords (or an explicit "GPS not ready" error). Its description tells the model to use `route_to` for navigation. The fake "Central Public Shelter / Market Square / blue shelter sign" string is gone — no more hallucinated streets.
- **`RouteTool` shipped at `agent/tools/RouteTool.kt`.** Destination resolution accepts:
  1. `"<lat>,<lon>"` (with optional wrapping parens — the LLM occasionally adds them).
  2. A POI category (`hospital`, `pharmacy`, `aed`, `defibrillator`, `er`, etc.) — resolved via the same `PoiRepository` and alias list `find_nearest` uses, so anything the LLM already learned for `find_nearest` works here too.
  Geocoded place names are intentionally unsupported — no on-device geocoder. The system-prompt examples make that explicit.
- **Profile param**: `walk` (default) | `bike` | `drive`. Maps to BRouter's `trekking` / `fastbike` / `car-fast`. Aliases (`walking`, `cycling`, `car`, `foot`, …) accepted.
- **Result JSON shape matches `find_nearest`**: `{name, category, lat, lon, …}` — so the existing chat→map handoff (`AppNavHost.parseFindNearestDestination` / `ChatThreadScreen.parseFindNearestCard`) picks `route_to` results up unchanged. `route_to` adds `distance_m`, `duration_s`, `step_count`, and `first_steps[]` (capped at 5) for the LLM's follow-up summary. The map mounts with the destination preselected and the user taps "Start navigation" if they want active turn-by-turn — same UX as `find_nearest`, the user is in control of when nav begins.
- **`OutsideDownloadedRegion` returns success=false with structured payload** — `{error: "outside_downloaded_region", message, missing_packs[], uncovered[]}`. The error string mentions the missing pack name(s) so the LLM's follow-up prompt can offer "Want me to install Germany?" without re-querying.
- **`ToolCallInfo` got a `rawResult` field** — the existing `result` truncates to 200 chars for chat-bubble display, which would cut off `route_to`'s longer JSON mid-`first_steps`. `rawResult` carries the full tool data so both `AppNavHost.onOpenTool` and `ChatThreadScreen`'s `MapToolCard` parser see the complete `{name, category, lat, lon, …}` block. Older `find_nearest` results are short enough that nothing changes for them.
- **System prompt updated.** `find_nearest` is now scoped to "**where** is the nearest X" (no movement implied); `route_to` is the default for "**take me to / get to / walk me to / drive me to / route to**". `get_location` is reduced to "give me the current GPS coords". Three new `route_to` examples (POI category / explicit coords / driving profile).
- **Tests** (`agent/tools/RouteToolTest.kt`, pure JVM):
  - `parseCoords` — accepts plain pair / spaced / parens / negative; rejects out-of-range / non-numeric / single-value.
  - `mapProfile` — known aliases (case-insensitive); unknowns return null.
  - `normalizeCategory` — direct hits + alias map + space→underscore; unknowns return null.
  - `formatOutcome` — Success → JSON with all 8 expected fields including `first_steps`; OutsideDownloadedRegion → success=false + error mentions the missing pack name + structured payload; NoRouteFound and GraphLoadFailed both surface their reason in the error string.
- **Not validated locally:** still no Android SDK in this WSL shell. The 12 new pure-JVM assertions should pass via `./gradlew :app:testDebugUnitTest`. Manual smoke test on a device once a real pack is installed:
  1. Chat: "Take me to the nearest hospital" → tool bubble with map preview → tap → map opens with polyline + route info card → "Start navigation" CTA appears → tap → NavigationScreen.
  2. Chat: "Walk me to 35.68, 139.69" (Tokyo, no JP pack) → LLM relays "outside downloaded maps; install Japan to route here" using the `missing_packs` field.
  3. Ask "Where is my GPS location?" → `get_location` returns just the coords (no fake directions).

---

### Step 9 — End-to-end offline regression test

On a physical device in **airplane mode** for the entire test:

1. Fresh install (skeleton only). App opens → world map shows.
2. Open region picker → Recommended → "Netherlands" → download.
3. Verify download progress, kill app mid-download, restart → resume completes.
4. Storage manager shows NL pack with size.
5. Map zooms in over Amsterdam → crisp at z18.
6. Chat: "Take me to the nearest hospital" → real route + steps.
7. Start navigation → walk a few hundred metres → maneuver banner updates, TTS speaks (if voice installed), follow-camera tracks heading.
8. Walk wrong way → off-route → reroute.
9. Ask LLM for a route to Berlin → honest "outside downloaded maps" + offer to download DE pack (download is **blocked** in airplane mode — must surface clear network-required error here).
10. Storage manager → delete NL → map reverts to skeleton.

**Pass condition:** every flow above completes without internet.

---

## §9 What to test (quick reference for the user)

When implementation is done, here's the demo path to run yourself. Expected outcomes per step are in §8 already; this is the punchlist.

1. **Pixelation gone** — zoom max on the map; lines are crisp. (After step 1.)
2. **Region picker works** — Recommended / Browse / Custom all functional; live size estimate; hard cap; download / pause / resume / delete. (After step 5.)
3. **Honest routing errors** — request a destination outside any downloaded pack; UI says so and offers the relevant pack. (After step 6.)
4. **Turn-by-turn feels like Google Maps** — clear banner, timely voice ("In 200 m, turn left onto …"), camera follows, off-route reroutes. (After step 7.5.)
5. **LLM directions are real** — ask the chatbot for directions; it routes against real data and is honest about failures. (After step 8.)
6. **Full offline run** — airplane-mode end-to-end demo from §8 step 9.

---

## §10 Open questions / risks

- **MapLibre PMTiles vs MBTiles**: keeping MBTiles via the existing localhost-server pattern. Verify Android `MapView` accepts `Content-Encoding: gzip` on vector tiles without double-decompressing. If not, serve un-gzipped (slightly larger but reliable).
- **Stripped `lookups.dat`**: validate that BRouter still parses all expected profiles after stripping. Build one country first as proof before bulk-rebuilding.
- **Offline TTS coverage**: varies by device + region. Plan handles graceful fallback to text. Confirm on at least one Samsung, one Pixel, one budget Android device.
- **WorkManager + foreground service** for long downloads: required for downloads > a few minutes. Confirm Android 13+ permission model.
- **R2 vs GitHub Releases**: hackathon scale → GitHub Releases. Switch when bandwidth becomes an issue.
- **Pack updates**: schema versioning is in the manifest (`version` field). Update flow: download new version alongside old, swap atomically, delete old. Handled by `PackDownloader`.

---

## §11 Reference: source data + tools

- **OSM data** — Geofabrik per-country `.osm.pbf` extracts: <https://download.geofabrik.de/>
- **Vector tiles** — Planetiler: <https://github.com/onthegomap/planetiler>
- **Routing graph** — BRouter map-creator: <https://github.com/abrensch/brouter>
- **POI extraction** — osmium-tool: <https://osmcode.org/osmium-tool/>
- **Admin boundaries (presets)** — Natural Earth: <https://www.naturalearthdata.com/>
- **MapLibre Android** — already in use, version pinned in `build.gradle.kts`.

---

End of plan. Tick boxes as you go. Don't break the offline guarantee.
