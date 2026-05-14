#!/usr/bin/env python3
"""Write the per-pack `manifest.json` for one region pack.

Walks the pack directory, computes sha256 + size for every file (except the
manifest itself), and writes the result in the schema consumed by
`com.example.emergency.offline.pack.PackManifest.parse`.

Usage::

    manifest-build.py --pack-dir <dir> --id <id> --name <name>
                      --type <country|state|metro|custom>
                      --iso <ISO|''>
                      --bbox <W,S,E,N>
                      --version <int>
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import sys
from pathlib import Path

SCHEMA_VERSION = 1
HASH_BUF = 1 << 20  # 1 MiB


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(HASH_BUF):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pack-dir", type=Path, required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument(
        "--type",
        required=True,
        choices=["country", "state", "metro", "custom"],
    )
    ap.add_argument("--iso", default="")
    ap.add_argument(
        "--bbox",
        required=True,
        help="West,South,East,North in decimal degrees",
    )
    ap.add_argument("--version", type=int, required=True)
    args = ap.parse_args()

    pack_dir: Path = args.pack_dir.resolve()
    if not pack_dir.is_dir():
        print(f"ERROR: pack dir not found: {pack_dir}", file=sys.stderr)
        return 66

    bbox = [float(x) for x in args.bbox.split(",")]
    if len(bbox) != 4:
        print("ERROR: --bbox must have 4 comma-separated numbers", file=sys.stderr)
        return 64

    files: list[dict[str, object]] = []
    for p in sorted(pack_dir.rglob("*")):
        if not p.is_file():
            continue
        if p.name == "manifest.json":
            continue  # never inventory the manifest in itself
        rel = p.relative_to(pack_dir).as_posix()
        files.append(
            {
                "path": rel,
                "sizeBytes": p.stat().st_size,
                "sha256": sha256_of(p),
            }
        )

    if not files:
        print(f"ERROR: pack dir is empty: {pack_dir}", file=sys.stderr)
        return 1

    manifest: dict[str, object] = {
        "schemaVersion": SCHEMA_VERSION,
        "id": args.id,
        "name": args.name,
        "type": args.type,
        "bbox": bbox,
        "version": args.version,
        "createdAt": _dt.datetime.now(tz=_dt.timezone.utc).isoformat(
            timespec="seconds"
        ),
        "files": files,
    }
    if args.iso:
        manifest["iso"] = args.iso

    out_path = pack_dir / "manifest.json"
    out_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    total = sum(int(f["sizeBytes"]) for f in files)
    print(
        f"Wrote {out_path} "
        f"({len(files)} files, {total // 1024 // 1024} MB total)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
