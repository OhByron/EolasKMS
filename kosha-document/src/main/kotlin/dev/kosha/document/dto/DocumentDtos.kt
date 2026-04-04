package dev.kosha.document.dto

import java.time.OffsetDateTime
import java.util.UUID

// --- Document ---

data class CreateDocumentRequest(
    val title: String,
    val description: String? = null,
    val departmentId: UUID,
    val categoryId: UUID? = null,
    val storageMode: String = "VAULT",
    val workflowType: String = "NONE",
)

data class UpdateDocumentRequest(
    val title: String? = null,
    val description: String? = null,
    val categoryId: UUID? = null,
    val status: String? = null,
    val workflowType: String? = null,
)

data class DocumentResponse(
    val id: UUID,
    val docNumber: String?,
    val title: String,
    val description: String?,
    val departmentId: UUID,
    val departmentName: String,
    val categoryId: UUID?,
    val categoryName: String?,
    val status: String,
    val storageMode: String,
    val workflowType: String,
    val checkedOut: Boolean,
    val lockedBy: UUID?,
    val currentVersion: VersionSummary?,
    val createdBy: UUID,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class DocumentListResponse(
    val id: UUID,
    val docNumber: String?,
    val title: String,
    val departmentName: String,
    val status: String,
    val checkedOut: Boolean,
    val currentVersion: String?,
    val createdAt: OffsetDateTime,
)

// --- Version ---

data class CreateVersionRequest(
    val fileName: String,
    val fileSizeBytes: Long? = null,
    val storageKey: String? = null,
    val changeSummary: String? = null,
)

data class VersionSummary(
    val id: UUID,
    val versionNumber: String,
    val fileName: String,
    val status: String,
    val createdAt: OffsetDateTime,
)

data class VersionDetailResponse(
    val id: UUID,
    val documentId: UUID,
    val versionNumber: String,
    val fileName: String,
    val fileSizeBytes: Long?,
    val contentHash: String?,
    val storageKey: String?,
    val changeSummary: String?,
    val status: String,
    val createdBy: UUID,
    val publishAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val metadata: MetadataResponse?,
)

data class MetadataResponse(
    val summary: String?,
    val aiConfidence: Double?,
    val humanReviewed: Boolean,
)

// --- Category ---

data class CreateCategoryRequest(
    val name: String,
    val description: String? = null,
    val departmentId: UUID? = null,
)

data class CategoryResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val departmentId: UUID?,
    val status: String,
)
