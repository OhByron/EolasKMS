package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.PageMeta
import dev.kosha.search.OpenSearchService
import dev.kosha.search.SearchQuery
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Full-text search endpoints powered by OpenSearch.
 *
 * - `POST /api/v1/search` — full search with filters + pagination
 * - `GET /api/v1/search/suggest?q=...` — autocomplete suggestions
 *
 * Both match the frontend contracts defined in `api.ts`.
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val searchService: OpenSearchService,
    private val jdbcTemplate: JdbcTemplate,
) {

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun search(@RequestBody request: SearchRequestDto): ApiResponse<List<SearchResultDto>> {
        val result = searchService.search(
            SearchQuery(
                query = expandQueryWithSynonyms(request.query),
                departmentId = request.filters?.departmentId,
                statuses = request.filters?.status,
                dateFrom = request.filters?.dateFrom,
                dateTo = request.filters?.dateTo,
                page = request.page ?: 0,
                size = request.size ?: 20,
            ),
        )
        return ApiResponse(
            data = result.results.map { hit ->
                SearchResultDto(
                    id = hit.id,
                    title = hit.title,
                    docNumber = hit.docNumber,
                    departmentName = hit.departmentName,
                    status = hit.status,
                    snippet = hit.snippet,
                    relevance = hit.relevance,
                    taxonomyTerms = hit.taxonomyTerms,
                    createdAt = hit.createdAt ?: "",
                )
            },
            meta = PageMeta(
                page = result.page,
                size = result.size,
                total = result.total,
            ),
        )
    }

    @GetMapping("/suggest")
    @PreAuthorize("isAuthenticated()")
    fun suggest(@RequestParam q: String): ApiResponse<List<String>> =
        ApiResponse(data = searchService.suggest(q))

    /**
     * If any whole-word token in the query matches a taxonomy alias or canonical
     * term label, append the canonical label and its other aliases so OpenSearch's
     * multi-match catches documents indexed under any surface form. The expansion
     * is conservative: only exact normalised label matches trigger expansion, no
     * fuzzy/substring matching, to avoid surprise broadening.
     *
     * Example: query "WatSan reports" → "WatSan reports WASH Water Sanitation Hygiene".
     */
    private fun expandQueryWithSynonyms(rawQuery: String): String {
        if (rawQuery.isBlank()) return rawQuery
        val tokens = rawQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return rawQuery

        val expansions = mutableSetOf<String>()
        for (token in tokens) {
            val normalised = token.lowercase()
            // Lookup canonical term + all its aliases when the token matches either
            // the term's normalised label or any alias's normalised label.
            val rows = jdbcTemplate.queryForList(
                """
                SELECT t.label AS canonical, COALESCE(a2.alias_label, '') AS alias_label
                FROM tax.taxonomy_term t
                LEFT JOIN tax.taxonomy_term_alias a ON a.term_id = t.id
                LEFT JOIN tax.taxonomy_term_alias a2 ON a2.term_id = t.id
                WHERE t.status = 'ACTIVE'
                  AND (t.normalized_label = ? OR a.normalized_alias_label = ?)
                """.trimIndent(),
                normalised, normalised,
            )
            for (row in rows) {
                val canonical = (row["canonical"] as? String)?.trim().orEmpty()
                val alias = (row["alias_label"] as? String)?.trim().orEmpty()
                if (canonical.isNotEmpty() && canonical.lowercase() != normalised) {
                    expansions += canonical
                }
                if (alias.isNotEmpty() && alias.lowercase() != normalised) {
                    expansions += alias
                }
            }
        }
        return if (expansions.isEmpty()) rawQuery else "$rawQuery ${expansions.joinToString(" ")}"
    }
}

// ── Request/Response DTOs matching the frontend contract ──

data class SearchRequestDto(
    val query: String,
    val filters: SearchFiltersDto? = null,
    val sort: String? = null,
    val page: Int? = null,
    val size: Int? = null,
)

data class SearchFiltersDto(
    val departmentId: String? = null,
    val status: List<String>? = null,
    val taxonomyTerms: List<String>? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val mimeType: List<String>? = null,
)

data class SearchResultDto(
    val id: String,
    val title: String,
    val docNumber: String?,
    val departmentName: String,
    val status: String,
    val snippet: String?,
    val relevance: Float,
    val taxonomyTerms: List<String>,
    val createdAt: String,
)
