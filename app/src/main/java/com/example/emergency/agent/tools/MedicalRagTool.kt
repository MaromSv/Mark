package com.example.emergency.agent.tools

import android.content.Context
import com.example.emergency.agent.Tool
import com.example.emergency.agent.ToolResult
import org.json.JSONObject
import java.io.File

/**
 * Medical RAG tool - searches through medical database for relevant information.
 */
class MedicalRagTool(private val context: Context) {
    
    private var medicalDocuments: List<MedicalDocument> = emptyList()
    
    data class MedicalDocument(
        val title: String,
        val category: String,
        val content: String,
        val tags: List<String>,
        val priority: String,
    )
    
    fun getTool(): Tool = Tool(
        name = "search_medical_database",
        description = "Retrieves medical protocols for symptoms, wounds, injuries, or treatments. Required param: query (e.g., 'tourniquet', 'burn', 'chest pain'). Use for any medical question, including image-based wound assessment.",
        execute = ::execute,
    )

    init {
        loadMedicalDatabase()
    }
    
    private fun loadMedicalDatabase() {
        try {
            // Load from assets
            val jsonContent = context.assets.open("core_medical.json").bufferedReader().use { it.readText() }
            
            val jsonObject = JSONObject(jsonContent)
            val documentsArray = jsonObject.getJSONArray("documents")
            
            medicalDocuments = (0 until documentsArray.length()).map { i ->
                val doc = documentsArray.getJSONObject(i)
                val tagsArray = doc.optJSONArray("tags")
                val tags = if (tagsArray != null) {
                    (0 until tagsArray.length()).map { j -> tagsArray.getString(j) }
                } else {
                    emptyList()
                }
                
                MedicalDocument(
                    title = doc.getString("title"),
                    category = doc.getString("category"),
                    content = doc.getString("content"),
                    tags = tags,
                    priority = doc.optString("priority", "normal")
                )
            }
        } catch (e: Exception) {
            // Silently fail - tool will return no results
            medicalDocuments = emptyList()
        }
    }
    
    // -- Synonym / related-term map --------------------------------------
    // Maps a query keyword to additional search terms so that e.g.
    // "tourniquet" also surfaces Hemorrhage, Laceration, Penetrating trauma.
    private val synonyms: Map<String, List<String>> = mapOf(
        "tourniquet" to listOf("hemorrhage", "bleeding", "laceration", "amputation", "penetrating", "wound", "crush"),
        "bleeding" to listOf("hemorrhage", "laceration", "wound", "tourniquet", "blood"),
        "wound" to listOf("laceration", "bleeding", "hemorrhage", "penetrating", "cut"),
        "cpr" to listOf("cardiac arrest", "compressions", "resuscitation", "pulse"),
        "choking" to listOf("airway", "obstruction", "heimlich"),
        "heart attack" to listOf("myocardial infarction", "chest pain", "coronary", "acs", "stemi"),
        "stroke" to listOf("neurologic", "facial droop", "weakness", "speech", "tpa"),
        "overdose" to listOf("opioid", "naloxone", "toxicity", "ingestion"),
        "burn" to listOf("thermal", "scald", "flame", "chemical burn"),
        "fracture" to listOf("broken", "bone", "splint", "dislocation", "immobilize"),
        "concussion" to listOf("head injury", "brain", "consciousness", "trauma"),
        "seizure" to listOf("convulsion", "epilepsy", "status epilepticus"),
        "drowning" to listOf("submersion", "water", "rescue breathing"),
        "poisoning" to listOf("toxicity", "ingestion", "overdose", "antidote"),
        "shock" to listOf("hypotension", "blood pressure", "tachycardia", "perfusion"),
        "snake bite" to listOf("envenomation", "venom", "antivenom"),
        "breathing" to listOf("airway", "respiratory", "dyspnea", "oxygen"),
        "allergic" to listOf("anaphylaxis", "epinephrine", "epipen", "hives"),
        "splint" to listOf("fracture", "immobilize", "broken", "bone"),
        "bandage" to listOf("wound", "dressing", "bleeding", "laceration"),
        "aed" to listOf("defibrillator", "cardiac arrest", "ventricular fibrillation"),
        "chest pain" to listOf("myocardial infarction", "acs", "coronary", "angina", "stemi"),
    )

