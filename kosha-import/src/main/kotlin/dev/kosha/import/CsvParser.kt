package dev.kosha.import

import java.nio.file.Files
import java.nio.file.Path

/**
 * Client-side CSV parser. The backend's `ImportValidationController`
 * has its own parser — the two are intentionally symmetric so a row
 * the CLI sends is the same shape the backend resolves. If you change
 * one, change the other.
 *
 * Why duplicate instead of sharing? The CLI runs independently of the
 * backend and shouldn't pull in the full `kosha-app` classpath just
 * for a handful of fields.
 */
data class CsvRow(
    val rowNumber: Int,
    val filePath: String,
    val title: String,
    val description: String,
    val departmentName: String,
    val categoryName: String,
    val ownerEmail: String,
    val tags: List<String>,
    val requiresLegalReview: Boolean,
    val legalReviewerEmail: String?,
)

object CsvParser {
    fun parseFile(path: Path): List<CsvRow> {
        require(Files.exists(path)) { "CSV file not found: $path" }
        val content = Files.readString(path)
        return parse(content)
    }

    fun parse(content: String): List<CsvRow> {
        val lines = content.lines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "CSV must have a header row and at least one data row" }

        val header = splitLine(lines[0]).map { it.trim().lowercase() }

        fun idx(name: String): Int = header.indexOf(name)
        val iFilePath = idx("file_path")
        val iTitle = idx("title")
        val iDescription = idx("description")
        val iDept = idx("department_name")
        val iCategory = idx("category_name")
        val iOwner = idx("owner_email")
        val iTags = idx("tags")
        val iRequiresLegal = idx("requires_legal_review")
        val iLegalReviewer = idx("legal_reviewer_email")

        require(iFilePath >= 0) { "CSV header missing required column: file_path" }
        require(iTitle >= 0) { "CSV header missing required column: title" }
        require(iDept >= 0) { "CSV header missing required column: department_name" }
        require(iOwner >= 0) { "CSV header missing required column: owner_email" }

        return lines.drop(1).mapIndexed { ordinal, line ->
            val cells = splitLine(line)
            fun cell(i: Int): String = if (i in 0..cells.lastIndex) cells[i].trim() else ""
            CsvRow(
                rowNumber = ordinal + 2,
                filePath = cell(iFilePath),
                title = cell(iTitle),
                description = cell(iDescription),
                departmentName = cell(iDept),
                categoryName = cell(iCategory),
                ownerEmail = cell(iOwner),
                tags = cell(iTags).split(";").map { it.trim() }.filter { it.isNotBlank() },
                requiresLegalReview = cell(iRequiresLegal).equals("true", ignoreCase = true),
                legalReviewerEmail = cell(iLegalReviewer).ifBlank { null },
            )
        }
    }

    /**
     * RFC 4180 sans embedded newlines — see ImportValidationController
     * parser for the matching documentation. Keep both parsers in sync.
     */
    private fun splitLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i += 2
                    continue
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }
}
