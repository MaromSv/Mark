#!/usr/bin/env python3
"""Build the bundled `presets.json` browsed by the region picker.

Produces a tree of pickable regions — countries → states/provinces → metros
— that the Browse tab in the picker UI walks (plan §8 step 5). Each leaf
points at a region pack id and ships its bbox so the picker can show
"approximately X MB" without fetching anything from the network.

The output schema is intentionally simple — a flat array per group, with a
`children` array on countries that have sub-regions worth exposing. The
picker doesn't do tree traversal beyond two levels for the hackathon.

By default the script reads the existing `regions.json` (the build-pack
catalog) so adding a country is a one-place change. Hand-curated metro
sub-regions live inline below and can be augmented over time.

Output: `app/src/main/assets/bundled/presets.json`
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCHEMA_VERSION = 1

# Hand-curated metros / states keyed by their parent country's ISO code.
# Adding a new metro: append here AND make sure the bbox sits inside the
# parent country's bbox in regions.json. The picker UI only enables the
# "Download" button on entries whose `id` matches a buildable pack — for
# now that's just the country-level entries; metros are advisory until a
# matching `regions.json` row exists.
SUBREGIONS: dict[str, list[dict[str, object]]] = {
    "NL": [
        {
            "id": "nl-randstad",
            "name": "Randstad (Amsterdam–Rotterdam)",
            "type": "metro",
            "bbox": [4.20, 51.85, 5.20, 52.55],
        },
    ],
    "CH": [
        {
            "id": "ch-zurich",
            "name": "Zürich Metro",
            "type": "metro",
            "bbox": [8.30, 47.20, 8.80, 47.55],
        },
        {
            "id": "ch-geneva",
            "name": "Geneva–Lausanne",
            "type": "metro",
            "bbox": [5.95, 46.10, 6.90, 46.60],
        },
    ],
    "BE": [
        {
            "id": "be-brussels",
            "name": "Brussels Metro",
            "type": "metro",
            "bbox": [4.20, 50.75, 4.55, 50.95],
        },
    ],
}


def load_regions(path: Path) -> list[dict[str, object]]:
    with path.open() as f:
        return json.load(f)["regions"]


def build_presets(regions: list[dict[str, object]]) -> dict[str, object]:
    countries: list[dict[str, object]] = []
    for r in regions:
        if r["type"] != "country":
            continue
        entry: dict[str, object] = {
            "id": r["id"],
            "name": r["name"],
            "type": "country",
            "bbox": r["bbox"],
        }
        if r.get("iso"):
            entry["iso"] = r["iso"]
            children = SUBREGIONS.get(str(r["iso"]).upper())
            if children:
                entry["children"] = children
        countries.append(entry)

    return {
        "schemaVersion": SCHEMA_VERSION,
        "countries": countries,
    }


def main() -> int:
    here = Path(__file__).resolve().parent
    default_regions = here / "regions.json"
    default_out = (
        here.parent.parent / "app/src/main/assets/bundled/presets.json"
    )
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--regions", type=Path, default=default_regions)
    ap.add_argument("--out", type=Path, default=default_out)
    args = ap.parse_args()

    if not args.regions.exists():
        print(f"ERROR: regions.json not found at {args.regions}", file=sys.stderr)
        return 66

    regions = load_regions(args.regions)
    presets = build_presets(regions)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(presets, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    n_countries = len(presets["countries"])
    n_metros = sum(
        len(c.get("children", [])) for c in presets["countries"]  # type: ignore[arg-type]
    )
    print(
        f"Wrote {args.out} ({n_countries} countries, {n_metros} sub-regions)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
