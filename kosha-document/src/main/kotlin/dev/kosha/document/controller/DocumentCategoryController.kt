package dev.kosha.document.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.document.dto.CategoryResponse
import dev.kosha.document.dto.UpdateCategoryRequest
import dev.kosha.document.entity.DocumentCategory
import dev.kosha.document.repository.DocumentCategoryRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Lightweight CRUD for document categories. The upload form reads this to
 * populate its category dropdown and to know which categories should
 * pre-tick the "requires legal review" checkbox via [CategoryResponse.suggestsLegalReview].
 *
 * The editable attribute in this pass is `suggestsLegalReview` — it lets
 * GLOBAL_ADMIN mark certain categories (Policy, Contract, Procedure) as
 * defaults for legal review. Other fields (name, description, department)
 * are editable through the same PATCH endpoint for completeness even though
 * the UI for them doesn't exist yet.
 */
@RestController
@RequestMapping("/api/v1/document-categories")
// TODO: restore @PreAuthorize where applicable once JWT roles are wired up.
class DocumentCategoryController(
    private val categoryRepo: DocumentCategoryRepository,
) {

    @GetMapping
    fun list(): ApiResponse<List<CategoryResponse>> {
        val categories = categoryRepo
            .findByStatus("ACTIVE")
            .sortedBy { it.name }
            .map { it.toResponse() }
        return ApiResponse(data = categories)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): ApiResponse<CategoryResponse> {
        val category = categoryRepo.findById(id)
            .orElseThrow { NoSuchElementException("Category not found: $id") }
        return ApiResponse(data = category.toResponse())
    }

    @PatchMapping("/{id}")
    @Transactional
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateCategoryRequest,
    ): ApiResponse<CategoryResponse> {
        val category = categoryRepo.findById(id)
            .orElseThrow { NoSuchElementException("Category not found: $id") }

        request.name?.let { category.name = it }
        request.description?.let { category.description = it }
        request.status?.let { category.status = it }
        request.suggestsLegalReview?.let { category.suggestsLegalReview = it }
        category.updatedAt = OffsetDateTime.now()

        return ApiResponse(data = categoryRepo.save(category).toResponse())
    }

    private fun DocumentCategory.toResponse() = CategoryResponse(
        id = id!!,
        name = name,
        description = description,
        departmentId = department?.id,
        status = status,
        suggestsLegalReview = suggestsLegalReview,
    )
}
