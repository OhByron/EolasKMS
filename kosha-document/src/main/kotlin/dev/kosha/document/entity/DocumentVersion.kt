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
import java.time.OffsetDateTime

@Entity
@Table(name = "document_version", schema = "doc")
class DocumentVersion(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(name = "version_number", nullable = false, length = 20)
    var versionNumber: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_version_id")
    var parentVersion: DocumentVersion? = null,

    @Column(name = "file_name", nullable = false, length = 500)
    var fileName: String,

    @Column(name = "file_size_bytes")
    var fileSizeBytes: Long? = null,

    @Column(name = "content_hash", length = 128)
    var contentHash: String? = null,

    @Column(name = "storage_key", length = 1000)
    var storageKey: String? = null,

    @Column(name = "change_summary")
    var changeSummary: String? = null,

    @Column(nullable = false, length = 30)
    var status: String = "DRAFT",

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    var createdBy: UserProfile,

    @Column(name = "publish_at")
    var publishAt: OffsetDateTime? = null,

    @Column(name = "unpublish_at")
    var unpublishAt: OffsetDateTime? = null,

    @OneToOne(mappedBy = "version", fetch = FetchType.LAZY)
    var metadata: VersionMetadata? = null,
) : BaseEntity()
