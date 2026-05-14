#!/usr/bin/env python3
"""Build the bundled `density-grid.bin` consumed by `DensityGrid.load`.

The density grid maps every 0.5° cell on Earth to a "KB-per-square-km"
estimate, which the region picker uses to predict how big a custom-bbox
download will be (plan §8 step 2 / step 5).

## Calibration model

For each grid cell we compute::

    kb_per_km2 = max(
        BASELINE_LAND,
        sum_over_cities( pop_factor * decay(distance_km) )
    )

where::

    pop_factor   = population / POP_FACTOR_DENOM
    decay(d)     = exp(-d / DECAY_KM)

Cities come from GeoNames `cities15000.txt` (≈25k entries with population
≥ 15 000), downloaded from <https://download.geonames.org/export/dump/>
on first run and cached under `.cache/`. The download happens **at build
time on a developer machine only** — the runtime artefact is the bundled
`density-grid.bin`, so the offline-first guarantee is unchanged (see the
project memory and plan §1).

A spatial bucket index (5° lat × 5° lon) keeps the build under a minute
even with 25k cities — without it, the inner loop is 6.5 G haversine
calls.

The constants are tuned against the §3 "Estimated sizes" table by
minimising log-ratio error on {NL, CH, California, Bay Area, NYC bbox,
Beijing metro}.

## Output

`app/src/main/assets/bundled/density-grid.bin` (~519 KB at 0.5°).
"""

from __future__ import annotations

import argparse
import csv
import math
import struct
import sys
import urllib.request
import zipfile
from pathlib import Path

# ── Tunable constants ─────────────────────────────────────────────────
# Calibrated against the §3 reference table after a full 0.5° sweep over
# the GeoNames dataset (≈33k cities ≥15k pop). These values pin NL within
# 20 % and California within 1.5×; urban-only bboxes (NYC, Bay Area)
# undershoot 2–5× — the picker UI should hedge size copy ("≈X MB,
# actual download may be larger") for small bboxes accordingly. The
# single-exponential-per-city kernel can't span both country-scale and
# tight-urban scale simultaneously; refine once Step 4–5 produce
# measured pack sizes (plan §10).
BASELINE_LAND_KB_PER_KM2 = 0.6
BASELINE_WATER_KB_PER_KM2 = 0
POP_FACTOR_DENOM = 300_000.0
DECAY_KM = 10.0
SEARCH_RADIUS_KM = 80.0  # 8 e-foldings at decay=10; cities further are <0.05 % of peak
MIN_POPULATION = 15_000  # cities15000.txt's intrinsic threshold

# ── GeoNames source ───────────────────────────────────────────────────
GEONAMES_URL = "https://download.geonames.org/export/dump/cities15000.zip"
GEONAMES_FILENAME = "cities15000.txt"

# Spatial bucket granularity. 5° matches BRouter's segment grid, gives
# ~70 cities per bucket on average, and lets us check at most a 3×3
# block of buckets per query (250 km radius < 555 km bucket width at the
# equator → 1-cell halo).
BUCKET_DEG = 5.0


def is_open_ocean(lon: float, lat: float) -> bool:
    """Crude land/water mask. Cells inside these windows fall back to the
    water baseline; everywhere else uses the land baseline. Coverage is
    intentionally conservative — open Pacific / Atlantic / Indian only —
    so coastal cells stay at land-baseline (the picker is unlikely to
    care about a few km² of misclassified coast)."""
    if -45 < lon < -25 and -50 < lat < 30:        # Atlantic
        return True
    if (lon > 160 or lon < -130) and -50 < lat < 50:  # Pacific
        return True
    if 60 < lon < 90 and -50 < lat < -10:         # Indian Ocean
        return True
    return False


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


# ── Loading: GeoNames or override ─────────────────────────────────────


def _cache_dir() -> Path:
    out = Path(__file__).resolve().parent / ".cache"
    out.mkdir(exist_ok=True)
    return out


def _ensure_geonames() -> Path:
    """Returns the path to the unpacked cities15000.txt, downloading and
    extracting from the GeoNames dump on first run."""
    cache = _cache_dir()
    txt = cache / GEONAMES_FILENAME
    if txt.exists() and txt.stat().st_size > 0:
        return txt
    zip_path = cache / "cities15000.zip"
    if not zip_path.exists() or zip_path.stat().st_size == 0:
        print(f"Downloading {GEONAMES_URL}…", file=sys.stderr)
        try:
            urllib.request.urlretrieve(GEONAMES_URL, zip_path)
        except OSError as e:
            raise SystemExit(
                f"ERROR: could not download GeoNames: {e}\n"
                f"  Run on a machine with internet, or pass --cities <csv>."
            ) from e
    print(f"Extracting {GEONAMES_FILENAME} from {zip_path.name}…", file=sys.stderr)
    with zipfile.ZipFile(zip_path) as zf:
        zf.extract(GEONAMES_FILENAME, cache)
    return txt


def _parse_geonames(path: Path) -> list[tuple[str, float, float, int]]:
    """GeoNames `cities*.txt` is tab-separated with no header. Columns
    relevant here: 1=name, 4=lat, 5=lon, 14=population. See
    <https://download.geonames.org/export/dump/readme.txt>."""
    cities: list[tuple[str, float, float, int]] = []
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
        for row in reader:
            if len(row) < 15:
                continue
            try:
                name = row[1]
                lat = float(row[4])
                lon = float(row[5])
                pop = int(row[14])
            except ValueError:
                continue
            if pop < MIN_POPULATION:
                continue
            cities.append((name, lat, lon, pop))
    return cities


