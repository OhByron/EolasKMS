package dev.kosha.document.repository

import dev.kosha.document.entity.ShareLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ShareLinkRepository : JpaRepository<ShareLink, UUID> {
    fun findByTokenHash(tokenHash: String): ShareLink?
    fun findByDocumentIdOrderByCreatedAtDesc(documentId: UUID): List<ShareLink>
    fun findByCreatedByIdOrderByCreatedAtDesc(createdById: UUID): List<ShareLink>
}
