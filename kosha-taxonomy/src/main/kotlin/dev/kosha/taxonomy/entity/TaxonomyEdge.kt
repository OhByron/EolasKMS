package dev.kosha.taxonomy.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "taxonomy_edge", schema = "tax")
class TaxonomyEdge(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_term_id", nullable = false)
    var parentTerm: TaxonomyTerm,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_term_id", nullable = false)
    var childTerm: TaxonomyTerm,

    @Column(name = "edge_type", nullable = false, length = 20)
    var edgeType: String = "BROADER", // BROADER, RELATED, SEE_ALSO
) : BaseEntity()
