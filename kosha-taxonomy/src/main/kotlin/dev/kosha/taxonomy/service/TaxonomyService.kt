package dev.kosha.taxonomy.service

import dev.kosha.taxonomy.dto.CreateAliasRequest
import dev.kosha.taxonomy.dto.CreateTermRequest
import dev.kosha.taxonomy.dto.DuplicateCheckResponse
import dev.kosha.taxonomy.dto.TaxonomyTermResponse
import dev.kosha.taxonomy.dto.TaxonomyTreeNodeResponse
import dev.kosha.taxonomy.dto.TermAliasResponse
import dev.kosha.taxonomy.dto.UpdateTermRequest
import dev.kosha.taxonomy.entity.TaxonomyEdge
import dev.kosha.taxonomy.entity.TaxonomyTerm
import dev.kosha.taxonomy.entity.TaxonomyTermAlias
import dev.kosha.taxonomy.repository.TaxonomyEdgeRepository
import dev.kosha.taxonomy.repository.TaxonomyTermAliasRepository
import dev.kosha.taxonomy.repository.TaxonomyTermRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class TaxonomyService(
    private val termRepo: TaxonomyTermRepository,
    private val edgeRepo: TaxonomyEdgeRepository,
    private val aliasRepo: TaxonomyTermAliasRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    // ----- Aliases (synonyms) -----

    fun listAliases(termId: UUID): List<TermAliasResponse> {
        // Validate term exists so callers get a 404 rather than an empty list
        termRepo.findById(termId)
            .orElseThrow { NoSuchElementException("Taxonomy term not found: $termId") }
        return aliasRepo.findByTermId(termId).map { it.toResponse() }
    }

    @Transactional
    fun addAlias(termId: UUID, request: CreateAliasRequest): TermAliasResponse {
        val term = termRepo.findById(termId)
            .orElseThrow { NoSuchElementException("Taxonomy term not found: $termId") }

        val label = request.aliasLabel.trim()
        require(label.isNotEmpty()) { "alias_label is required" }
        val normalized = label.lowercase()

        // Reject if the alias is identical to the canonical label — no value, just noise
        require(normalized != term.normalizedLabel) {
            "Alias matches the term's canonical label"
        }
        // Idempotent: existing identical alias on the same term returns as-is
        aliasRepo.findByTermIdAndNormalizedAliasLabel(termId, normalized)?.let {
            return it.toResponse()
        }

        val source = if (request.source.uppercase() in setOf("AI_SUGGESTED", "MANUAL")) {
            request.source.uppercase()
        } else {
            "MANUAL"
        }
        val saved = aliasRepo.save(
            TaxonomyTermAlias(
                termId = termId,
                aliasLabel = label,
                normalizedAliasLabel = normalized,
                source = source,
            )
        )
        return saved.toResponse()
    }

    @Transactional
    fun deleteAlias(aliasId: UUID) {
        if (!aliasRepo.existsById(aliasId)) {
            throw NoSuchElementException("Alias not found: $aliasId")
        }
        aliasRepo.deleteById(aliasId)
    }

    private fun TaxonomyTermAlias.toResponse() = TermAliasResponse(
        id = this.id!!,
        termId = this.termId,
        aliasLabel = this.aliasLabel,
        source = this.source,
        createdAt = this.createdAt,
    )

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
        // Cross-module raw query: kosha-taxonomy doesn't import doc.* entities,
        // and the document module doesn't depend on taxonomy. Joining via SQL
        // here keeps the module boundary clean.
        //
        // No status filter on the term itself — both ACTIVE and CANDIDATE terms
        // can have document classifications. CANDIDATE terms surface their
        // source document(s) so admins reviewing them have the context to
        // approve/reject without hunting.
        val safeSize = size.coerceIn(1, 100)
        val offset = (page.coerceAtLeast(0).toLong()) * safeSize
        // Aliases are double-quoted so PostgreSQL preserves the camelCase that
        // the SvelteKit DocumentListItem type expects. Unquoted identifiers get
        // folded to lowercase, which yielded `createdat` / `docnumber` and made
        // the frontend render `Invalid Date`.
        return jdbcTemplate.queryForList(
            """
            SELECT d.id           AS "id",
                   d.title        AS "title",
                   d.doc_number   AS "docNumber",
                   d.status       AS "status",
                   d.created_at   AS "createdAt",
                   dept.name      AS "departmentName",
                   dc.confidence  AS "confidence",
                   dc.source      AS "classificationSource"
            FROM tax.document_classification dc
            JOIN doc.document d ON d.id = dc.document_id
            JOIN ident.department dept ON dept.id = d.department_id
            WHERE dc.term_id = ?
            ORDER BY dc.confidence DESC NULLS LAST, d.created_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            termId, safeSize, offset,
        )
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
        // Inline aliases on the canonical term response so the AI sidecar can pull
        // {term, aliases} in a single call. For very large taxonomies this could
        // be an N+1 — fine for now; revisit with a batch fetch if it shows up in
        // a profile.
        aliases = id?.let { aliasRepo.findByTermId(it).map { a -> a.aliasLabel } } ?: emptyList(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
