package dev.kosha.import

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * User pre-provision importer (roadmap 4.2.1).
 *
 * Mirrors [BulkImporter] for documents but works on the user domain.
 * Orchestration is simpler because there are no file bytes to upload
 * and no categories/tags/legal reviewers to resolve — just email,
 * name, department, role, and an optional temporary password.
 *
 * The point of this tool is to unblock a document import against a
 * foreign set of owners: run this first to create every owner the
 * document CSV will reference, then run the document importer. The
 * fail-loud ownership rule in 4.2 becomes a non-issue because every
 * referenced email now exists.
 *
 * ## Output
 *
 * On a successful real run the state file records the provisioned
 * user id AND the temporary password returned by the backend. The
 * admin can grep the state file for passwords to communicate them
 * to the new users. Storing the password on disk is a trade-off —
 * it's convenient but sensitive; the file is owned by the running
 * user and should be deleted after use.
 */
@Component
class BulkUserImporter(
    private val api: KoshaApiClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(opts: CliOptions): Int {
        api.baseUrl = opts.apiUrl
        api.token = opts.token

        val csvPath = Paths.get(opts.csvPath).toAbsolutePath()
        val statePath = Paths.get(opts.statePath).toAbsolutePath()

        if (!Files.exists(csvPath)) {
            log.error("CSV not found: {}", csvPath)
            return 3
        }

        val csvContent = Files.readString(csvPath)
        val csvSha = sha256(csvContent)
        val rows = try {
            UserCsvParser.parse(csvContent)
        } catch (ex: Exception) {
            log.error("Failed to parse user CSV: {}", ex.message)
            return 3
        }
        log.info("Parsed {} user rows from {}", rows.size, csvPath.fileName)

        val dryRun = try {
            api.validateUserCsv(csvContent)
        } catch (ex: Exception) {
            log.error("User dry-run validation failed: {}", ex.message)
            return 3
        }

        if (dryRun.globalErrors.isNotEmpty()) {
            log.error("CSV has global errors:")
            dryRun.globalErrors.forEach { log.error("  - {}", it) }
            return 2
        }

        if (dryRun.invalidRows > 0) {
            log.error(
                "Dry-run reports {}/{} user rows with errors. Nothing has been written.",
                dryRun.invalidRows, dryRun.totalRows,
            )
            dryRun.rows.filter { !it.ok }.take(20).forEach { r ->
                log.error("  row {} ({}):", r.row, r.email)
                r.errors.forEach { log.error("    - {}", it) }
            }
            if (dryRun.rows.count { !it.ok } > 20) {
                log.error("  ... ({} more failed rows)", dryRun.rows.count { !it.ok } - 20)
            }
            return 2
        }

        log.info("Dry-run passed: {}/{} user rows ready to provision", dryRun.validRows, dryRun.totalRows)

        if (opts.dryRun) {
            log.info("--dry-run specified, exiting without writing anything")
            return 0
        }

        // Real run — reuse the document importer's state store, keyed
        // to a different file by default. Resume semantics are identical.
        val stateStore = ImportStateStore(statePath, mapper)
        val state = try {
            stateStore.loadOrInit(csvSha)
        } catch (ex: IllegalStateException) {
            log.error(ex.message)
            return 3
        }

        val (alreadyDone, _, _) = state.counts()
        if (alreadyDone > 0) {
            log.info("Resuming — {} users already provisioned from previous run", alreadyDone)
        }

        val resolvedByRow: Map<Int, String> = dryRun.rows
            .filter { it.ok && it.resolvedDepartmentId != null }
            .associate { it.row to it.resolvedDepartmentId!! }

        var succeeded = 0
        var failed = 0
        var skipped = 0

        for (row in rows) {
            if (state.isDone(row.rowNumber)) {
                skipped++
                continue
            }
            val deptId = resolvedByRow[row.rowNumber]
            if (deptId == null) {
                val msg = "row ${row.rowNumber} has no resolved department id (internal error)"
                state.markFailure(row.rowNumber, msg)
                stateStore.save(state)
                failed++
                continue
            }

            try {
                val result = api.provisionUser(
                    ProvisionUserPayload(
                        email = row.email,
                        displayName = row.displayName,
                        departmentId = deptId,
                        role = row.role,
                        temporaryPassword = row.temporaryPassword,
                    ),
                )
                state.markSuccess(
                    rowNumber = row.rowNumber,
                    documentId = result.user.id, // reusing ImportState's "documentId" slot for user id
                    versionId = result.temporaryPassword, // reusing "versionId" slot for the temp pw
                )
                stateStore.save(state)
                succeeded++
                log.info(
                    "Row {} provisioned: {} ({}) → user {} temp password in state file",
                    row.rowNumber, row.email, row.role, result.user.id,
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
            "User import complete. Succeeded: {}  Failed: {}  Skipped: {}",
            succeeded, failed, skipped,
        )
        if (succeeded > 0) {
            log.info(
                "Temporary passwords are stored in {} — communicate them securely and delete the file after use.",
                statePath.fileName,
            )
        }

        return if (failed == 0) 0 else 1
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