    private suspend fun execute(params: Map<String, String>): ToolResult {
        val rawQuery = params["query"]?.lowercase()?.trim() ?: return ToolResult(
            success = false,
            data = "",
            error = "No query provided"
        )

        if (medicalDocuments.isEmpty()) {
            return ToolResult(
                success = false,
                data = "",
                error = "Medical database not loaded"
            )
        }

        // Build the set of search terms: original query words + synonyms
        val queryWords = rawQuery.split("\\s+".toRegex()).filter { it.length > 2 }
        val expandedTerms = buildSet {
            add(rawQuery)                       // full phrase
            addAll(queryWords)                  // individual words
            queryWords.forEach { w ->
                synonyms[w]?.let { addAll(it) } // synonym expansions
            }
            synonyms[rawQuery]?.let { addAll(it) }
        }

        val results = medicalDocuments
            .map { doc -> Pair(doc, scoreDocument(doc, rawQuery, expandedTerms)) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(3)

        if (results.isEmpty()) {
            return ToolResult(
                success = true,
                data = "No relevant medical information found for: $rawQuery"
            )
        }

        val bestTerms = expandedTerms.toList()
        val resultText = buildString {
            appendLine("Medical database results for '$rawQuery':\n")
            results.forEachIndexed { index, (doc, _) ->
                appendLine("${index + 1}. ${doc.title} (${doc.category})")
                val excerpt = extractBestExcerpt(doc.content, bestTerms)
                appendLine(excerpt)
                appendLine()
            }
        }

        return ToolResult(success = true, data = resultText.trim())
    }

    // -- Scoring ----------------------------------------------------------
    private fun scoreDocument(
        doc: MedicalDocument,
        rawQuery: String,
        terms: Set<String>,
    ): Double {
        val titleLower = doc.title.lowercase()
        val catLower = doc.category.lowercase()
        val contentLower = doc.content.lowercase()
        val tagsLower = doc.tags.map { it.lowercase() }

        var score = 0.0

        // Exact full-query match in title -> strongest signal
        if (titleLower.contains(rawQuery)) score += 30.0

        for (term in terms) {
            val isOriginal = term == rawQuery || term in rawQuery.split("\\s+".toRegex())
            val weight = if (isOriginal) 1.0 else 0.5 // synonyms count half

            if (titleLower.contains(term))            score += 15.0 * weight
            if (catLower.contains(term))              score += 5.0 * weight
            if (tagsLower.any { it.contains(term) })  score += 10.0 * weight

            // Content hits (capped to avoid long docs dominating)
            val hits = countOccurrences(contentLower, term).coerceAtMost(10)
            score += hits * 2.0 * weight
        }

        if (doc.priority == "high") score += 5.0
        return score
    }

    private fun countOccurrences(text: String, query: String): Int {
        var count = 0
        var index = 0
        while (text.indexOf(query, index).also { index = it } != -1) {
            count++
            index += query.length
        }
        return count
    }

    // -- Excerpt extraction -----------------------------------------------
    // Finds the densest region of matching terms and extracts a window.
    private fun extractBestExcerpt(
        content: String,
        terms: List<String>,
        maxLength: Int = 500,
    ): String {
        val lower = content.lowercase()

        // Collect all hit positions
        data class Hit(val pos: Int, val term: String)
        val hits = mutableListOf<Hit>()
        for (t in terms) {
            var i = 0
            while (true) {
                val found = lower.indexOf(t, i)
                if (found == -1) break
                hits.add(Hit(found, t))
                i = found + t.length
            }
        }

        if (hits.isEmpty()) {
            // No term found at all - return the opening paragraph
            val end = content.indexOf("\n\n").let { if (it in 1 until maxLength) it else maxLength.coerceAtMost(content.length) }
            return content.substring(0, end).trim() + if (end < content.length) "..." else ""
        }

        hits.sortBy { it.pos }

        // Sliding window: find the window of [maxLength] chars with the most hits
        var bestStart = hits[0].pos
        var bestCount = 0
        var left = 0
        for (right in hits.indices) {
            while (hits[right].pos - hits[left].pos > maxLength) left++
            val count = right - left + 1
            if (count > bestCount) {
                bestCount = count
                bestStart = hits[left].pos
            }
        }

        val start = maxOf(0, bestStart - 40)
        val end = minOf(content.length, start + maxLength)
        var excerpt = content.substring(start, end)
        if (start > 0) excerpt = "...$excerpt"
        if (end < content.length) excerpt += "..."
        return excerpt.trim()
    }
}
