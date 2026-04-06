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

/**
 * An immutable attestation record.
 *
 * Once created, a signature row is never updated or deleted. It
 * persists as a permanent record that user X attested to version Y
 * at time T with content hash H. See V030 migration for the schema
 * rationale.
 */
@Entity
@Table(name = "document_signature", schema = "doc")
class DocumentSignature(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    var version: DocumentVersion,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signer_id", nullable = false)
    var signer: UserProfile,

    @Column(name = "typed_name", nullable = false, length = 300)
    var typedName: String,

    @Column(name = "content_hash", nullable = false, length = 128)
    var contentHash: String,

    @Column(name = "ip_address", length = 45)
    var ipAddress: String? = null,

    @Column(name = "signed_at", nullable = false)
    var signedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    var id: UUID? = null
}
