#!/usr/bin/env bash
# Build the BRouter routing graph for a region by downloading the prebuilt
# .rd5 segments that intersect a bounding box from brouter.de.
#
# Usage:
#   brouter-build.sh <bbox> <output-dir>
#
# <bbox> is "<west>,<south>,<east>,<north>" in WGS84 degrees (e.g.
# "3.0,50.5,7.5,53.7" for the Netherlands).
#
# <output-dir> receives one .rd5 file per intersecting 5°×5° segment.
#
# Why download instead of running BRouter map-creator (which the plan
# mentions): map-creator needs the planet PBF, ~50 GB of disk and
# ~6 hours per build. The published segments are byte-identical to
# what we'd produce ourselves, gated on the same lookups.dat the
# brouter all-jar already ships. The "stripped lookups.dat" idea in the
# plan is a future optimisation; for the hackathon, parity with stock
# BRouter is the right baseline.
#
# References:
#   - segment grid: 5°×5° tiles aligned at 5°-multiples
#     https://github.com/abrensch/brouter/wiki/Segments
#   - distribution endpoint: https://brouter.de/brouter/segments4

set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <bbox=west,south,east,north> <output-dir>" >&2
    exit 64
fi

BBOX="$1"
OUT_DIR="$2"

IFS=',' read -r WEST SOUTH EAST NORTH <<<"${BBOX}"
for v in WEST SOUTH EAST NORTH; do
    [[ -n "${!v:-}" ]] || { echo "ERROR: bbox is missing $v" >&2; exit 64; }
done

# Sanity-check bbox.
awk -v w="${WEST}" -v s="${SOUTH}" -v e="${EAST}" -v n="${NORTH}" '
    BEGIN {
        if (w >= e || s >= n) { print "ERROR: degenerate bbox"; exit 1 }
        if (w < -180 || e > 180) { print "ERROR: lon out of range"; exit 1 }
        if (s < -90  || n > 90)  { print "ERROR: lat out of range"; exit 1 }
    }
' >&2

mkdir -p "${OUT_DIR}"

SEGMENTS_BASE_URL="${BROUTER_SEGMENTS_BASE_URL:-https://brouter.de/brouter/segments4}"

fetch() {
    local url="$1" dest="$2"
    if [[ -s "${dest}" ]]; then
        echo "    cached  ${dest##*/}"
        return 0
    fi
    if ! command -v curl >/dev/null 2>&1; then
        echo "ERROR: curl required for retry logic" >&2; exit 69
    fi

    local tmp="${dest}.partial"
    local attempt=1 max_attempts=4 delay=2 http_code
    while (( attempt <= max_attempts )); do
        # `--write-out %{http_code}` gives us the response code without
        # `-f`, so we can distinguish 404 (this segment isn't published)
        # from 5xx / network blips (worth retrying).
        http_code="$(curl -Ls -o "${tmp}" -w '%{http_code}' --max-time 120 "${url}" || echo "000")"
        case "${http_code}" in
            2??)
                mv "${tmp}" "${dest}"
                echo "    fetched ${dest##*/}"
                return 0
                ;;
            404)
                # brouter.de doesn't publish segments for empty-ocean
                # tiles. A region's bbox can include such a tile (e.g.
                # Ireland's eastern bbox edge extending into the
                # E5°-E10° column where Wales/England live but the
                # segment grid happens to not have an .rd5). Treat as
                # non-fatal: warn and continue. The pack still routes
                # everywhere covered by the segments we did get.
                rm -f "${tmp}"
                echo "    skipped ${dest##*/} (HTTP 404 - segment not published)" >&2
                return 0
                ;;
            *)
                rm -f "${tmp}"
                echo "    HTTP ${http_code} for ${dest##*/}, attempt ${attempt}/${max_attempts}" >&2
                ;;
        esac
        sleep "${delay}"
        attempt=$(( attempt + 1 ))
        delay=$(( delay * 2 ))
    done

    echo "ERROR: gave up on ${url} after ${max_attempts} attempts (last HTTP=${http_code})" >&2
    return 1
}

# Translate lon/lat (5°-aligned origin of a segment tile) → BRouter's filename.
# BRouter names segments by the SW corner: W120_S30, E5_N50, etc.
seg_name() {
    local lon="$1" lat="$2"
    local lon_prefix lat_prefix
    if (( lon < 0 )); then lon_prefix="W"; lon=$(( -lon )); else lon_prefix="E"; fi
    if (( lat < 0 )); then lat_prefix="S"; lat=$(( -lat )); else lat_prefix="N"; fi
    printf '%s%d_%s%d' "${lon_prefix}" "${lon}" "${lat_prefix}" "${lat}"
}

# Snap bbox to the 5° grid and walk every covered tile. floor() in bash via
# arithmetic on 1e6-scaled microdegrees so we don't depend on `bc`.
floor_to_5() {
    # echo `floor(x / 5) * 5` for any decimal x via awk.
    awk -v x="$1" 'BEGIN { printf "%d", (x>=0 ? int(x/5) : int((x-4.999999)/5)) * 5 }'
}

W5="$(floor_to_5 "${WEST}")"
S5="$(floor_to_5 "${SOUTH}")"
# `ceil(x/5)*5` for the east/north — done as floor((x-eps)/5)+5 so a bbox
# whose east edge lies exactly on a 5° line doesn't pull in an empty extra
# segment.
ceil_to_5() {
    awk -v x="$1" 'BEGIN {
        s = (x>=0 ? int(x/5) : int((x-4.999999)/5)) * 5
        # if the value already lies on the grid, return it untouched
        if (x == s) print s; else print s + 5
    }'
}
E5="$(ceil_to_5 "${EAST}")"
N5="$(ceil_to_5 "${NORTH}")"

echo "Downloading BRouter segments for bbox=${BBOX}"
echo "  snapped to 5° grid: W${W5} S${S5} E${E5} N${N5}"

count=0
for (( lon = W5; lon < E5; lon += 5 )); do
    for (( lat = S5; lat < N5; lat += 5 )); do
        name="$(seg_name "${lon}" "${lat}").rd5"
        fetch "${SEGMENTS_BASE_URL}/${name}" "${OUT_DIR}/${name}"
        count=$(( count + 1 ))
    done
done

echo "Done: ${count} segment(s) in ${OUT_DIR}"
