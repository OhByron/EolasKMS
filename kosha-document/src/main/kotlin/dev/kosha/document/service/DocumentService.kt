package dev.kosha.document.service

import dev.kosha.common.event.DocumentCheckedIn
import dev.kosha.common.event.DocumentCheckedOut
import dev.kosha.common.event.DocumentCreated
import dev.kosha.common.event.DocumentStatusChanged
import dev.kosha.common.event.DocumentVersionCreated
import dev.kosha.document.dto.CreateDocumentRequest
import dev.kosha.document.dto.CreateVersionRequest
import dev.kosha.document.dto.DocumentListResponse
import dev.kosha.document.dto.DocumentResponse
import dev.kosha.document.dto.MetadataResponse
import dev.kosha.document.dto.UpdateDocumentRequest
import dev.kosha.document.dto.VersionDetailResponse
import dev.kosha.document.dto.VersionSummary
import dev.kosha.document.entity.Document
import dev.kosha.document.entity.DocumentVersion
import dev.kosha.document.repository.DocumentCategoryRepository
import dev.kosha.document.repository.DocumentRepository
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional(readOnly = true)
class DocumentService(
    private val documentRepo: DocumentRepository,
    private val versionRepo: DocumentVersionRepository,
    private val departmentRepo: DepartmentRepository,
    private val categoryRepo: DocumentCategoryRepository,
    private val userProfileRepo: UserProfileRepository,
    private val events: ApplicationEventPublisher,
) {

    fun findAll(pageable: Pageable): Page<DocumentListResponse> =
        documentRepo.findAllActive(pageable).map { it.toListResponse() }

    fun findByDepartment(deptId: UUID, pageable: Pageable): Page<DocumentListResponse> =
        documentRepo.findByDepartment(deptId, pageable).map { it.toListResponse() }

    fun findById(id: UUID): DocumentResponse {
        val doc = documentRepo.findById(id)
            .orElseThrow { NoSuchElementException("Document not found: $id") }
        if (doc.isDeleted) throw NoSuchElementException("Document not found: $id")
        return doc.toDetailResponse()
    }

    @Transactional
    fun create(request: CreateDocumentRequest, actorId: UUID): DocumentResponse {
        val department = departmentRepo.findById(request.departmentId)
            .orElseThrow { NoSuchElementException("Department not found: ${request.departmentId}") }
        val category = request.categoryId?.let {
            categoryRepo.findById(it).orElseThrow { NoSuchElementException("Category not found: $it") }
        }
        val creator = userProfileRepo.findById(actorId)
            .orElseThrow { NoSuchElementException("User not found: $actorId") }

        val doc = Document(
            title = request.title,
            description = request.description,
            department = department,
            category = category,
            storageMode = request.storageMode,
            workflowType = request.workflowType,
            createdBy = creator,
        )
        doc.owners.add(creator)
        val saved = documentRepo.save(doc)

        events.publishEvent(DocumentCreated(
            aggregateId = saved.id!!,
            title = saved.title,
            departmentId = department.id!!,
            actorId = actorId,
        ))

        return saved.toDetailResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpdateDocumentRequest, actorId: UUID): DocumentResponse {
        val doc = findEntity(id)
        val oldStatus = doc.status

        request.title?.let { doc.title = it }
        request.description?.let { doc.description = it }
        request.workflowType?.let { doc.workflowType = it }
        request.categoryId?.let { catId ->
            doc.category = categoryRepo.findById(catId)
                .orElseThrow { NoSuchElementException("Category not found: $catId") }
        }
        request.status?.let { newStatus ->
            doc.status = newStatus
            if (oldStatus != newStatus) {
                events.publishEvent(DocumentStatusChanged(
                    aggregateId = doc.id!!,
                    previousStatus = oldStatus,
                    newStatus = newStatus,
                    actorId = actorId,
                ))
            }
        }

        return documentRepo.save(doc).toDetailResponse()
    }

    @Transactional
    fun checkout(id: UUID, actorId: UUID): DocumentResponse {
        val doc = findEntity(id)
        if (doc.checkedOut) {
            throw IllegalStateException("Document is already checked out by user ${doc.lockedBy?.id}")
        }
        val user = userProfileRepo.findById(actorId)
            .orElseThrow { NoSuchElementException("User not found: $actorId") }

        doc.checkedOut = true
        doc.lockedBy = user
        doc.lockedAt = OffsetDateTime.now()

        events.publishEvent(DocumentCheckedOut(aggregateId = doc.id!!, actorId = actorId))

        return documentRepo.save(doc).toDetailResponse()
    }

    @Transactional
    fun checkin(id: UUID, actorId: UUID): DocumentResponse {
        val doc = findEntity(id)
        if (!doc.checkedOut) throw IllegalStateException("Document is not checked out")
        if (doc.lockedBy?.id != actorId) {
            throw IllegalStateException("Document is checked out by another user")
        }

        doc.checkedOut = false
        doc.lockedBy = null
        doc.lockedAt = null

        events.publishEvent(DocumentCheckedIn(aggregateId = doc.id!!, actorId = actorId))

        return documentRepo.save(doc).toDetailResponse()
    }

    @Transactional
    fun softDelete(id: UUID, actorId: UUID) {
        val doc = findEntity(id)
        doc.softDelete()
        documentRepo.save(doc)

        events.publishEvent(DocumentStatusChanged(
            aggregateId = doc.id!!,
            previousStatus = doc.status,
            newStatus = "DELETED",
            actorId = actorId,
        ))
    }

    // --- Versions ---

    fun listVersions(documentId: UUID): List<VersionDetailResponse> =
        versionRepo.findByDocumentIdOrderByCreatedAtDesc(documentId).map { it.toResponse() }

    @Transactional
    fun createVersion(documentId: UUID, request: CreateVersionRequest, actorId: UUID): VersionDetailResponse {
        val doc = findEntity(documentId)
        val creator = userProfileRepo.findById(actorId)
            .orElseThrow { NoSuchElementException("User not found: $actorId") }

        val latest = versionRepo.findLatestByDocumentId(documentId)
        val nextVersion = latest?.let { incrementVersion(it.versionNumber) } ?: "1.0"

        val version = DocumentVersion(
            document = doc,
            versionNumber = nextVersion,
            parentVersion = latest,
            fileName = request.fileName,
            fileSizeBytes = request.fileSizeBytes,
            storageKey = request.storageKey,
            changeSummary = request.changeSummary,
            createdBy = creator,
        )
        val saved = versionRepo.save(version)

        events.publishEvent(DocumentVersionCreated(
            aggregateId = saved.id!!,
            documentId = documentId,
            versionNumber = nextVersion,
            actorId = actorId,
        ))

        return saved.toResponse()
    }

    // --- Helpers ---

    private fun findEntity(id: UUID): Document {
        val doc = documentRepo.findById(id)
            .orElseThrow { NoSuchElementException("Document not found: $id") }
        if (doc.isDeleted) throw NoSuchElementException("Document not found: $id")
        return doc
    }

    private fun incrementVersion(current: String): String {
        val parts = current.split(".")
        val minor = (parts.getOrNull(1)?.toIntOrNull() ?: 0) + 1
        return "${parts[0]}.$minor"
    }

    private fun Document.toListResponse(): DocumentListResponse {
        val latest = versionRepo.findLatestByDocumentId(id!!)
        return DocumentListResponse(
            id = id!!,
            docNumber = docNumber,
            title = title,
            departmentName = department.name,
            status = status,
            checkedOut = checkedOut,
            currentVersion = latest?.versionNumber,
            createdAt = createdAt,
        )
    }

    private fun Document.toDetailResponse(): DocumentResponse {
        val latest = versionRepo.findLatestByDocumentId(id!!)
        return DocumentResponse(
            id = id!!,
            docNumber = docNumber,
            title = title,
            description = description,
            departmentId = department.id!!,
            departmentName = department.name,
            categoryId = category?.id,
            categoryName = category?.name,
            status = status,
            storageMode = storageMode,
            workflowType = workflowType,
            checkedOut = checkedOut,
            lockedBy = lockedBy?.id,
            currentVersion = latest?.let {
                VersionSummary(
                    id = it.id!!,
                    versionNumber = it.versionNumber,
                    fileName = it.fileName,
                    status = it.status,
                    createdAt = it.createdAt,
                )
            },
            createdBy = createdBy.id!!,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun DocumentVersion.toResponse() = VersionDetailResponse(
        id = id!!,
        documentId = document.id!!,
        versionNumber = versionNumber,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        contentHash = contentHash,
        storageKey = storageKey,
        changeSummary = changeSummary,
        status = status,
        createdBy = createdBy.id!!,
        publishAt = publishAt,
        createdAt = createdAt,
        metadata = metadata?.let {
            MetadataResponse(
                summary = it.summary,
                aiConfidence = it.aiConfidence?.toDouble(),
                humanReviewed = it.humanReviewed,
            )
        },
    )
}
