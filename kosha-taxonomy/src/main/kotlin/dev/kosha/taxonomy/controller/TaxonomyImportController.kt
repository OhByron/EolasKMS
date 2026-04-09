package dev.kosha.taxonomy.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.taxonomy.service.ImportPreviewResponse
import dev.kosha.taxonomy.service.ImportResult
import dev.kosha.taxonomy.service.TaxonomyImportService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Taxonomy import endpoints for bulk loading terms from CSV, JSON,
 * or XML files.
 *
 * POST /preview  - dry-run: parse and validate without writing
 * POST /commit   - parse, validate, and persist new terms + edges
 */
@RestController
@RequestMapping("/api/v1/admin/taxonomy/import")
@PreAuthorize("hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')")
class TaxonomyImportController(
    private val importService: TaxonomyImportService,
) {

    @PostMapping("/preview")
    fun preview(@RequestBody request: TaxonomyImportRequest): ApiResponse<ImportPreviewResponse> {
        val result = importService.preview(request.content, request.format)
        return ApiResponse(data = result)
    }

    @PostMapping("/commit")
    fun commit(@RequestBody request: TaxonomyImportRequest): ApiResponse<ImportResult> {
        val result = importService.importTerms(request.content, request.format, request.sourceRef)
        return ApiResponse(data = result)
    }
}

data class TaxonomyImportRequest(
    val content: String,
    val format: String, // csv, json, xml
    val sourceRef: String? = null,
)
