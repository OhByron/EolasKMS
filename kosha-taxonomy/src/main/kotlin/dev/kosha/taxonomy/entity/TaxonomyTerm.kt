package dev.kosha.taxonomy.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "taxonomy_term", schema = "tax")
class TaxonomyTerm(
    @Column(nullable = false, length = 500)
    var label: String,

    @Column(name = "normalized_label", nullable = false, length = 500)
    var normalizedLabel: String,

    var description: String? = null,

    @Column(nullable = false, length = 30)
    var source: String, // SEED, AI_GENERATED, MANUAL

    @Column(name = "source_ref", length = 500)
    var sourceRef: String? = null,

    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE", // ACTIVE, CANDIDATE, MERGED, DEPRECATED

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into_id")
    var mergedInto: TaxonomyTerm? = null,
) : BaseEntity()
