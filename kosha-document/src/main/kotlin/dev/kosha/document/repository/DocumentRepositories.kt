package dev.kosha.document.repository

import dev.kosha.document.entity.Document
import dev.kosha.document.entity.DocumentCategory
import dev.kosha.document.entity.DocumentVersion
import dev.kosha.document.entity.VersionMetadata
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DocumentRepository : JpaRepository<Document, UUID> {

    @Query("SELECT d FROM Document d WHERE d.deletedAt IS NULL")
    fun findAllActive(pageable: Pageable): Page<Document>

    @Query("SELECT d FROM Document d WHERE d.department.id = :deptId AND d.deletedAt IS NULL")
    fun findByDepartment(deptId: UUID, pageable: Pageable): Page<Document>

    @Query("SELECT d FROM Document d WHERE d.status = :status AND d.deletedAt IS NULL")
    fun findByStatus(status: String, pageable: Pageable): Page<Document>

    @Query("SELECT d FROM Document d JOIN d.owners o WHERE o.id = :userId AND d.deletedAt IS NULL")
    fun findByOwner(userId: UUID, pageable: Pageable): Page<Document>

    @Query("SELECT d FROM Document d WHERE d.lockedBy.id = :userId AND d.checkedOut = true")
    fun findLockedByUser(userId: UUID): List<Document>
}

@Repository
interface DocumentVersionRepository : JpaRepository<DocumentVersion, UUID> {
    fun findByDocumentIdOrderByCreatedAtDesc(documentId: UUID): List<DocumentVersion>

    fun findFirstByDocumentIdOrderByCreatedAtDesc(documentId: UUID): DocumentVersion?
}

@Repository
interface VersionMetadataRepository : JpaRepository<VersionMetadata, UUID> {
    fun findByVersionId(versionId: UUID): VersionMetadata?
}

@Repository
interface DocumentCategoryRepository : JpaRepository<DocumentCategory, UUID> {
    fun findByStatus(status: String): List<DocumentCategory>
    fun findByDepartmentId(departmentId: UUID): List<DocumentCategory>
}
