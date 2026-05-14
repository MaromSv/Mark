#!/usr/bin/env bash
# Build the Tier-0 global skeleton vector mbtiles (plan §3).
#
# Output: app/src/main/assets/bundled/skeleton.mbtiles — global OpenMapTiles
# vector basemap, z0–z6, building layer excluded. Target size ≈ 30 MB.
#
# Usage:
#   scripts/build-pack/skeleton-build.sh                       # planet, default
#   scripts/build-pack/skeleton-build.sh <area>                # any Geofabrik area
#   scripts/build-pack/skeleton-build.sh planet /tmp/sk.mbtiles
#
# Cost (planet, on a fast laptop):
#   - first run: downloads ~80 GB planet PBF, ~3 h CPU, ~50 GB scratch.
#   - re-run with cached PBF: ~30–60 min CPU.
#
# Cheap alternative for dev iteration: pass a small Geofabrik area name
# (e.g. `monaco`) to produce a regional placeholder skeleton in seconds.
# The shipping APK should always be built from `planet` so the world basemap
# is global, not just one country.
#
# Why a separate script from planetiler-build.sh:
#   - skeleton stays at z0–z6 (low zoom only); regional packs cover z7–z14.
#   - skeleton has no maxbounds clipping — planet-wide coverage is the point.
#   - output path defaults to assets/bundled/, not assets/tiles/.

set -euo pipefail

AREA="${1:-planet}"
# Planetiler's --area expects a leaf slug (planet, netherlands, california),
# not a Geofabrik path prefix. Strip directories.
PLANETILER_AREA="${AREA##*/}"
DEFAULT_OUT="$(dirname "$0")/../../app/src/main/assets/bundled/skeleton.mbtiles"
OUT="${2:-$DEFAULT_OUT}"

PLANETILER_VERSION="0.8.4"
PLANETILER_URL="https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"

CACHE_DIR="$(dirname "$0")/.cache"
PLANETILER_JAR="${CACHE_DIR}/planetiler-${PLANETILER_VERSION}.jar"
WORK_DIR="${CACHE_DIR}/work-skeleton-${AREA}"

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

# Planet z0-z6 fits in 4 GB heap; placeholder regional builds stay well under.
# Caller can override via PLANETILER_XMX env var.
XMX="${PLANETILER_XMX:-4g}"

echo "Building skeleton ${OUT} from area=${AREA} maxzoom=6 (Xmx=${XMX})…"
echo "  This is the Tier-0 global basemap; per-region detail comes from packs."

java "-Xmx${XMX}" -jar "${PLANETILER_JAR}" \
    --download \
    --area="${PLANETILER_AREA}" \
    --output="${OUT}" \
    --force \
    --exclude-layers=building \
    --maxzoom=6 \
    --tmpdir="${WORK_DIR}" \
    --download-dir="${CACHE_DIR}/download"

SIZE_MB=$(( $(stat -c%s "${OUT}" 2>/dev/null || stat -f%z "${OUT}") / 1024 / 1024 ))
echo "Done: ${OUT} (${SIZE_MB} MB)"

if [[ "${AREA}" != "planet" && "${AREA}" != "world" ]]; then
    echo
    echo "  WARNING: skeleton built from regional area '${AREA}', not the planet."
    echo "  The shipping APK must be rebuilt with --area=planet so users outside"
    echo "  this region see *some* basemap before downloading a detail pack."
fi
