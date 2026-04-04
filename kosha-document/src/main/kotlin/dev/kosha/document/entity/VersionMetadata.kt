package dev.kosha.document.entity

import dev.kosha.common.domain.BaseEntity
import dev.kosha.identity.entity.UserProfile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "version_metadata", schema = "doc")
class VersionMetadata(
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, unique = true)
    var version: DocumentVersion,

    var summary: String? = null,

    @Column(name = "extracted_text")
    var extractedText: String? = null,

    @Column(name = "ai_confidence", precision = 3, scale = 2)
    var aiConfidence: BigDecimal? = null,

    @Column(name = "human_reviewed", nullable = false)
    var humanReviewed: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    var reviewedBy: UserProfile? = null,
) : BaseEntity()