def _parse_csv(path: Path) -> list[tuple[str, float, float, int]]:
    cities: list[tuple[str, float, float, int]] = []
    with path.open(newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                cities.append(
                    (
                        row["name"],
                        float(row["lat"]),
                        float(row["lon"]),
                        int(float(row["population"])),
                    )
                )
            except (KeyError, ValueError) as e:
                print(f"WARNING: skipping row {row}: {e}", file=sys.stderr)
    return cities


def load_cities(path: Path | None) -> list[tuple[str, float, float, int]]:
    if path is not None:
        return _parse_csv(path)
    return _parse_geonames(_ensure_geonames())


# ── Spatial bucket index ──────────────────────────────────────────────


def bucket_key(lat: float, lon: float) -> tuple[int, int]:
    return (
        math.floor((lat + 90.0) / BUCKET_DEG),
        math.floor((lon + 180.0) / BUCKET_DEG),
    )


def index_cities(
    cities: list[tuple[str, float, float, int]],
) -> dict[tuple[int, int], list[tuple[float, float, int]]]:
    idx: dict[tuple[int, int], list[tuple[float, float, int]]] = {}
    for _, lat, lon, pop in cities:
        idx.setdefault(bucket_key(lat, lon), []).append((lat, lon, pop))
    return idx


def neighbours_within(
    idx: dict[tuple[int, int], list[tuple[float, float, int]]],
    mid_lat: float,
    mid_lon: float,
    halo: int = 1,
) -> list[tuple[float, float, int]]:
    """Returns cities in the bucket containing (mid_lat, mid_lon) plus the
    surrounding `halo` rings. With BUCKET_DEG=5 and halo=1 the lookup is
    9 buckets, easily covering SEARCH_RADIUS_KM=120 anywhere on Earth."""
    cy, cx = bucket_key(mid_lat, mid_lon)
    out: list[tuple[float, float, int]] = []
    for dy in range(-halo, halo + 1):
        for dx in range(-halo, halo + 1):
            bucket = idx.get((cy + dy, cx + dx))
            if bucket:
                out.extend(bucket)
    return out


# ── Build ─────────────────────────────────────────────────────────────


def build_grid(
    cell_size_deg: float,
    cities: list[tuple[str, float, float, int]],
) -> tuple[int, int, list[int]]:
    """Returns (n_cols, n_rows, data) where each `data[i]` is decikb/km²
    (KB/km² × 10) — the on-disk unit for DGR1. The 0.1-KB resolution
    keeps a 0.3 KB/km² rural baseline from rounding to zero."""
    n_cols = int(round(360.0 / cell_size_deg))
    n_rows = int(round(180.0 / cell_size_deg))
    data: list[int] = [0] * (n_cols * n_rows)

    idx = index_cities(cities)
    decay = DECAY_KM
    radius2 = SEARCH_RADIUS_KM
    pop_denom = POP_FACTOR_DENOM
    base_land = BASELINE_LAND_KB_PER_KM2
    base_water = BASELINE_WATER_KB_PER_KM2

    for row in range(n_rows):
        mid_lat = -90.0 + (row + 0.5) * cell_size_deg
        for col in range(n_cols):
            mid_lon = -180.0 + (col + 0.5) * cell_size_deg
            kb = 0.0
            for clat, clon, pop in neighbours_within(idx, mid_lat, mid_lon):
                d = haversine_km(mid_lat, mid_lon, clat, clon)
                if d > radius2:
                    continue
                kb += (pop / pop_denom) * math.exp(-d / decay)
            base = base_water if is_open_ocean(mid_lon, mid_lat) else base_land
            kb = max(base, kb)
            data[row * n_cols + col] = min(0xFFFF, int(round(kb * 10)))
    return n_cols, n_rows, data


def write_dgr1(
    out: Path, cell_size_deg: float, n_cols: int, n_rows: int, data: list[int]
) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(b"DGR1")
        f.write(struct.pack("<f", cell_size_deg))
        f.write(struct.pack("<i", n_cols))
        f.write(struct.pack("<i", n_rows))
        f.write(struct.pack(f"<{n_cols * n_rows}H", *data))


def main() -> int:
    default_out = (
        Path(__file__).resolve().parent.parent.parent
        / "app/src/main/assets/bundled/density-grid.bin"
    )
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=default_out)
    ap.add_argument("--cell-size-deg", type=float, default=0.5)
    ap.add_argument(
        "--cities",
        type=Path,
        default=None,
        help=(
            "Optional override CSV with columns name,lat,lon,population. "
            "If omitted, uses GeoNames cities15000.txt downloaded from "
            f"{GEONAMES_URL} on first run (cached in .cache/)."
        ),
    )
    args = ap.parse_args()

    cities = load_cities(args.cities)
    print(
        f"Building density grid from {len(cities)} cities "
        f"(pop ≥ {MIN_POPULATION:,})…",
        file=sys.stderr,
    )
    n_cols, n_rows, data = build_grid(args.cell_size_deg, cities)
    write_dgr1(args.out, args.cell_size_deg, n_cols, n_rows, data)
    nonzero = sum(1 for v in data if v > 0)
    print(
        f"Wrote {args.out} "
        f"({args.out.stat().st_size // 1024} KB, "
        f"{n_cols}×{n_rows}, {nonzero} non-zero cells)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
