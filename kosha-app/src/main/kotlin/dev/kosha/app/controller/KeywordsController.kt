package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ExtractedKeywordResponse(
    val keyword: String,
    val frequency: Int,
    val confidence: Double,
)

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
            SELECT ek.keyword, ek.frequency, ek.confidence
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
                )
            },
            docId,
        )
        return ApiResponse(data = keywords)
    }
}
