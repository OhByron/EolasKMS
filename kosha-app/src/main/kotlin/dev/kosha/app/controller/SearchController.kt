package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.PageMeta
import dev.kosha.search.OpenSearchService
import dev.kosha.search.SearchQuery
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
) {

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun search(@RequestBody request: SearchRequestDto): ApiResponse<List<SearchResultDto>> {
        val result = searchService.search(
            SearchQuery(
                query = request.query,
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
