package dev.kosha.import

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Persistent resume state for the bulk importer.
 *
 * Stored as `.import-state.json` (or whatever `--state` points at)
 * next to the CSV. The state tracks, per row number, whether the
 * row has been imported successfully and if so what the resulting
 * document / version ids are. Re-running the CLI with the same CSV
 * and state file skips completed rows, so:
 *
 * - Interrupted imports resume cleanly
 * - Partial failures can be retried row-by-row by fixing the CSV
 *   and re-running without deleting state
 * - A forced fresh import is "delete the state file then run"
 *
 * ## File format
 *
 * ```json
 * {
 *   "csvSha256": "abc123...",
 *   "startedAt": "2026-04-05T22:00:00Z",
 *   "rows": {
 *     "2": { "ok": true,  "documentId": "...", "versionId": "...", "completedAt": "..." },
 *     "3": { "ok": false, "error": "department not found", "failedAt": "..." }
 *   }
 * }
 * ```
 *
 * Row numbers are 1-indexed and match the CSV's physical line number
 * (header is row 1, first data row is row 2). This keeps error
 * messages aligned with what a user sees in their text editor.
 *
 * The `csvSha256` field is a safety check — if the CSV changes
 * between runs we fail the CLI rather than silently skip rows that
 * may now have different content. Users who really want to replace
 * the state deliberately delete the file.
 */
data class ImportState(
    val csvSha256: String,
    val startedAt: String,
    val rows: MutableMap<Int, RowState> = mutableMapOf(),
) {
    fun markSuccess(rowNumber: Int, documentId: String, versionId: String) {
        rows[rowNumber] = RowState(
            ok = true,
            documentId = documentId,
            versionId = versionId,
            completedAt = Instant.now().toString(),
        )
    }

    fun markFailure(rowNumber: Int, error: String) {
        rows[rowNumber] = RowState(
            ok = false,
            error = error,
            failedAt = Instant.now().toString(),
        )
    }

    fun isDone(rowNumber: Int): Boolean = rows[rowNumber]?.ok == true

    fun counts(): Triple<Int, Int, Int> {
        val done = rows.values.count { it.ok }
        val failed = rows.values.count { !it.ok }
        return Triple(done, failed, rows.size)
    }
}

data class RowState(
    val ok: Boolean,
    val documentId: String? = null,
    val versionId: String? = null,
    val completedAt: String? = null,
    val error: String? = null,
    val failedAt: String? = null,
)

class ImportStateStore(private val path: Path, private val mapper: ObjectMapper) {

    /**
     * Load existing state, or return a fresh state keyed to the given
     * CSV hash. If the existing state's hash doesn't match the CSV,
     * throw — it's almost certainly a mistake. The user can force a
     * fresh state by deleting the file.
     */
    fun loadOrInit(csvSha256: String): ImportState {
        if (!Files.exists(path)) {
            return ImportState(
                csvSha256 = csvSha256,
                startedAt = Instant.now().toString(),
            )
        }
        val existing: ImportState = mapper.readValue(Files.readString(path))
        if (existing.csvSha256 != csvSha256) {
            throw IllegalStateException(
                "State file ${path.fileName} is for a different CSV (hash mismatch). " +
                    "Delete the state file to start fresh, or point --state at a new location.",
            )
        }
        return existing
    }

    /**
     * Write state after every row so a crash loses at most one row.
     * This is O(rows) write amplification but the state file is tiny
     * (a few hundred bytes per row) — not worth optimising until
     * imports of 100k+ documents become common.
     */
    fun save(state: ImportState) {
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state)
        Files.writeString(path, json)
    }
}
