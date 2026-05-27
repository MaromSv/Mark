#!/usr/bin/env bash
# Top-level orchestrator: turns a region id into a single tar.gz that the
# device's PackDownloader can install end-to-end.
#
# Pipeline (plan §5):
#   1. Resolve region id → Geofabrik area + bbox via regions.json.
#   2. Vector tiles  — planetiler-build.sh   → tiles.mbtiles
#   3. Routing graph — brouter-build.sh      → routing/*.rd5
#   4. POIs          — pois-extract.py       → pois.geojson
#   5. Per-pack manifest.json (sha256 + size per file).
#   6. tar czf <id>-v<version>.tar.gz {tiles, routing/, pois, manifest}.
#
# Usage:
#   build.sh <region-id> [version]
#
#   build.sh nl
#   build.sh ch 2
#
# Outputs to scripts/build-pack/.cache/packs/<id>/ and writes the final
# tarball to scripts/build-pack/dist/<id>-v<version>.tar.gz.
#
# Design notes:
#   - PBF download is delegated to Planetiler's `--download` flag so we
#     only fetch the source PBF once per build. brouter-build.sh and
#     pois-extract.py reuse the cached PBF from .cache/download/.
#   - Manifest sha256s are computed *after* every artefact is finalized
#     so a half-finished build never produces a checksum that doesn't
#     match the on-disk bytes.
#   - The tarball is built with a deterministic file order so two runs
#     against the same source data produce byte-identical archives —
#     this lets us reuse uploads when nothing meaningful has changed.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <region-id> [version]" >&2
    exit 64
fi

REGION_ID="$1"
VERSION="${2:-1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REGIONS_JSON="${SCRIPT_DIR}/regions.json"

# ─── Resolve region from regions.json ────────────────────────────────────────

if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 is required to parse regions.json" >&2
    exit 69
fi
if [[ ! -f "${REGIONS_JSON}" ]]; then
    echo "ERROR: regions.json missing at ${REGIONS_JSON}" >&2
    exit 66
fi

read_region() {
    python3 - "$REGIONS_JSON" "$REGION_ID" <<'PY'
import json, sys
catalog = json.loads(open(sys.argv[1]).read())
target = sys.argv[2]
for r in catalog["regions"]:
    if r["id"] == target:
        bbox = r["bbox"]
        # Emit shell-eval-safe k=v lines.
        print(f"REGION_NAME={r['name']!r}")
        print(f"REGION_TYPE={r['type']!r}")
        print(f"REGION_ISO={r.get('iso','')!r}")
        print(f"GEOFABRIK_AREA={r['geofabrikArea']!r}")
        print(f"BBOX={','.join(str(x) for x in bbox)!r}")
        sys.exit(0)
print(f"ERROR: region '{target}' not in regions.json", file=sys.stderr)
sys.exit(2)
PY
}

# Capture region lookup output before eval — `eval "$(cmd)"` masks `cmd`'s
# exit code under set -e on some bash versions, leaving us with empty
# variables and a confusing 'unbound variable' error downstream.
if ! REGION_VARS="$(read_region)"; then
    exit 1
fi
eval "${REGION_VARS}"

CACHE_DIR="${SCRIPT_DIR}/.cache"
PACK_DIR="${CACHE_DIR}/packs/${REGION_ID}"
DIST_DIR="${SCRIPT_DIR}/dist"
TARBALL="${DIST_DIR}/${REGION_ID}-v${VERSION}.tar.gz"

# Start from a clean slate so a stale half-built pack can never bleed into
# a new tarball.
rm -rf "${PACK_DIR}"
mkdir -p "${PACK_DIR}/routing" "${DIST_DIR}"

echo "================================================================"
echo "Building pack: ${REGION_ID} v${VERSION} (${REGION_NAME})"
echo "  area:   ${GEOFABRIK_AREA}"
echo "  bbox:   ${BBOX}"
echo "  output: ${TARBALL}"
echo "================================================================"

# ─── Step 1: vector tiles via Planetiler ─────────────────────────────────────

echo
echo "[1/5] Vector tiles → ${PACK_DIR}/tiles.mbtiles"
bash "${SCRIPT_DIR}/planetiler-build.sh" "${GEOFABRIK_AREA}" "${PACK_DIR}/tiles.mbtiles"

# Planetiler caches the source PBF at .cache/download/<leaf>.osm.pbf
# (the leaf component of the Geofabrik path) — see planetiler-build.sh
# which now passes that as `--osm_path`. Reuse it for BRouter and POI
# extraction so we don't refetch the 100MB-5GB blob.
AREA_LEAF="${GEOFABRIK_AREA##*/}"
SOURCE_PBF="${CACHE_DIR}/download/${AREA_LEAF}.osm.pbf"
if [[ ! -f "${SOURCE_PBF}" ]]; then
    # Fallback for older script versions that named the file differently.
    SOURCE_PBF="$(find "${CACHE_DIR}/download" -name '*.osm.pbf' | head -n1 || true)"
    [[ -n "${SOURCE_PBF}" ]] || {
        echo "ERROR: couldn't locate cached PBF after Planetiler run" >&2
        exit 1
    }
fi

# ─── Step 2: routing graph via brouter-build.sh ──────────────────────────────

echo
echo "[2/5] BRouter segments → ${PACK_DIR}/routing/"
bash "${SCRIPT_DIR}/brouter-build.sh" "${BBOX}" "${PACK_DIR}/routing"

# ─── Step 3: POIs via pois-extract.py ────────────────────────────────────────

echo
echo "[3/5] POIs → ${PACK_DIR}/pois.geojson"
python3 "${SCRIPT_DIR}/pois-extract.py" "${SOURCE_PBF}" "${PACK_DIR}/pois.geojson"

# ─── Step 4: per-pack manifest.json ──────────────────────────────────────────

echo
echo "[4/5] Manifest → ${PACK_DIR}/manifest.json"
python3 "${SCRIPT_DIR}/manifest-build.py" \
    --pack-dir "${PACK_DIR}" \
    --id "${REGION_ID}" \
    --name "${REGION_NAME}" \
    --type "${REGION_TYPE}" \
    --iso "${REGION_ISO}" \
    --bbox="${BBOX}" \
    --version "${VERSION}"
# `--bbox=` (with the equals sign) is required, not optional: bboxes that
# start with a negative longitude/latitude (the entire western hemisphere
# plus places like the Faroe Islands and Iceland) get split into two argv
# tokens by `--bbox "${BBOX}"`, and argparse treats the leading `-` as a
# new flag, failing with "expected one argument".

# ─── Step 5: tar.gz ──────────────────────────────────────────────────────────

echo
echo "[5/5] Packing → ${TARBALL}"
# `--sort=name` + fixed mtime makes the archive deterministic across runs
# (no node-id or build-time leak into the tarball metadata).
tar \
    --sort=name \
    --owner=0 --group=0 --numeric-owner \
    --mtime='2026-01-01 00:00:00 UTC' \
    -czf "${TARBALL}" \
    -C "${PACK_DIR}" .

TAR_SHA="$(sha256sum "${TARBALL}" | awk '{print $1}')"
TAR_SIZE_MB=$(( $(stat -c%s "${TARBALL}" 2>/dev/null || stat -f%z "${TARBALL}") / 1024 / 1024 ))

echo
echo "================================================================"
echo "  ${TARBALL}"
echo "  size:   ${TAR_SIZE_MB} MB"
echo "  sha256: ${TAR_SHA}"
echo "================================================================"
