package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.document.repository.DocumentCategoryRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Dry-run validation for the bulk import CLI.
 *
 * The CLI (`kosha-import`) calls `POST /api/v1/admin/import/validate`
 * with the raw CSV body before executing a real import. The backend
 * parses every row, resolves every reference (department, category,
 * owner, legal reviewer, tags), and returns a per-row verdict. No
 * database writes happen during dry-run — the CLI uses the result
 * to decide whether to proceed.
 *
 * ## Why validate server-side
 *
 * The CLI could technically do the resolution by making GET calls
 * for each reference, but that's O(rows × reference types) round
 * trips. Sending the CSV once and letting the backend resolve it
 * in a single transaction is an order of magnitude faster for
 * realistic imports (10k+ rows).
 *
 * ## Request shape
 *
 * ```json
 * { "csvContent": "file_path,title,...\n./policies/...,...,..." }
 * ```
 *
 * Text/csv would be more idiomatic but wrapping in JSON keeps the CLI
 * code simpler (reuses the same JSON serialiser) and lets us return
 * structured per-row errors with column positions in the same body.
 *
 * ## Response shape
 *
 * ```json
 * {
 *   "data": {
 *     "totalRows": 3,
 *     "validRows": 2,
 *     "invalidRows": 1,
 *     "rows": [
 *       { "row": 1, "ok": true, "filePath": "./a.pdf" },
 *       { "row": 2, "ok": false, "filePath": "./b.pdf",
 *         "errors": ["department 'Unknown' not found", "owner 'foo@x' not found"] },
 *       { "row": 3, "ok": true, "filePath": "./c.pdf" }
 *     ]
 *   }
 * }
 * ```
 */
