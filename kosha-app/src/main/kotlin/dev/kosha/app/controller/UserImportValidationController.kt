package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.keycloak.KeycloakAdminClient
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Dry-run validation for the user bulk import CLI (roadmap 4.2.1).
 *
 * This endpoint is the user-domain equivalent of
 * [ImportValidationController] for documents — same shape, different
 * concerns. It parses a CSV of user rows, resolves every reference
 * (department by name), checks every email against both the local
 * `user_profile` table and Keycloak, and returns a per-row verdict
 * with resolved department ids so the CLI doesn't need a second
 * round-trip for lookups.
 *
 * ## Why a separate endpoint rather than reusing document import
 *
 * User import and document import have different failure modes and
 * different validation rules:
 *
 * - Email uniqueness must be checked against BOTH Kosha and Keycloak
 *   because provisioning fails atomically if either exists.
 * - Role validation is a closed list (CONTRIBUTOR/EDITOR/DEPT_ADMIN/
 *   GLOBAL_ADMIN) with no parallel in the document domain.
 * - The CSV schema is completely different.
 *
 * Conflating the two would mean a CSV with a `mode` column or some
 * other discriminator — more complex and less discoverable than two
 * cleanly separated endpoints.
 *
 * ## CSV schema
 *
 * ```
 * email,display_name,department_name,role,temporary_password
 * alice@example.com,Alice Adams,Finance,EDITOR,
 * bob@example.com,Bob Baker,Marketing,CONTRIBUTOR,Bob-Temp-Pass-1
 * ```
 *
 * `temporary_password` is optional — blank tells the backend to
 * generate a secure random password and return it in the response
 * (same contract as the existing `POST /users/provision` endpoint).
 */
@RestController
@RequestMapping("/api/v1/admin/import/users")
class UserImportValidationController(
    private val departmentRepo: DepartmentRepository,
    private val userProfileRepo: UserProfileRepository,
    private val keycloak: KeycloakAdminClient,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val dryRunsRequested = meterRegistry.counter("eolas.import.users.dry_runs")
    private val rowsOk = meterRegistry.counter("eolas.import.users.rows.validated", "outcome", "ok")
    private val rowsFail = meterRegistry.counter("eolas.import.users.rows.validated", "outcome", "fail")

    @PostMapping("/validate")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun validate(@RequestBody request: UserValidateRequest): ApiResponse<UserValidateResponse> {
        dryRunsRequested.increment()

        val rows = parseCsv(request.csvContent)
        if (rows.isEmpty()) {
            return ApiResponse(
                data = UserValidateResponse(
                    totalRows = 0,
                    validRows = 0,
                    invalidRows = 0,
                    rows = emptyList(),
                    globalErrors = listOf("CSV is empty or has no data rows after header"),
                ),
            )
        }

        // Preload lookups once
        val deptByName = departmentRepo.findAll().associateBy { it.name.lowercase() }
        val existingLocalEmails = userProfileRepo.findAll().map { it.email.lowercase() }.toHashSet()

        // Within-CSV duplicate detection — two rows in the same file
        // can't both create the same email. We detect these as "duplicate
        // in CSV" errors rather than letting the first succeed and the
        // second explode at runtime.
        val emailsSeenInCsv = mutableMapOf<String, Int>() // email → first row number

        val validated = rows.map { row ->
            val errors = mutableListOf<String>()

            if (row.email.isBlank()) errors.add("email is required")
            if (row.displayName.isBlank()) errors.add("display_name is required")
            if (row.departmentName.isBlank()) errors.add("department_name is required")
            if (row.role.isBlank()) errors.add("role is required")

            val emailLower = row.email.lowercase()

            if (emailLower.isNotBlank()) {
                // Within-CSV duplicate
                val firstOccurrence = emailsSeenInCsv[emailLower]
                if (firstOccurrence != null) {
                    errors.add("email '${row.email}' appears earlier in CSV on row $firstOccurrence")
                } else {
                    emailsSeenInCsv[emailLower] = row.rowNumber
                }

                // Existing Kosha profile
                if (emailLower in existingLocalEmails) {
                    errors.add("a Kosha user with email '${row.email}' already exists")
                }

                // Existing Keycloak account. The admin client can be slow
                // so we batch-protect: one call per row is acceptable for
                // a pre-provision batch (this is a dry-run, the user is
                // waiting for the answer anyway), but it's the slowest
                // part of validation. In a later optimisation we could
                // use a single `searchByAttributes` call to fetch every
                // email in one request.
                if (emailLower !in existingLocalEmails) {
                    try {
                        if (keycloak.userExists(row.email)) {
                            errors.add("a Keycloak account with email '${row.email}' already exists")
                        }
                    } catch (ex: Exception) {
                        log.warn("Keycloak lookup failed for '{}': {}", row.email, ex.message)
                        errors.add("could not verify Keycloak for '${row.email}': ${ex.message}")
                    }
                }
            }

            val dept = if (row.departmentName.isNotBlank()) {
                deptByName[row.departmentName.lowercase()].also {
                    if (it == null) errors.add("department '${row.departmentName}' not found")
                }
            } else null

            if (row.role.isNotBlank() && row.role !in VALID_ROLES) {
                errors.add("role '${row.role}' is not one of $VALID_ROLES")
            }

            val ok = errors.isEmpty()
            if (ok) rowsOk.increment() else rowsFail.increment()

            UserRowValidation(
                row = row.rowNumber,
                email = row.email,
                ok = ok,
                errors = errors.toList(),
                resolvedDepartmentId = if (ok) dept?.id?.toString() else null,
            )
        }

        val validCount = validated.count { it.ok }
        log.info(
            "User import dry-run: {} rows, {} valid, {} invalid",
            validated.size, validCount, validated.size - validCount,
        )

        return ApiResponse(
            data = UserValidateResponse(
                totalRows = validated.size,
                validRows = validCount,
                invalidRows = validated.size - validCount,
                rows = validated,
                globalErrors = emptyList(),
            ),
        )
    }

    companion object {
        val VALID_ROLES = setOf("GLOBAL_ADMIN", "DEPT_ADMIN", "EDITOR", "CONTRIBUTOR")
    }

    internal fun parseCsv(content: String): List<ParsedUserRow> {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        val header = splitCsvLine(lines[0]).map { it.trim().lowercase() }

        fun idx(name: String): Int = header.indexOf(name)
        val iEmail = idx("email")
        val iName = idx("display_name")
        val iDept = idx("department_name")
        val iRole = idx("role")
        val iPassword = idx("temporary_password")

        return lines.drop(1).mapIndexed { ordinal, line ->
            val cells = splitCsvLine(line)
            fun cell(i: Int): String = if (i in 0..cells.lastIndex) cells[i].trim() else ""
            ParsedUserRow(
                rowNumber = ordinal + 2,
                email = cell(iEmail),
                displayName = cell(iName),
                departmentName = cell(iDept),
                role = cell(iRole),
                temporaryPassword = cell(iPassword).ifBlank { null },
            )
        }
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

data class UserValidateRequest(val csvContent: String)

data class UserValidateResponse(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val rows: List<UserRowValidation>,
    val globalErrors: List<String>,
)

data class UserRowValidation(
    val row: Int,
    val email: String,
    val ok: Boolean,
    val errors: List<String>,
    val resolvedDepartmentId: String? = null,
)

data class ParsedUserRow(
    val rowNumber: Int,
    val email: String,
    val displayName: String,
    val departmentName: String,
    val role: String,
    val temporaryPassword: String?,
)
