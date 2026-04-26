package dev.kosha.taxonomy.repository

import dev.kosha.taxonomy.entity.TaxonomyEdge
import dev.kosha.taxonomy.entity.TaxonomyTerm
import dev.kosha.taxonomy.entity.TaxonomyTermAlias
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TaxonomyTermRepository : JpaRepository<TaxonomyTerm, UUID> {
    fun findByStatus(status: String): List<TaxonomyTerm>

    fun findByStatusIn(statuses: List<String>): List<TaxonomyTerm>

    @Query("SELECT t FROM TaxonomyTerm t WHERE t.status = 'CANDIDATE'")
    fun findCandidates(): List<TaxonomyTerm>

    fun findByNormalizedLabel(normalizedLabel: String): TaxonomyTerm?
}

@Repository
interface TaxonomyEdgeRepository : JpaRepository<TaxonomyEdge, UUID> {
    fun findByParentTermId(parentTermId: UUID): List<TaxonomyEdge>
    fun findByChildTermId(childTermId: UUID): List<TaxonomyEdge>
    fun findByParentTermIdAndChildTermId(parentTermId: UUID, childTermId: UUID): List<TaxonomyEdge>

    @Query("SELECT e FROM TaxonomyEdge e WHERE e.edgeType = 'BROADER'")
    fun findAllBroaderEdges(): List<TaxonomyEdge>
}

@Repository
interface TaxonomyTermAliasRepository : JpaRepository<TaxonomyTermAlias, UUID> {
    fun findByTermId(termId: UUID): List<TaxonomyTermAlias>
    fun findByTermIdAndNormalizedAliasLabel(termId: UUID, normalizedAliasLabel: String): TaxonomyTermAlias?
    fun deleteByTermId(termId: UUID)
}
