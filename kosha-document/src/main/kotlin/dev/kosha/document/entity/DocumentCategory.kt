package dev.kosha.document.entity

import dev.kosha.common.domain.BaseEntity
import dev.kosha.identity.entity.Department
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "document_category", schema = "doc")
class DocumentCategory(
    @Column(nullable = false, length = 200)
    var name: String,

    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    var department: Department? = null,

    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE",

    /**
     * When true, the upload form pre-ticks the "requires legal review"
     * checkbox if this category is selected. Advisory only — the submitter
     * can always override. Set by GLOBAL_ADMIN.
     */
    @Column(name = "suggests_legal_review", nullable = false)
    var suggestsLegalReview: Boolean = false,
) : BaseEntity()
