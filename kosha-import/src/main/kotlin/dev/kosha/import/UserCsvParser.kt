package dev.kosha.import

import java.nio.file.Files
import java.nio.file.Path

/**
 * CSV schema for the user pre-provision flow (roadmap 4.2.1).
 *
 * This is the companion to [CsvParser] for the document flow. Same
 * parsing rules (RFC 4180 minus embedded newlines), different columns.
 *
 * Required columns: email, display_name, department_name, role.
 * Optional: temporary_password (blank = backend generates a secure
 * random password and returns it in the response).
 */
data class UserCsvRow(
    val rowNumber: Int,
    val email: String,
    val displayName: String,
    val departmentName: String,
    val role: String,
    val temporaryPassword: String?,
)

object UserCsvParser {
    fun parseFile(path: Path): List<UserCsvRow> {
        require(Files.exists(path)) { "CSV file not found: $path" }
        return parse(Files.readString(path))
    }

    fun parse(content: String): List<UserCsvRow> {
        val lines = content.lines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "CSV must have a header row and at least one data row" }

        val header = splitLine(lines[0]).map { it.trim().lowercase() }
        fun idx(name: String): Int = header.indexOf(name)
        val iEmail = idx("email")
        val iName = idx("display_name")
        val iDept = idx("department_name")
        val iRole = idx("role")
        val iPassword = idx("temporary_password")

        require(iEmail >= 0) { "CSV header missing required column: email" }
        require(iName >= 0) { "CSV header missing required column: display_name" }
        require(iDept >= 0) { "CSV header missing required column: department_name" }
        require(iRole >= 0) { "CSV header missing required column: role" }

        return lines.drop(1).mapIndexed { ordinal, line ->
            val cells = splitLine(line)
            fun cell(i: Int): String = if (i in 0..cells.lastIndex) cells[i].trim() else ""
            UserCsvRow(
                rowNumber = ordinal + 2,
                email = cell(iEmail),
                displayName = cell(iName),
                departmentName = cell(iDept),
                role = cell(iRole),
                temporaryPassword = cell(iPassword).ifBlank { null },
            )
        }
    }

    private fun splitLine(line: String): List<String> {
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
