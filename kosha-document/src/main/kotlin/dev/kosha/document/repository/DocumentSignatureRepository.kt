package dev.kosha.document.repository

import dev.kosha.document.entity.DocumentSignature
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DocumentSignatureRepository : JpaRepository<DocumentSignature, UUID> {
    fun findByDocumentIdOrderBySignedAtDesc(documentId: UUID): List<DocumentSignature>
    fun findByVersionIdOrderBySignedAtDesc(versionId: UUID): List<DocumentSignature>
    fun existsByDocumentIdAndVersionIdAndSignerId(documentId: UUID, versionId: UUID, signerId: UUID): Boolean
}
