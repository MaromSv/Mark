#!/usr/bin/env bash
# Build a vector tiles MBTiles for a single region using Planetiler with the
# OpenMapTiles profile.
#
# Usage:
#   scripts/build-pack/planetiler-build.sh <area> [output-mbtiles]
#
# Examples:
#   scripts/build-pack/planetiler-build.sh netherlands
#   scripts/build-pack/planetiler-build.sh monaco /tmp/mc.mbtiles
#
# <area> is a Geofabrik area name (e.g. netherlands, monaco, bay-area).
# Planetiler auto-downloads the matching .osm.pbf via --download.
#
# Defaults to writing scripts/build-pack/.cache/<area>.mbtiles when invoked
# without an output path. The end product is meant to live inside a per-region
# pack (see build.sh), not directly inside the APK — Tier-0 assets ship via
# scripts/build-pack/skeleton-build.sh, not this script.
#
# Excludes the `building` layer (saves 30–40% of basemap size; we don't render
# buildings at any zoom in this app).

set -euo pipefail

AREA="${1:-netherlands}"
# Planetiler's --area expects the leaf country/state slug (e.g. "netherlands"
# or "california"), not Geofabrik's full path ("europe/netherlands"). Strip
# any directory prefix before passing it on. Keep AREA unchanged for our
# own cache paths so different countries don't collide on a shared
# 'work-netherlands' dir.
PLANETILER_AREA="${AREA##*/}"
DEFAULT_OUT="$(dirname "$0")/.cache/${PLANETILER_AREA}.mbtiles"
OUT="${2:-$DEFAULT_OUT}"

PLANETILER_VERSION="0.8.4"
PLANETILER_URL="https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"

CACHE_DIR="$(dirname "$0")/.cache"
PLANETILER_JAR="${CACHE_DIR}/planetiler-${PLANETILER_VERSION}.jar"
# Replace slashes in the cache path so 'europe/netherlands' doesn't try to
# create a 'work-europe' subdir.
WORK_DIR="${CACHE_DIR}/work-${AREA//\//-}"

mkdir -p "${CACHE_DIR}" "${WORK_DIR}"
mkdir -p "$(dirname "${OUT}")"

# ─── Preflight ────────────────────────────────────────────────────────────────

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: java not found on PATH. Planetiler needs JDK 21+." >&2
    exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | awk -F\" '/version/ { print $2 }' | awk -F. '{ print $1 }')"
if [[ -n "${JAVA_MAJOR:-}" && "${JAVA_MAJOR}" -lt 21 ]]; then
    echo "ERROR: Planetiler ${PLANETILER_VERSION} requires JDK 21+; found ${JAVA_MAJOR}." >&2
    exit 1
fi

# ─── Fetch Planetiler if missing ──────────────────────────────────────────────

if [[ ! -f "${PLANETILER_JAR}" ]]; then
    echo "Downloading Planetiler ${PLANETILER_VERSION}…"
    if command -v curl >/dev/null 2>&1; then
        curl -L --fail -o "${PLANETILER_JAR}.partial" "${PLANETILER_URL}"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "${PLANETILER_JAR}.partial" "${PLANETILER_URL}"
    else
        echo "ERROR: need curl or wget to download Planetiler." >&2
        exit 1
    fi
    mv "${PLANETILER_JAR}.partial" "${PLANETILER_JAR}"
fi

# ─── Build ────────────────────────────────────────────────────────────────────

# Memory: NL fits comfortably in 2 GB; bigger areas need more. Caller can
# override via PLANETILER_XMX env var.
XMX="${PLANETILER_XMX:-3g}"

echo "Building ${OUT} from area=${AREA} (planetiler arg: ${PLANETILER_AREA}, Xmx=${XMX})..."

java "-Xmx${XMX}" -jar "${PLANETILER_JAR}" \
    --download \
    --http_retries=4 \
    --http_timeout=300s \
    --area="${PLANETILER_AREA}" \
    --output="${OUT}" \
    --force \
    --only-layers=water,waterway,transportation,transportation_name,boundary,place,park,water_name \
    --maxzoom=14 \
    --nodemap-type=sortedtable \
    --tmpdir="${WORK_DIR}" \
    --download-dir="${CACHE_DIR}/download"
# `--nodemap-type=sortedtable`: node-location index on disk uses
# ~50 % less space than the default `array` type. Germany has 433M
# nodes; default eats ~7 GB just for the node map and blows the
# runner's free space mid-pass1 with
# `Channel not open for writing - cannot extend file to required size`.
# Sortedtable trades ~10-15 % CPU for the disk savings - well worth
# it inside a 90-min runner budget.
# `--http_retries=4` / `--http_timeout=300`: the natural-earth and
# water-polygons mirrors (osmdata.openstreetmap.de, dev.maptiler.download)
# occasionally take >30s for the initial HEAD/size request, which made
# whole region builds explode with `TimeoutException`. 5-minute timeout
# plus 4 retries handles every flake we've seen without slowing down the
# happy path.

SIZE_MB=$(( $(stat -c%s "${OUT}" 2>/dev/null || stat -f%z "${OUT}") / 1024 / 1024 ))
echo "Done: ${OUT} (${SIZE_MB} MB)"
