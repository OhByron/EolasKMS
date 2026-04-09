package dev.kosha.taxonomy.service

import dev.kosha.taxonomy.entity.TaxonomyEdge
import dev.kosha.taxonomy.entity.TaxonomyTerm
import dev.kosha.taxonomy.repository.TaxonomyEdgeRepository
import dev.kosha.taxonomy.repository.TaxonomyTermRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses taxonomy data from CSV, JSON, or XML and imports it into the
 * taxonomy graph. Supports both flat and hierarchical structures.
 *
 * CSV format:
 *   label,description,parent_label
 *   Finance,Financial operations,
 *   Accounts Payable,Vendor payment management,Finance
 *
 * JSON format (flat array):
 *   [{ "label": "Finance", "description": "...", "parent": "..." }]
 *
 * JSON format (nested):
 *   [{ "label": "Finance", "children": [{ "label": "AP" }] }]
 *
 * XML format:
 *   <taxonomy>
 *     <term label="Finance" description="...">
 *       <term label="Accounts Payable" />
 *     </term>
 *   </taxonomy>
 */
@Service
class TaxonomyImportService(
    private val termRepo: TaxonomyTermRepository,
    private val edgeRepo: TaxonomyEdgeRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Parse the file content into a flat list of import rows.
     * The format is detected from the provided format hint.
     */
    fun parse(content: String, format: String): List<ImportRow> {
        return when (format.lowercase()) {
            "csv" -> parseCsv(content)
            "json" -> parseJson(content)
            "xml" -> parseXml(content)
            else -> throw IllegalArgumentException("Unsupported format: $format. Use csv, json, or xml.")
        }
    }

    /**
     * Preview: parse and validate without writing. Returns per-row
     * verdicts with duplicate detection against existing terms.
     */
    fun preview(content: String, format: String): ImportPreviewResponse {
        val rows = parse(content, format)
        if (rows.isEmpty()) {
            return ImportPreviewResponse(
                totalRows = 0, newTerms = 0, duplicates = 0, errors = 0,
                rows = emptyList(), globalErrors = listOf("No terms found in the file."),
            )
        }

        val existingLabels = termRepo.findAll()
            .associate { it.normalizedLabel to it.label }

        // Track labels within the import file for internal duplicates
        val seenInFile = mutableMapOf<String, Int>()

        val validated = rows.mapIndexed { idx, row ->
            val errors = mutableListOf<String>()
            val normalized = row.label.lowercase().trim()

            if (row.label.isBlank()) {
                errors.add("Label is required")
            }

            if (normalized.isNotBlank()) {
                // Check against existing DB terms
                val existingLabel = existingLabels[normalized]
                if (existingLabel != null) {
                    errors.add("Term '$existingLabel' already exists in the taxonomy")
                }

                // Check for duplicates within the file
                val firstSeen = seenInFile[normalized]
                if (firstSeen != null) {
                    errors.add("Duplicate of row ${firstSeen + 1} in this file")
                } else {
                    seenInFile[normalized] = idx
                }
            }

            // Validate parent reference (if any) can be resolved
            if (row.parentLabel.isNotBlank()) {
                val parentNormalized = row.parentLabel.lowercase().trim()
                val parentExists = existingLabels.containsKey(parentNormalized) ||
                    rows.any { it.label.lowercase().trim() == parentNormalized && it !== row }
                if (!parentExists) {
                    errors.add("Parent '${row.parentLabel}' not found in taxonomy or this file")
                }
            }

            ImportRowPreview(
                row = idx + 1,
                label = row.label,
                description = row.description,
                parentLabel = row.parentLabel,
                isDuplicate = existingLabels.containsKey(normalized),
                errors = errors,
                ok = errors.isEmpty(),
            )
        }

        return ImportPreviewResponse(
            totalRows = validated.size,
            newTerms = validated.count { it.ok },
            duplicates = validated.count { it.isDuplicate },
            errors = validated.count { !it.ok },
            rows = validated,
            globalErrors = emptyList(),
        )
    }

    /**
     * Import: parse, validate, and persist. Skips duplicates, creates
     * new terms and edges. Returns a summary of what was created.
     */
    @Transactional
    fun importTerms(content: String, format: String, sourceRef: String?): ImportResult {
        val rows = parse(content, format)
        if (rows.isEmpty()) {
            return ImportResult(created = 0, skipped = 0, errors = 0, details = emptyList())
        }

        val existingByNormalized = termRepo.findAll()
            .associateBy { it.normalizedLabel }
            .toMutableMap()

        // Two-pass: create terms first, then edges
        val createdTerms = mutableMapOf<String, TaxonomyTerm>() // normalized -> term
        val details = mutableListOf<ImportResultRow>()
        var created = 0
        var skipped = 0
        var errors = 0

        // Pass 1: create terms
        for ((idx, row) in rows.withIndex()) {
            val normalized = row.label.lowercase().trim()
            if (normalized.isBlank()) {
                details.add(ImportResultRow(idx + 1, row.label, "error", "Label is required"))
                errors++
                continue
            }

            if (existingByNormalized.containsKey(normalized)) {
                details.add(ImportResultRow(idx + 1, row.label, "skipped", "Already exists"))
                skipped++
                continue
            }

            if (createdTerms.containsKey(normalized)) {
                details.add(ImportResultRow(idx + 1, row.label, "skipped", "Duplicate in file"))
                skipped++
                continue
            }

            try {
                val term = termRepo.save(
                    TaxonomyTerm(
                        label = row.label.trim(),
                        normalizedLabel = normalized,
                        description = row.description.takeIf { it.isNotBlank() },
                        source = "SEED",
                        sourceRef = sourceRef,
                        status = "ACTIVE",
                    )
                )
                createdTerms[normalized] = term
                existingByNormalized[normalized] = term
                details.add(ImportResultRow(idx + 1, row.label, "created", null))
                created++
            } catch (ex: Exception) {
                details.add(ImportResultRow(idx + 1, row.label, "error", ex.message))
                errors++
            }
        }

        // Pass 2: create edges for parent-child relationships
        for (row in rows) {
            if (row.parentLabel.isBlank()) continue
            val childNormalized = row.label.lowercase().trim()
            val parentNormalized = row.parentLabel.lowercase().trim()

            val child = existingByNormalized[childNormalized] ?: continue
            val parent = existingByNormalized[parentNormalized]
            if (parent == null) {
                log.warn("Parent '{}' not found for child '{}', skipping edge", row.parentLabel, row.label)
                continue
            }

            // Don't create duplicate edges
            val existingEdge = edgeRepo.findByParentTermIdAndChildTermId(parent.id!!, child.id!!)
            if (existingEdge.isEmpty()) {
                edgeRepo.save(TaxonomyEdge(parentTerm = parent, childTerm = child, edgeType = "BROADER"))
            }
        }

        log.info("Taxonomy import complete: {} created, {} skipped, {} errors", created, skipped, errors)
        return ImportResult(created = created, skipped = skipped, errors = errors, details = details)
    }

    // ── Parsers ──────────────────────────────────────────────

    private fun parseCsv(content: String): List<ImportRow> {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        val header = splitCsvLine(lines[0]).map { it.trim().lowercase() }
        val iLabel = header.indexOf("label")
        val iDesc = header.indexOf("description")
        val iParent = header.indexOf("parent_label").takeIf { it >= 0 }
            ?: header.indexOf("parent")

        if (iLabel < 0) {
            throw IllegalArgumentException("CSV must have a 'label' column header")
        }

        return lines.drop(1).map { line ->
            val cells = splitCsvLine(line)
            fun cell(i: Int) = if (i in 0..cells.lastIndex) cells[i].trim() else ""
            ImportRow(
                label = cell(iLabel),
                description = if (iDesc >= 0) cell(iDesc) else "",
                parentLabel = if (iParent >= 0) cell(iParent) else "",
            )
        }
    }

    private fun parseJson(content: String): List<ImportRow> {
        val trimmed = content.trim()
        return if (trimmed.startsWith("[")) {
            // Array of terms (flat or nested)
            val items: List<Map<String, Any?>> = objectMapper.readValue(trimmed)
            flattenJsonTerms(items, parentLabel = "")
        } else if (trimmed.startsWith("{")) {
            // Single root object with "terms" array
            val root: Map<String, Any?> = objectMapper.readValue(trimmed)
            val terms = root["terms"] as? List<*>
                ?: throw IllegalArgumentException("JSON object must have a 'terms' array")
            @Suppress("UNCHECKED_CAST")
            flattenJsonTerms(terms as List<Map<String, Any?>>, parentLabel = "")
        } else {
            throw IllegalArgumentException("JSON must be an array or object with a 'terms' array")
        }
    }

    private fun flattenJsonTerms(items: List<Map<String, Any?>>, parentLabel: String): List<ImportRow> {
        val result = mutableListOf<ImportRow>()
        for (item in items) {
            val label = item["label"]?.toString() ?: continue
            val desc = item["description"]?.toString() ?: ""
            val parent = item["parent"]?.toString() ?: parentLabel
            result.add(ImportRow(label = label, description = desc, parentLabel = parent))

            // Recurse into children
            @Suppress("UNCHECKED_CAST")
            val children = item["children"] as? List<Map<String, Any?>>
            if (children != null) {
                result.addAll(flattenJsonTerms(children, parentLabel = label))
            }
        }
        return result
    }

    private fun parseXml(content: String): List<ImportRow> {
        val factory = DocumentBuilderFactory.newInstance()
        // Disable external entities for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(content.byteInputStream())
        doc.documentElement.normalize()

        val result = mutableListOf<ImportRow>()
        val root = doc.documentElement

        // Support <taxonomy><term>...</term></taxonomy> or just <terms><term>...</term></terms>
        val termElements = root.getElementsByTagName("term")
        if (termElements.length == 0) {
            throw IllegalArgumentException("XML must contain <term> elements")
        }

        // Walk the tree structure
        fun walkTerms(parent: Element, parentLabel: String) {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                if (node.tagName != "term") continue

                val label = node.getAttribute("label").takeIf { it.isNotBlank() }
                    ?: node.getElementsByTagName("label").item(0)?.textContent?.trim()
                    ?: continue

                val desc = node.getAttribute("description").takeIf { it.isNotBlank() }
                    ?: node.getElementsByTagName("description").item(0)?.textContent?.trim()
                    ?: ""

                result.add(ImportRow(label = label, description = desc, parentLabel = parentLabel))

                // Recurse into nested terms
                walkTerms(node, label)
            }
        }

        walkTerms(root, "")
        return result
    }

    private fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i += 2; continue
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }
}

// ── DTOs ──────────────────────────────────────────────

data class ImportRow(
    val label: String,
    val description: String,
    val parentLabel: String,
)

data class ImportRowPreview(
    val row: Int,
    val label: String,
    val description: String,
    val parentLabel: String,
    val isDuplicate: Boolean,
    val errors: List<String>,
    val ok: Boolean,
)

data class ImportPreviewResponse(
    val totalRows: Int,
    val newTerms: Int,
    val duplicates: Int,
    val errors: Int,
    val rows: List<ImportRowPreview>,
    val globalErrors: List<String>,
)

data class ImportResult(
    val created: Int,
    val skipped: Int,
    val errors: Int,
    val details: List<ImportResultRow>,
)

data class ImportResultRow(
    val row: Int,
    val label: String,
    val status: String, // created, skipped, error
    val message: String?,
)
