package dev.kosha.taxonomy.dto

import java.time.OffsetDateTime
import java.util.UUID

data class CreateTermRequest(
    val label: String,
    val description: String? = null,
    val source: String = "MANUAL",
    val parentTermId: UUID? = null,
)

data class UpdateTermRequest(
    val label: String? = null,
    val description: String? = null,
    val status: String? = null,
)

data class DuplicateCheckResponse(
    val isDuplicate: Boolean,
    val existingTermId: UUID? = null,
    val existingLabel: String? = null,
    val existingStatus: String? = null,
)

data class TaxonomyTermResponse(
    val id: UUID,
    val label: String,
    val normalizedLabel: String,
    val description: String?,
    val source: String,
    val sourceRef: String?,
    val status: String,
    val mergedIntoId: UUID?,
    val aliases: List<String> = emptyList(),
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class TaxonomyTreeNodeResponse(
    val term: TaxonomyTermResponse,
    val children: List<TaxonomyTreeNodeResponse>,
)

data class TermAliasResponse(
    val id: UUID,
    val termId: UUID,
    val aliasLabel: String,
    val source: String,
    val createdAt: OffsetDateTime,
)

data class CreateAliasRequest(
    val aliasLabel: String,
    val source: String = "MANUAL", // AI_SUGGESTED | MANUAL — defaults to MANUAL when an admin types one in
)
