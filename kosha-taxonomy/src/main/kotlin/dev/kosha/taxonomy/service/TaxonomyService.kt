package dev.kosha.taxonomy.service

import dev.kosha.taxonomy.dto.CreateTermRequest
import dev.kosha.taxonomy.dto.DuplicateCheckResponse
import dev.kosha.taxonomy.dto.TaxonomyTermResponse
import dev.kosha.taxonomy.dto.TaxonomyTreeNodeResponse
import dev.kosha.taxonomy.dto.UpdateTermRequest
import dev.kosha.taxonomy.entity.TaxonomyEdge
import dev.kosha.taxonomy.entity.TaxonomyTerm
import dev.kosha.taxonomy.repository.TaxonomyEdgeRepository
import dev.kosha.taxonomy.repository.TaxonomyTermRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class TaxonomyService(
    private val termRepo: TaxonomyTermRepository,
    private val edgeRepo: TaxonomyEdgeRepository,
) {

    fun findAll(): List<TaxonomyTermResponse> =
        termRepo.findByStatusIn(listOf("ACTIVE", "CANDIDATE")).map { it.toResponse() }

    fun findById(id: UUID): TaxonomyTermResponse =
        termRepo.findById(id)
            .orElseThrow { NoSuchElementException("Taxonomy term not found: $id") }
            .toResponse()

    fun buildTree(): List<TaxonomyTreeNodeResponse> {
        val terms = termRepo.findByStatusIn(listOf("ACTIVE", "CANDIDATE"))
        val edges = edgeRepo.findAllBroaderEdges()

        val termMap = terms.associateBy { it.id!! }
        val childrenMap = mutableMapOf<UUID, MutableList<UUID>>()
        val hasParent = mutableSetOf<UUID>()

        for (edge in edges) {
            val parentId = edge.parentTerm.id!!
            val childId = edge.childTerm.id!!
            childrenMap.getOrPut(parentId) { mutableListOf() }.add(childId)
            hasParent.add(childId)
        }

        // Root nodes = terms with no parent
        val rootIds = terms.map { it.id!! }.filter { it !in hasParent }

        fun buildNode(termId: UUID): TaxonomyTreeNodeResponse? {
            val term = termMap[termId] ?: return null
            val children = childrenMap[termId]?.mapNotNull { buildNode(it) } ?: emptyList()
            return TaxonomyTreeNodeResponse(term = term.toResponse(), children = children)
        }

        return rootIds.mapNotNull { buildNode(it) }
    }

    fun findCandidates(): List<TaxonomyTermResponse> =
        termRepo.findCandidates().map { it.toResponse() }

    @Transactional
    fun create(request: CreateTermRequest): TaxonomyTermResponse {
        val term = TaxonomyTerm(
            label = request.label,
            normalizedLabel = request.label.lowercase().trim(),
            description = request.description,
            source = request.source,
        )
        val saved = termRepo.save(term)

        request.parentTermId?.let { parentId ->
            val parent = termRepo.findById(parentId)
                .orElseThrow { NoSuchElementException("Parent term not found: $parentId") }
            edgeRepo.save(TaxonomyEdge(parentTerm = parent, childTerm = saved))
        }

        return saved.toResponse()
    }

    @Transactional
    fun updateStatus(id: UUID, newStatus: String): TaxonomyTermResponse {
        val term = termRepo.findById(id)
            .orElseThrow { NoSuchElementException("Taxonomy term not found: $id") }
        term.status = newStatus
        return termRepo.save(term).toResponse()
    }

    fun findDocumentsByTerm(termId: UUID, page: Int, size: Int): List<Map<String, Any?>> {
        // Return documents classified under this term
        // Uses a raw query since we don't have cross-module entity access
        return emptyList() // TODO: implement with JdbcTemplate when cross-module query is needed
    }

    @Transactional
    fun update(id: UUID, request: UpdateTermRequest): TaxonomyTermResponse {
        val term = termRepo.findById(id)
            .orElseThrow { NoSuchElementException("Taxonomy term not found: $id") }

        request.label?.let { newLabel ->
            // Check for duplicate on label change
            val normalized = newLabel.lowercase().trim()
            val existing = termRepo.findByNormalizedLabel(normalized)
            if (existing != null && existing.id != id) {
                throw IllegalStateException("A term with label '$newLabel' already exists (${existing.status})")
            }
            term.label = newLabel
            term.normalizedLabel = normalized
        }
        request.description?.let { term.description = it }
        request.status?.let { term.status = it }

        return termRepo.save(term).toResponse()
    }

    fun checkDuplicate(label: String): DuplicateCheckResponse {
        val normalized = label.lowercase().trim()
        val existing = termRepo.findByNormalizedLabel(normalized)
        return if (existing != null) {
            DuplicateCheckResponse(
                isDuplicate = true,
                existingTermId = existing.id,
                existingLabel = existing.label,
                existingStatus = existing.status,
            )
        } else {
            DuplicateCheckResponse(isDuplicate = false)
        }
    }

    @Transactional
    fun deleteTerm(id: UUID) {
        val term = termRepo.findById(id)
            .orElseThrow { NoSuchElementException("Taxonomy term not found: $id") }
        // Delete edges first
        edgeRepo.findByParentTermId(id).forEach { edgeRepo.delete(it) }
        edgeRepo.findByChildTermId(id).forEach { edgeRepo.delete(it) }
        termRepo.delete(term)
    }

    private fun TaxonomyTerm.toResponse() = TaxonomyTermResponse(
        id = id!!,
        label = label,
        normalizedLabel = normalizedLabel,
        description = description,
        source = source,
        sourceRef = sourceRef,
        status = status,
        mergedIntoId = mergedInto?.id,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
