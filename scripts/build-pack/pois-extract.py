#!/usr/bin/env python3
"""Extract emergency-relevant POIs from an OSM PBF as a single GeoJSON file.

Usage::

    pois-extract.py <input.osm.pbf> <output.geojson>

Wraps `osmium tags-filter` + `osmium export` (osmium-tool) with the tag
allowlist agreed in the maps & navigation plan §5: hospitals, pharmacies,
police, fire stations, clinics, doctors, shelters, drinking-water taps,
defibrillators (`emergency=defibrillator`), and the catch-all `emergency=*`
that picks up phones, assembly points, etc.

Why osmium-tool: it's the canonical OSM-data CLI, fast (~1 GB/min on a PBF
read), and produces clean GeoJSON without the LineString/Way ambiguity that
GDAL's ogr2ogr OSM driver introduces. The resulting `pois.geojson` ships in
each region pack and is consumed by `FindNearestTool` on device.

Falls back to a clear error if osmium isn't installed; this runs on the
build machine, not on user devices, so a hard dep is fine.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# Tag selectors passed to `osmium tags-filter`. Keep this list in sync with
# the manifest schema and FindNearestTool's expectations.
#
# `n/` = nodes (point POIs), `nw/` = nodes + ways (so e.g. hospital
# *buildings* are picked up alongside hospital nodes — most large hospitals
# are tagged on a way, not a node).
TAG_FILTERS = [
    # Critical care + first response
    "nw/amenity=hospital,clinic,doctors,pharmacy,police,fire_station",
    # Survival infrastructure
    "nw/amenity=shelter,drinking_water,toilets",
    # Anything tagged with the emergency=* key
    "nw/emergency",
]


def have(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def run(cmd: list[str]) -> None:
    print("    +", " ".join(cmd), file=sys.stderr)
    subprocess.run(cmd, check=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input_pbf", type=Path, help="Source .osm.pbf")
    ap.add_argument("output_geojson", type=Path, help="Destination .geojson")
    args = ap.parse_args()

    if not args.input_pbf.exists():
        print(f"ERROR: input PBF not found: {args.input_pbf}", file=sys.stderr)
        return 66

    if not have("osmium"):
        print(
            "ERROR: osmium-tool is required but not on PATH.\n"
            "  Install via:  apt install osmium-tool   # Debian/Ubuntu\n"
            "                brew install osmium-tool  # macOS\n"
            "                pacman -S osmium-tool     # Arch",
            file=sys.stderr,
        )
        return 69

    args.output_geojson.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="pois-extract-") as tmp:
        tmpdir = Path(tmp)
        filtered_pbf = tmpdir / "filtered.osm.pbf"

        # Step 1 — narrow the PBF to just the rows we care about. This keeps
        # `osmium export` fast and produces a much smaller intermediate file.
        run(
            [
                "osmium", "tags-filter",
                "--overwrite",
                "-o", str(filtered_pbf),
                str(args.input_pbf),
                *TAG_FILTERS,
            ]
        )

        # Step 2 — convert to GeoJSON. `--geometry-types=point` collapses
        # ways to their centroid so downstream code only ever deals with
        # point geometries.
        run(
            [
                "osmium", "export",
                "--overwrite",
                "--geometry-types=point",
                "-f", "geojson",
                "-o", str(args.output_geojson),
                str(filtered_pbf),
            ]
        )

    # Sanity-check the output is parseable JSON and has at least one feature.
    try:
        gj = json.loads(args.output_geojson.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"ERROR: osmium produced invalid GeoJSON: {e}", file=sys.stderr)
        return 1
    n_features = len(gj.get("features") or [])
    print(
        f"Done: {args.output_geojson} "
        f"({args.output_geojson.stat().st_size // 1024} KB, "
        f"{n_features} features)"
    )
    if n_features == 0:
        print(
            "WARNING: no POIs matched. Check the tag filter list above.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
