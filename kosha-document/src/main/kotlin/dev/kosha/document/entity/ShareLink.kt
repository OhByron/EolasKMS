package dev.kosha.document.entity

import dev.kosha.identity.entity.UserProfile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "share_link", schema = "doc")
class ShareLink(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    var version: DocumentVersion,

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    var tokenHash: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    var createdBy: UserProfile,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime,

    @Column(name = "password_hash", length = 200)
    var passwordHash: String? = null,

    @Column(name = "max_access")
    var maxAccess: Int? = null,

    @Column(name = "access_count", nullable = false)
    var accessCount: Int = 0,

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    var id: UUID? = null

    val isExpired: Boolean get() = OffsetDateTime.now().isAfter(expiresAt)
    val isRevoked: Boolean get() = revokedAt != null
    val isExhausted: Boolean get() = maxAccess != null && accessCount >= maxAccess!!
}