@RestController
@RequestMapping("/api/v1/admin/import")
class ImportValidationController(
    private val departmentRepo: DepartmentRepository,
    private val userProfileRepo: UserProfileRepository,
    private val categoryRepo: DocumentCategoryRepository,
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Per instrument-as-we-go: one counter per dry-run outcome so the
    // Pass 6 dashboards see import health even before anyone runs a
    // real import. `totalRows` as a distribution summary gives us
    // import sizes.
    private val dryRunsRequested = meterRegistry.counter("eolas.import.dry_runs")
    private val dryRunRowsOk = meterRegistry.counter("eolas.import.rows.validated", "outcome", "ok")
    private val dryRunRowsFail = meterRegistry.counter("eolas.import.rows.validated", "outcome", "fail")

    @PostMapping("/validate")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun validate(@RequestBody request: ValidateRequest): ApiResponse<ValidateResponse> {
        dryRunsRequested.increment()
        val autoProvisionOwners = request.autoProvisionOwners == true

        val rows = parseCsv(request.csvContent)
        if (rows.isEmpty()) {
            return ApiResponse(
                data = ValidateResponse(
                    totalRows = 0,
                    validRows = 0,
                    invalidRows = 0,
                    rows = emptyList(),
                    globalErrors = listOf("CSV is empty or has no data rows after header"),
                ),
            )
        }

        // Pre-load lookup tables once. For 10k-row CSVs this is the
        // difference between "done in 3 seconds" and "takes 10 minutes".
        val depts = departmentRepo.findAll().associateBy { it.name.lowercase() }
        val categories = categoryRepo.findAll().associateBy { (it.name).lowercase() }
        val userByEmail = userProfileRepo.findAll().associateBy { it.email.lowercase() }

        // Tags live in kosha-taxonomy which kosha-app depends on
        // transitively. To avoid pulling the taxonomy repo into this
        // controller's constructor (the module graph would reject it),
        // we do a single native query for all tag labels up front.
        val knownTagLabels: Set<String> = jdbcTemplate
            .queryForList("SELECT LOWER(label) FROM tax.taxonomy_term", String::class.java)
            .toSet()

        // Legal reviewers are any active user whose department has
        // handles_legal_review = true. Pre-computed once.
        val legalReviewerEmails: Set<String> = jdbcTemplate
            .queryForList(
                """
                SELECT LOWER(u.email)
                FROM ident.user_profile u
                JOIN ident.department d ON d.id = u.department_id
                WHERE u.status = 'ACTIVE'
                  AND d.handles_legal_review = true
                """,
                String::class.java,
            )
            .toSet()

        // Legal reviewer user-id map keyed by lowercase email, computed
        // once (same query as legalReviewerEmails but keeping ids too).
        val legalReviewerIdByEmail: Map<String, String> = jdbcTemplate
            .query(
                """
                SELECT LOWER(u.email) as email, u.id as id
                FROM ident.user_profile u
                JOIN ident.department d ON d.id = u.department_id
                WHERE u.status = 'ACTIVE'
                  AND d.handles_legal_review = true
                """,
            ) { rs, _ -> rs.getString("email") to rs.getString("id") }
            .toMap()

        val validated = rows.map { row ->
            val errors = mutableListOf<String>()

            if (row.filePath.isBlank()) errors.add("file_path is required")
            if (row.title.isBlank()) errors.add("title is required")

            val dept = if (row.departmentName.isBlank()) {
                errors.add("department_name is required")
                null
            } else {
                depts[row.departmentName.lowercase()].also {
                    if (it == null) errors.add("department '${row.departmentName}' not found")
                }
            }

            val category = if (row.categoryName.isNotBlank()) {
                categories[row.categoryName.lowercase()].also {
                    if (it == null) errors.add("category '${row.categoryName}' not found")
                }
            } else null

            // Owner resolution. Normally a missing owner is a hard error
            // (4.2 fail-loud rule). When the CLI opts into auto-provision
            // (4.2.2), a missing owner becomes a non-error if the row is
            // otherwise valid — the CLI will create the user just in time
            // during the real run. The dry-run still surfaces this as
            // structured info so the admin can review which users would
            // be created.
            var willAutoProvisionOwner = false
            val owner = if (row.ownerEmail.isBlank()) {
                errors.add("owner_email is required")
                null
            } else {
                val found = userByEmail[row.ownerEmail.lowercase()]
                if (found == null) {
                    if (autoProvisionOwners) {
                        willAutoProvisionOwner = true
                    } else {
                        errors.add(
                            "owner '${row.ownerEmail}' not found " +
                                "(use --auto-provision to create missing owners on import)",
                        )
                    }
                }
                found
            }

            // Tags — fail-loud on any unknown label
            val rawTags = row.tags.split(";").map { it.trim() }.filter { it.isNotBlank() }
            for (tag in rawTags) {
                if (tag.lowercase() !in knownTagLabels) {
                    errors.add("tag '$tag' not found")
                }
            }

            // Legal review fields are conditionally required
            val requiresLegal = row.requiresLegalReview.equals("true", ignoreCase = true)
            var legalReviewerId: String? = null
            if (requiresLegal) {
                if (row.legalReviewerEmail.isBlank()) {
                    errors.add("legal_reviewer_email is required when requires_legal_review=true")
                } else {
                    legalReviewerId = legalReviewerIdByEmail[row.legalReviewerEmail.lowercase()]
                    if (legalReviewerId == null) {
                        errors.add(
                            "legal reviewer '${row.legalReviewerEmail}' not found or not in a " +
                                "department flagged handles_legal_review",
                        )
                    }
                }
            } else if (row.requiresLegalReview.isNotBlank() &&
                !row.requiresLegalReview.equals("false", ignoreCase = true)
            ) {
                errors.add(
                    "requires_legal_review must be 'true' or 'false' (got '${row.requiresLegalReview}')",
                )
            }

            val ok = errors.isEmpty()
            if (ok) dryRunRowsOk.increment() else dryRunRowsFail.increment()

            // When the row resolves cleanly we return the ids in the
            // response. This lets the CLI skip a second round-trip for
            // lookups — it receives everything it needs to execute
            // the real import from a single dry-run call.
            RowValidation(
                row = row.rowNumber,
                filePath = row.filePath,
                ok = ok,
                errors = errors.toList(),
                resolved = if (ok) ResolvedIds(
                    departmentId = dept?.id?.toString() ?: "",
                    categoryId = category?.id?.toString(),
                    // When auto-provisioning, ownerId is left blank in
                    // the resolved block — the CLI fills it in after
                    // creating the user.
                    ownerId = owner?.id?.toString() ?: "",
                    legalReviewerId = legalReviewerId,
                ) else null,
                autoProvisionOwner = if (willAutoProvisionOwner) {
                    AutoProvisionOwnerHint(
                        email = row.ownerEmail,
                        displayName = row.ownerEmail.substringBefore('@'),
                        departmentId = dept?.id?.toString() ?: "",
                    )
                } else null,
            )
        }

        val validCount = validated.count { it.ok }
        val invalidCount = validated.size - validCount

        log.info(
            "Import dry-run: {} rows, {} valid, {} invalid",
            validated.size, validCount, invalidCount,
        )

        return ApiResponse(
            data = ValidateResponse(
                totalRows = validated.size,
                validRows = validCount,
                invalidRows = invalidCount,
                rows = validated,
                globalErrors = emptyList(),
            ),
        )
    }

    /**
     * Minimal CSV parser. Not RFC 4180 complete — no embedded newlines
     * inside quoted fields, which is a deliberate v1 restriction that
     * keeps the parser ~20 lines. Real-world migration CSVs almost
     * never have embedded newlines in metadata fields; if it becomes
     * an issue we can swap in a real CSV library in a follow-up.
     *
     * The parser handles:
     * - Header row identification
     * - Quoted fields with embedded commas
     * - Escaped quotes ("") inside quoted fields
     * - Blank lines are skipped
     * - Leading/trailing whitespace is trimmed outside quotes
     */
    internal fun parseCsv(content: String): List<ParsedRow> {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList() // header only, no data

        val header = splitCsvLine(lines[0]).map { it.trim().lowercase() }

        // Column index map so the parser doesn't hard-code order
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

        return lines.drop(1).mapIndexedNotNull { ordinal, line ->
            val cells = splitCsvLine(line)
            fun cell(i: Int): String = if (i in 0..cells.lastIndex) cells[i].trim() else ""
            ParsedRow(
                rowNumber = ordinal + 2, // header = row 1, first data row = row 2
                filePath = cell(iFilePath),
                title = cell(iTitle),
                description = cell(iDescription),
                departmentName = cell(iDept),
                categoryName = cell(iCategory),
                ownerEmail = cell(iOwner),
                tags = cell(iTags),
                requiresLegalReview = cell(iRequiresLegal),
                legalReviewerEmail = cell(iLegalReviewer),
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

data class ValidateRequest(
    val csvContent: String,
    /**
     * When true, rows with an unresolvable `owner_email` that are
     * otherwise valid are treated as OK and flagged for auto-provision
     * (see [AutoProvisionOwnerHint]). The CLI must then create each
     * flagged user before creating the document. Defaults to false to
     * preserve the fail-loud rule from roadmap 4.2.
     */
    val autoProvisionOwners: Boolean? = false,
)

data class ValidateResponse(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val rows: List<RowValidation>,
    val globalErrors: List<String>,
)

data class RowValidation(
    val row: Int,
    val filePath: String,
    val ok: Boolean,
    val errors: List<String>,
    val resolved: ResolvedIds? = null,
    /**
     * Non-null when `autoProvisionOwners=true` was passed AND the row
     * has a valid department but an unknown owner_email. The CLI is
     * expected to call `POST /users/provision` with these fields
     * before calling `POST /documents` so the doc has a real owner id.
     */
    val autoProvisionOwner: AutoProvisionOwnerHint? = null,
)

data class AutoProvisionOwnerHint(
    val email: String,
    val displayName: String,
    val departmentId: String,
)

/**
 * Ids the CLI needs to execute a real import for a row. Populated by
 * the dry-run endpoint when every reference resolves successfully.
 * Null when any error is present.
 */
data class ResolvedIds(
    val departmentId: String,
    val categoryId: String?,
    val ownerId: String,
    val legalReviewerId: String?,
)

data class ParsedRow(
    val rowNumber: Int,
    val filePath: String,
    val title: String,
    val description: String,
    val departmentName: String,
    val categoryName: String,
    val ownerEmail: String,
    val tags: String,
    val requiresLegalReview: String,
    val legalReviewerEmail: String,
)
