package dev.kosha.import

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Top-level orchestrator for the bulk importer. Pulls the three
 * concerns (CSV parsing, state tracking, API calls) together and
 * executes the import in the order dictated by `docs/bulk-import.md`:
 *
 *   1. Hash the CSV, load or initialise the resume state
 *   2. Call the backend dry-run endpoint to validate and resolve ids
 *   3. Abort with exit 2 if validation produced errors
 *   4. If `--dry-run` was passed, print the verdict and exit 0
 *   5. Otherwise, for each row with a successful dry-run verdict
 *      that isn't already marked done in the state file:
 *         a. Create document
 *         b. Create version row
 *         c. Upload file bytes
 *         d. Write success to state
 *      On any row failure, mark the row failed and continue with
 *      the next row. Failed rows can be retried by re-running.
 *   6. Print a summary of successes, failures, and skips
 *
 * Exit code is derived from the final counts: 0 if everything
 * succeeded, 1 if anything failed.
 */
@Component
class BulkImporter(
    private val api: KoshaApiClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(opts: CliOptions): Int {
        api.baseUrl = opts.apiUrl
        api.token = opts.token

        val csvPath = Paths.get(opts.csvPath).toAbsolutePath()
        val rootDir = Paths.get(opts.rootDir).toAbsolutePath()
        val statePath = Paths.get(opts.statePath).toAbsolutePath()

        if (!Files.exists(csvPath)) {
            log.error("CSV not found: {}", csvPath)
            return 3
        }
        if (!Files.isDirectory(rootDir)) {
            log.error("Root directory not found: {}", rootDir)
            return 3
        }

        val csvContent = Files.readString(csvPath)
        val csvSha = sha256(csvContent)
        val rows = try {
            CsvParser.parse(csvContent)
        } catch (ex: Exception) {
            log.error("Failed to parse CSV: {}", ex.message)
            return 3
        }
        log.info("Parsed {} rows from {}", rows.size, csvPath.fileName)

        // Dry-run validation — single round-trip, resolves ids inline.
        // Passing autoProvisionOwners asks the backend to tolerate
        // unknown owner_email on otherwise-valid rows and return a
        // provisioning hint for each.
        val dryRun = try {
            api.validateCsv(csvContent, autoProvisionOwners = opts.autoProvisionOwners)
        } catch (ex: Exception) {
            log.error("Dry-run validation failed to reach the API: {}", ex.message)
            return 3
        }

        if (dryRun.globalErrors.isNotEmpty()) {
            log.error("CSV has global errors:")
            dryRun.globalErrors.forEach { log.error("  - {}", it) }
            return 2
        }

        if (dryRun.invalidRows > 0) {
            log.error(
                "Dry-run reports {}/{} rows with errors. Nothing has been written.",
                dryRun.invalidRows, dryRun.totalRows,
            )
            dryRun.rows.filter { !it.ok }.take(20).forEach { r ->
                log.error("  row {} ({}):", r.row, r.filePath)
                r.errors.forEach { log.error("    - {}", it) }
            }
            if (dryRun.rows.count { !it.ok } > 20) {
                log.error("  ... ({} more failed rows)", dryRun.rows.count { !it.ok } - 20)
            }
            return 2
        }

        log.info("Dry-run passed: {}/{} rows ready to import", dryRun.validRows, dryRun.totalRows)

        if (opts.dryRun) {
            log.info("--dry-run specified, exiting without writing anything")
            return 0
        }

        // Real run — load or init state.
        val stateStore = ImportStateStore(statePath, mapper)
        val state = try {
            stateStore.loadOrInit(csvSha)
        } catch (ex: IllegalStateException) {
            log.error(ex.message)
            return 3
        }

        val (alreadyDoneAtStart, _, _) = state.counts()
        if (alreadyDoneAtStart > 0) {
            log.info("Resuming — {} rows already completed from previous run", alreadyDoneAtStart)
        }

        // Build a row-number → resolved map for fast lookup during
        // execution. Dry-run rows are 1-indexed against physical line
        // numbers, same as CsvParser output.
        val resolvedByRow: Map<Int, ResolvedIds> = dryRun.rows
            .filter { it.ok && it.resolved != null }
            .associate { it.row to it.resolved!! }

        // Rows that need just-in-time user provisioning before the
        // document is created (roadmap 4.2.2). Indexed by row number so
        // the execution loop can consult it on the fly. Cache created
        // user ids by email so we only provision each unknown owner
        // once, even if multiple rows reference the same address.
        val autoProvisionHintsByRow: Map<Int, AutoProvisionOwnerHint> = dryRun.rows
            .filter { it.ok && it.autoProvisionOwner != null }
            .associate { it.row to it.autoProvisionOwner!! }
        val provisionedOwnerCache = mutableMapOf<String, String>() // email (lc) → user id

        if (opts.autoProvisionOwners && autoProvisionHintsByRow.isNotEmpty()) {
            log.info(
                "Auto-provisioning enabled: {} row(s) will create their owner on import",
                autoProvisionHintsByRow.size,
            )
        }

        var succeeded = 0
        var failed = 0
        var skipped = 0

        for (row in rows) {
            if (state.isDone(row.rowNumber)) {
                skipped++
                continue
            }

            val resolved = resolvedByRow[row.rowNumber]
            if (resolved == null) {
                // Should not happen — we already verified dry-run was clean
                val msg = "row ${row.rowNumber} has no resolved ids (internal error)"
                state.markFailure(row.rowNumber, msg)
                stateStore.save(state)
                failed++
                continue
            }

            val filePath = rootDir.resolve(row.filePath).normalize()
            if (!Files.exists(filePath)) {
                val msg = "file not found: ${row.filePath}"
                log.error("Row {}: {}", row.rowNumber, msg)
                state.markFailure(row.rowNumber, msg)
                stateStore.save(state)
                failed++
                continue
            }

            try {
                // If this row needs a just-in-time owner provision,
                // resolve (or create) the user now. Cache by email so a
                // 10k-row CSV with 200 distinct new owners only makes
                // 200 provision calls, not 10k.
                val ownerIdForRow: String = if (resolved.ownerId.isNotBlank()) {
                    resolved.ownerId
                } else {
                    val hint = autoProvisionHintsByRow[row.rowNumber]
                        ?: throw IllegalStateException(
                            "row ${row.rowNumber} has no resolved owner and no auto-provision hint",
                        )
                    val emailKey = hint.email.lowercase()
                    provisionedOwnerCache[emailKey] ?: run {
                        log.info("Auto-provisioning owner {} for row {}", hint.email, row.rowNumber)
                        val result = api.provisionUser(
                            ProvisionUserPayload(
                                email = hint.email,
                                displayName = hint.displayName,
                                departmentId = hint.departmentId,
                                role = "CONTRIBUTOR",
                                temporaryPassword = null, // backend generates
                            ),
                        )
                        log.info(
                            "  → created user {} ({}) with temporary password: {}",
                            result.user.id, hint.email, result.temporaryPassword,
                        )
                        provisionedOwnerCache[emailKey] = result.user.id
                        result.user.id
                    }
                }

                val doc = api.createDocument(
                    CreateDocumentPayload(
                        title = row.title,
                        description = row.description.ifBlank { null },
                        departmentId = resolved.departmentId,
                        categoryId = resolved.categoryId,
                        ownerId = ownerIdForRow,
                        requiresLegalReview = row.requiresLegalReview,
                        legalReviewerId = resolved.legalReviewerId,
                    ),
                )
                val fileSize = Files.size(filePath)
                val version = api.createVersion(doc.id, filePath.fileName.toString(), fileSize)
                val contentType = guessContentType(filePath)
                api.uploadBytes(doc.id, version.id, filePath, contentType)

                state.markSuccess(row.rowNumber, doc.id, version.id)
                stateStore.save(state)
                succeeded++
                log.info(
                    "Row {} imported: {} → doc {} version {}",
                    row.rowNumber, row.filePath, doc.id, version.id,
                )
            } catch (ex: Exception) {
                val msg = ex.message ?: ex.javaClass.simpleName
                log.error("Row {} failed: {}", row.rowNumber, msg)
                state.markFailure(row.rowNumber, msg)
                stateStore.save(state)
                failed++
            }
        }

        log.info(
            "Import complete. Succeeded: {}  Failed: {}  Skipped (already done): {}",
            succeeded, failed, skipped,
        )

        return if (failed == 0) 0 else 1
    }

    /**
     * Best-effort MIME detection. Tries JDK's URLConnection first
     * (covers common types via its built-in mime.types), falls back
     * to extension-based guessing, and defaults to
     * `application/octet-stream` for unknown types. The backend will
     * persist whatever we send as the version's content_type.
     */
    private fun guessContentType(path: Path): String {
        return try {
            URLConnection.guessContentTypeFromName(path.fileName.toString())
                ?: Files.probeContentType(path)
                ?: "application/octet-stream"
        } catch (_: Exception) {
            "application/octet-stream"
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
