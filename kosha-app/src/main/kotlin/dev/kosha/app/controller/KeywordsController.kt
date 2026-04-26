package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class ExtractedKeywordResponse(
    val keyword: String,
    val frequency: Int,
    val confidence: Double,
    val source: String,
)

data class AddKeywordRequest(val keyword: String?)

@RestController
@RequestMapping("/api/v1/documents")
class KeywordsController(
    private val jdbcTemplate: JdbcTemplate,
) {

    @GetMapping("/{docId}/keywords")
    @PreAuthorize("isAuthenticated()")
    fun getKeywords(@PathVariable docId: UUID): ApiResponse<List<ExtractedKeywordResponse>> {
        val keywords = jdbcTemplate.query(
            """
            SELECT ek.keyword, ek.frequency, ek.confidence, ek.source
            FROM tax.extracted_keyword ek
            JOIN doc.document_version dv ON dv.id = ek.version_id
            WHERE dv.document_id = ?
            ORDER BY ek.confidence DESC, ek.frequency DESC
            LIMIT 30
            """,
            { rs, _ ->
                ExtractedKeywordResponse(
                    keyword = rs.getString("keyword"),
                    frequency = rs.getInt("frequency"),
                    confidence = rs.getDouble("confidence"),
                    source = rs.getString("source"),
                )
            },
            docId,
        )
        return ApiResponse(data = keywords)
    }

    /**
     * Manually add a keyword to a document. Targets the latest version
     * (newest createdAt) so the keyword is anchored to the current state
     * of the document; older versions keep their AI-extracted set intact.
     *
     * Frequency is computed from the version's extracted_text via a
     * case-insensitive substring count, so a manually-added "Nepal" on
     * a document that mentions Nepal six times reports frequency=6 just
     * like an AI-extracted keyword would. Falls back to 1 if there's no
     * extracted text or the term doesn't appear (the user may know the
     * doc is about Nepal even when the literal token is absent).
     *
     * Idempotent: re-adding the same keyword (case-insensitive) updates
     * the existing row to MANUAL with confidence=1.0 rather than
     * inserting a duplicate.
     */
    @PostMapping("/{docId}/keywords")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN','EDITOR','CONTRIBUTOR')")
    fun addKeyword(
        @PathVariable docId: UUID,
        @RequestBody body: AddKeywordRequest,
    ): ApiResponse<ExtractedKeywordResponse> {
        val keyword = body.keyword?.trim().orEmpty()
        if (keyword.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword is required")
        }
        if (keyword.length > 500) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword too long (max 500)")
        }

        val latestVersion = jdbcTemplate.queryForList(
            """
            SELECT id FROM doc.document_version
            WHERE document_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """,
            docId,
        ).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document has no versions")

        val versionId = latestVersion["id"] as UUID

        val extractedText = jdbcTemplate.queryForList(
            "SELECT extracted_text FROM doc.version_metadata WHERE version_id = ?",
            versionId,
        ).firstOrNull()?.get("extracted_text") as? String

        val frequency = countOccurrences(extractedText, keyword).coerceAtLeast(1)

        // ON CONFLICT keeps the manual-add idempotent and lets the user
        // "promote" an existing AI-extracted keyword to MANUAL by adding
        // it again — confidence becomes 1.0 and source flips, so the
        // next AI re-run won't touch it.
        jdbcTemplate.update(
            """
            INSERT INTO tax.extracted_keyword (version_id, keyword, frequency, confidence, source)
            VALUES (?, ?, ?, 1.0, 'MANUAL')
            ON CONFLICT (version_id, lower(keyword)) DO UPDATE
                SET frequency  = EXCLUDED.frequency,
                    confidence = 1.0,
                    source     = 'MANUAL',
                    updated_at = now()
            """,
            versionId, keyword, frequency,
        )

        return ApiResponse(
            data = ExtractedKeywordResponse(
                keyword = keyword,
                frequency = frequency,
                confidence = 1.0,
                source = "MANUAL",
            ),
        )
    }

    /**
     * Remove a keyword from every version of the document. Scoping to
     * "the document, all versions" rather than "latest version only"
     * matches user intent — they're saying "this term shouldn't show up
     * on this doc" — and matches the dedup-across-versions behaviour
     * the frontend already uses for display.
     */
    @DeleteMapping("/{docId}/keywords")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN','EDITOR','CONTRIBUTOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteKeyword(
        @PathVariable docId: UUID,
        @RequestParam("keyword") keyword: String,
    ) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword is required")
        }
        jdbcTemplate.update(
            """
            DELETE FROM tax.extracted_keyword
            WHERE version_id IN (SELECT id FROM doc.document_version WHERE document_id = ?)
              AND lower(keyword) = lower(?)
            """,
            docId, trimmed,
        )
    }

    /**
     * Case-insensitive whole-or-substring count of `needle` in `haystack`.
     * Used so manually-added keywords get a real frequency from the
     * document text instead of always reporting 1.
     */
    private fun countOccurrences(haystack: String?, needle: String): Int {
        if (haystack.isNullOrEmpty() || needle.isEmpty()) return 0
        val lowerHay = haystack.lowercase()
        val lowerNeedle = needle.lowercase()
        var count = 0
        var idx = 0
        while (true) {
            val found = lowerHay.indexOf(lowerNeedle, idx)
            if (found < 0) return count
            count++
            idx = found + lowerNeedle.length
        }
    }
}
