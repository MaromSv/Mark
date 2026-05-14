package com.example.emergency.agent.tools.rag

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/**
 * Minimal BERT WordPiece tokenizer in pure Kotlin.
 *
 * Reads the `tokenizer.json` exported from HuggingFace for MiniLM
 * (assets/rag/tokenizer.json). Behaviour matches the reference
 * implementation in `transformers.BertTokenizer` for the configuration
 * MiniLM ships with:
 *   - normalizer: BertNormalizer (lowercase=true, strip_accents=true,
 *     clean_text=true)
 *   - pre_tokenizer: BertPreTokenizer (split on whitespace + punctuation)
 *   - model: WordPiece (continuing_subword_prefix="##", unk_token="[UNK]")
 *
 * We don't claim parity with edge cases the BERT reference handles (CJK
 * char-by-char tokenization, byte-level fallback) — they don't affect
 * English medical text. If retrieval quality dips on a non-English query
 * later, revisit.
 *
 * Used instead of DJL's HuggingFaceTokenizer because that JAR doesn't
 * ship Android native libs (loads fine on desktop, blows up at runtime
 * on Android with "Unexpected flavor: cpu").
 */
class BertTokenizer(context: Context) {

    data class Encoding(
        val ids: LongArray,
        val attentionMask: LongArray,
        val typeIds: LongArray,
    ) {
        val size: Int get() = ids.size
    }

    private val vocab: Map<String, Int>
    private val unkId: Int
    private val clsId: Int
    private val sepId: Int
    private val doLowercase: Boolean
    private val stripAccents: Boolean
    private val continuingPrefix: String
    private val maxInputCharsPerWord: Int

    init {
        val raw = context.assets.open("rag/tokenizer.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val model = root.getJSONObject("model")
        val vocabJson = model.getJSONObject("vocab")
        val map = HashMap<String, Int>(vocabJson.length())
        val keys = vocabJson.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = vocabJson.getInt(k)
        }
        vocab = map

        val unkTok = model.optString("unk_token", "[UNK]")
        unkId = vocab[unkTok] ?: error("Vocab missing $unkTok")
        clsId = vocab["[CLS]"] ?: error("Vocab missing [CLS]")
        sepId = vocab["[SEP]"] ?: error("Vocab missing [SEP]")
        continuingPrefix = model.optString("continuing_subword_prefix", "##")
        maxInputCharsPerWord = model.optInt("max_input_chars_per_word", 100)

        // BertNormalizer config (defaults match MiniLM's tokenizer.json):
        val norm = root.optJSONObject("normalizer")
        doLowercase = norm?.optBoolean("lowercase", true) ?: true
        // `strip_accents` is JSON `null` for MiniLM, which the HF spec treats
        // as "follow lowercase" (strip when lowercase=true).
        stripAccents = when {
            norm == null -> true
            norm.isNull("strip_accents") -> doLowercase
            else -> norm.optBoolean("strip_accents", doLowercase)
        }
    }

    /** Encode a single string. Adds [CLS]/[SEP] and truncates to [maxLen]. */
    fun encode(text: String, maxLen: Int = 384): Encoding {
        val ids = ArrayList<Int>(minOf(maxLen, 64))
        ids.add(clsId)
        val budget = maxLen - 1  // reserve one slot for [SEP]
        outer@ for (word in basicTokenize(text)) {
            for (sub in wordpiece(word)) {
                if (ids.size >= budget) break@outer
                ids.add(sub)
            }
        }
        ids.add(sepId)
        val n = ids.size
        val idsArr = LongArray(n) { ids[it].toLong() }
        val mask = LongArray(n) { 1L }
        val typeIds = LongArray(n) { 0L }
        return Encoding(idsArr, mask, typeIds)
    }

    // ── Basic (pre-)tokenizer ───────────────────────────────────────────
    // Mirrors BertNormalizer + BertPreTokenizer: clean → lowercase →
    // strip accents → split on whitespace and punctuation.

    private fun basicTokenize(text: String): List<String> {
        var s = cleanText(text)
        if (doLowercase) s = s.lowercase()
        if (stripAccents) s = stripAccentsFn(s)

        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in s) {
            if (ch.isWhitespaceLike()) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
            } else if (isPunctuation(ch)) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                out.add(ch.toString())
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun cleanText(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val cp = ch.code
            if (cp == 0 || cp == 0xFFFD || ch.isControlButNotWhitespace()) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun stripAccentsFn(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) {
            // Filter combining-mark category (Mn = Nonspacing_Mark).
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return sb.toString()
    }

    private fun Char.isWhitespaceLike(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r' || this.isWhitespace()

    private fun Char.isControlButNotWhitespace(): Boolean {
        if (isWhitespaceLike()) return false
        val type = Character.getType(this)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.SURROGATE.toInt() ||
            type == Character.PRIVATE_USE.toInt() ||
            type == Character.UNASSIGNED.toInt()
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        // ASCII punctuation ranges (matches BERT's _is_punctuation).
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    // ── WordPiece ───────────────────────────────────────────────────────
    // Greedy longest-match starting at the left of the word. Subwords
    // after the first are prefixed with "##". Returns [UNK] for words
    // that exceed the per-word char cap or whose suffix can't be matched.

    private fun wordpiece(word: String): List<Int> {
        if (word.length > maxInputCharsPerWord) return listOf(unkId)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched: Int = -1
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end)
                          else continuingPrefix + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched == -1) return listOf(unkId)
            out.add(matched)
            start = end
        }
        return out
    }
}
