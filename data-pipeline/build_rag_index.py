"""
Build the on-device RAG index for MedicalRagTool.

Reads:   app/src/main/data/core_medical.json   (source of truth)
Writes:  app/src/main/assets/core_medical.json (mirror, what the app loads)
         app/src/main/assets/rag/model.onnx    (MiniLM, ~22MB)
         app/src/main/assets/rag/tokenizer.json
         app/src/main/assets/rag/embeddings.bin (int8, plus per-vector scale)
         app/src/main/assets/rag/chunks.json   (chunk text + metadata)
         app/src/main/assets/rag/index_meta.json (dim, count, version)

Run:
    pip install sentence-transformers optimum[onnxruntime] huggingface_hub
    python data-pipeline/build_rag_index.py

Re-run whenever core_medical.json changes.
"""

from __future__ import annotations

import json
import re
import shutil
import struct
from pathlib import Path

import numpy as np
from huggingface_hub import snapshot_download
from optimum.onnxruntime import ORTModelForFeatureExtraction
from sentence_transformers import SentenceTransformer
from transformers import AutoTokenizer

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
EMBED_DIM = 384
CHUNK_TARGET_TOKENS = 256
CHUNK_OVERLAP_TOKENS = 48
MAX_TOKENS_PER_CHUNK = 384  # MiniLM's window is 512, leave headroom

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_JSON = REPO_ROOT / "app/src/main/data/core_medical.json"
ASSETS_DIR = REPO_ROOT / "app/src/main/assets"
ASSETS_JSON = ASSETS_DIR / "core_medical.json"
RAG_DIR = ASSETS_DIR / "rag"


def load_documents() -> list[dict]:
    with SRC_JSON.open(encoding="utf-8") as f:
        data = json.load(f)
    return data["documents"]


# ── Chunking ────────────────────────────────────────────────────────────────
# Split each document into roughly section-sized pieces, then a sliding
# window over too-long sections. Keep the doc title prepended to every
# chunk so the embedding has topical context even for tiny snippets.

SECTION_HEADERS = {
    "Background", "Clinical Features", "Differential Diagnosis",
    "Evaluation", "Management", "Disposition", "Treatment", "Diagnosis",
    "Prevention", "Prognosis", "Complications", "See Also", "References",
    "Workup", "Risk Factors", "Pathophysiology", "Epidemiology",
    "Indications", "Contraindications", "Procedure", "Dosing",
}


def split_sections(content: str) -> list[tuple[str, str]]:
    lines = content.split("\n")
    sections: list[tuple[str, str]] = []
    cur_header = "Overview"
    cur_body: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped in SECTION_HEADERS:
            if cur_body:
                sections.append((cur_header, "\n".join(cur_body).strip()))
            cur_header = stripped
            cur_body = []
        else:
            cur_body.append(line)
    if cur_body:
        sections.append((cur_header, "\n".join(cur_body).strip()))
    return [(h, b) for h, b in sections if b]


def sliding_window(tokens: list[int], target: int, overlap: int) -> list[list[int]]:
    if len(tokens) <= target:
        return [tokens]
    step = target - overlap
    windows = []
    for start in range(0, len(tokens), step):
        chunk = tokens[start:start + target]
        if not chunk:
            break
        windows.append(chunk)
        if start + target >= len(tokens):
            break
    return windows


def build_chunks(docs: list[dict], tokenizer) -> list[dict]:
    chunks: list[dict] = []
    for doc in docs:
        title = doc["title"]
        category = doc.get("category", "")
        priority = doc.get("priority", "normal")
        tags = doc.get("tags", []) or []
        for header, body in split_sections(doc["content"]):
            prefix = f"{title} — {header}\n"
            full = prefix + body
            tokens = tokenizer.encode(full, add_special_tokens=False)
            for window in sliding_window(tokens, CHUNK_TARGET_TOKENS, CHUNK_OVERLAP_TOKENS):
                if len(window) > MAX_TOKENS_PER_CHUNK:
                    window = window[:MAX_TOKENS_PER_CHUNK]
                text = tokenizer.decode(window, skip_special_tokens=True).strip()
                if len(text) < 30:
                    continue
                chunks.append({
                    "title": title,
                    "category": category,
                    "section": header,
                    "priority": priority,
                    "tags": tags,
                    "text": text,
                })
    return chunks


