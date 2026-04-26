package dev.kosha.taxonomy.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * Alternative surface form ("synonym") for a taxonomy term.
 *
 * The Stage-1 LLM classifier sees these alongside the canonical term so a
 * mention of "Water Sanitation Hygiene" classifies under the canonical "WASH"
 * instead of becoming a duplicate CANDIDATE. See V037 migration for rationale.
 */
@Entity
@Table(name = "taxonomy_term_alias", schema = "tax")
class TaxonomyTermAlias(
    @Column(name = "term_id", nullable = false)
    var termId: UUID,

    @Column(name = "alias_label", nullable = false, length = 500)
    var aliasLabel: String,

    @Column(name = "normalized_alias_label", nullable = false, length = 500)
    var normalizedAliasLabel: String,

    @Column(nullable = false, length = 30)
    var source: String, // AI_SUGGESTED | MANUAL
) : BaseEntity()
