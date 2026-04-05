package dev.kosha.document.entity

import dev.kosha.common.domain.SoftDeletable
import dev.kosha.identity.entity.Department
import dev.kosha.identity.entity.UserProfile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "document", schema = "doc")
class Document(
    @Column(name = "doc_number", unique = true, length = 50)
    var docNumber: String? = null,

    @Column(nullable = false, length = 500)
    var title: String,

    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    var department: Department,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: DocumentCategory? = null,

    @Column(nullable = false, length = 30)
    var status: String = "DRAFT",

    @Column(name = "storage_mode", nullable = false, length = 20)
    var storageMode: String = "VAULT",

    @Column(name = "workflow_type", nullable = false, length = 20)
    var workflowType: String = "NONE",

    @Column(name = "checked_out", nullable = false)
    var checkedOut: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_by")
    var lockedBy: UserProfile? = null,

    @Column(name = "locked_at")
    var lockedAt: OffsetDateTime? = null,

    @Column(name = "review_cycle", length = 50)
    var reviewCycle: String? = null,

    @Column(name = "next_review_at")
    var nextReviewAt: OffsetDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    var createdBy: UserProfile,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "primary_owner_id", nullable = false)
    var primaryOwner: UserProfile,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proxy_owner_id")
    var proxyOwner: UserProfile? = null,

    /**
     * Set by the submitter at upload time. When true, the workflow engine
     * (Pass 3) adds a parallel legal review step to the workflow instance
     * assigned to [legalReviewer]. The submitter picks a specific user from
     * a department flagged as handles_legal_review.
     */
    @Column(name = "requires_legal_review", nullable = false)
    var requiresLegalReview: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "legal_reviewer_id")
    var legalReviewer: UserProfile? = null,

    @OneToMany(mappedBy = "document", fetch = FetchType.LAZY)
    @OrderBy("createdAt DESC")
    var versions: MutableList<DocumentVersion> = mutableListOf(),

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "document_owner",
        schema = "doc",
        joinColumns = [JoinColumn(name = "document_id")],
        inverseJoinColumns = [JoinColumn(name = "user_profile_id")],
    )
    var owners: MutableSet<UserProfile> = mutableSetOf(),
) : SoftDeletable()