# ── int8 quantization ───────────────────────────────────────────────────────
# Per-vector symmetric int8 quantization. Each row stored as:
#     float32 scale  +  EMBED_DIM * int8 values
# Dequant on device: float = int8 * scale.

def quantize_int8(vectors: np.ndarray) -> bytes:
    assert vectors.dtype == np.float32
    out = bytearray()
    for row in vectors:
        scale = float(np.max(np.abs(row)))
        if scale == 0.0:
            scale = 1.0
        q = np.round(row / scale * 127.0).clip(-127, 127).astype(np.int8)
        out += struct.pack("<f", scale / 127.0)
        out += q.tobytes()
    return bytes(out)


# ── Main ────────────────────────────────────────────────────────────────────

def main() -> None:
    print(f"[1/6] Loading {SRC_JSON.relative_to(REPO_ROOT)}…")
    docs = load_documents()
    print(f"      {len(docs)} documents")

    print(f"[2/6] Mirroring → {ASSETS_JSON.relative_to(REPO_ROOT)}…")
    shutil.copyfile(SRC_JSON, ASSETS_JSON)

    print(f"[3/6] Loading tokenizer + sentence-transformer ({MODEL_ID})…")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    st_model = SentenceTransformer(MODEL_ID)

    print("[4/6] Chunking documents…")
    chunks = build_chunks(docs, tokenizer)
    print(f"      {len(chunks)} chunks")

    print("[5/6] Embedding chunks (this is the slow part)…")
    texts = [c["text"] for c in chunks]
    vectors = st_model.encode(
        texts,
        batch_size=64,
        show_progress_bar=True,
        normalize_embeddings=True,  # L2-normalize so dot-product == cosine on device
        convert_to_numpy=True,
    ).astype(np.float32)
    assert vectors.shape == (len(chunks), EMBED_DIM), vectors.shape

    print(f"[6/6] Exporting ONNX + tokenizer + vectors → {RAG_DIR.relative_to(REPO_ROOT)}/")
    RAG_DIR.mkdir(parents=True, exist_ok=True)

    # ONNX export. Optimum gives us a clean ORT-compatible graph.
    print("      exporting ONNX (optimum)…")
    ort_model = ORTModelForFeatureExtraction.from_pretrained(MODEL_ID, export=True)
    tmp_dir = RAG_DIR / "_export_tmp"
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    ort_model.save_pretrained(tmp_dir)
    # The exported file is named "model.onnx" inside tmp_dir.
    shutil.copyfile(tmp_dir / "model.onnx", RAG_DIR / "model.onnx")
    # Also pull the matching tokenizer.json (fast tokenizer, single file).
    src_tok = snapshot_download(MODEL_ID, allow_patterns=["tokenizer.json"])
    shutil.copyfile(Path(src_tok) / "tokenizer.json", RAG_DIR / "tokenizer.json")
    shutil.rmtree(tmp_dir, ignore_errors=True)

    print("      writing embeddings.bin (int8)…")
    (RAG_DIR / "embeddings.bin").write_bytes(quantize_int8(vectors))

    print("      writing chunks.json…")
    with (RAG_DIR / "chunks.json").open("w", encoding="utf-8") as f:
        json.dump({"chunks": chunks}, f, ensure_ascii=False)

    with (RAG_DIR / "index_meta.json").open("w", encoding="utf-8") as f:
        json.dump({
            "model": MODEL_ID,
            "dim": EMBED_DIM,
            "count": len(chunks),
            "quantization": "int8_per_row_symmetric",
            "row_bytes": 4 + EMBED_DIM,  # float32 scale + EMBED_DIM int8s
        }, f, indent=2)

    embed_size_mb = (RAG_DIR / "embeddings.bin").stat().st_size / 1e6
    onnx_size_mb = (RAG_DIR / "model.onnx").stat().st_size / 1e6
    print()
    print(f"  done. {len(chunks)} chunks, embeddings.bin = {embed_size_mb:.2f} MB, model.onnx = {onnx_size_mb:.2f} MB")


if __name__ == "__main__":
    main()
