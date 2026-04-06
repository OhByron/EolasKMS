package dev.kosha.document.service

import dev.kosha.common.event.DocumentCheckedIn
import dev.kosha.common.event.DocumentCheckedOut
import dev.kosha.common.event.DocumentCreated
import dev.kosha.common.event.DocumentLegalHoldApplied
import dev.kosha.common.event.DocumentStatusChanged
import dev.kosha.common.event.DocumentSubmittedForReview
import dev.kosha.common.event.DocumentVersionCreated
import dev.kosha.common.event.LegalReviewerPreSelected
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
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
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
    private val entityManager: EntityManager,
) {

    /**
     * Read the global legal review time limit from notif.legal_review_settings.
     * Kept as a native query to avoid coupling the document module to the
     * notification module. Defaults to 5 days if the row is missing (which
     * should never happen thanks to V024's seed).
     */
    private fun legalReviewTimeLimitDays(): Int {
        return try {
            val result = entityManager
                .createNativeQuery("SELECT default_time_limit_days FROM notif.legal_review_settings WHERE id = 'default'")
                .singleResult
            (result as Number).toInt()
        } catch (_: Exception) {
            5
        }
    }

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

        // Authority: non-admins can only upload into their own department.
        // Global admins may file into any active department. This mirrors the
        // rule enforced client-side in MeController.uploadableDepartments; the
        // check is duplicated here because the frontend list is advisory and
        // a forged POST must still be rejected.
        if (creator.role != "GLOBAL_ADMIN" && creator.department.id != department.id) {
            throw AccessDeniedException(
                "User is not a member of department ${department.id}",
            )
        }

        // Ownership: if ownerId provided and differs from uploader,
        // that person is the primary owner and the uploader becomes proxy.
        val primaryOwner = request.ownerId?.let { oid ->
            if (oid == actorId) creator
            else userProfileRepo.findById(oid)
                .orElseThrow { NoSuchElementException("Owner not found: $oid") }
        } ?: creator

        val proxyOwner = if (primaryOwner.id != actorId) creator else null

        // Legal review: if requested, resolve and validate the chosen reviewer.
        //  - reviewer must exist and be ACTIVE
        //  - reviewer must belong to a department flagged handles_legal_review=true
        //  - if requiresLegalReview is true, legalReviewerId must be set
        val legalReviewer = if (request.requiresLegalReview) {
            val reviewerId = request.legalReviewerId
                ?: throw IllegalArgumentException(
                    "legalReviewerId is required when requiresLegalReview is true"
                )
            val reviewer = userProfileRepo.findById(reviewerId)
                .orElseThrow { NoSuchElementException("Legal reviewer not found: $reviewerId") }
            require(reviewer.status == "ACTIVE") {
                "Legal reviewer '${reviewer.displayName}' is not active"
            }
            val reviewerDept = reviewer.department
            require(reviewerDept.handlesLegalReview) {
                "Legal reviewer '${reviewer.displayName}' is not in a department authorised for legal review"
            }
            reviewer
        } else {
            // Quietly ignore a legalReviewerId if requiresLegalReview is false —
            // protects against stale form state without surprising the user.
            null
        }

        val doc = Document(
            title = request.title,
            description = request.description,
            department = department,
            category = category,
            storageMode = request.storageMode,
            workflowType = request.workflowType,
            createdBy = creator,
            primaryOwner = primaryOwner,
            proxyOwner = proxyOwner,
            requiresLegalReview = request.requiresLegalReview,
            legalReviewer = legalReviewer,
        )
        doc.owners.add(primaryOwner)
        if (proxyOwner != null) doc.owners.add(proxyOwner)
        val saved = documentRepo.save(doc)

        events.publishEvent(DocumentCreated(
            aggregateId = saved.id!!,
            title = saved.title,
            departmentId = department.id!!,
            actorId = actorId,
        ))

        // Pre-selection notification: only fired when a reviewer was chosen.
        // The actual review task is created later when the document is
        // submitted to the workflow engine (Pass 3). This event just sends
        // a courtesy heads-up email.
        if (legalReviewer != null) {
            events.publishEvent(LegalReviewerPreSelected(
                aggregateId = saved.id!!,
                documentTitle = saved.title,
                submitterId = creator.id!!,
                submitterName = creator.displayName,
                departmentId = department.id!!,
                departmentName = department.name,
                legalReviewerId = legalReviewer.id!!,
                timeLimitDays = legalReviewTimeLimitDays(),
                actorId = actorId,
            ))
        }

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
        request.primaryOwnerId?.let { ownerId ->
            doc.primaryOwner = userProfileRepo.findById(ownerId)
                .orElseThrow { NoSuchElementException("Owner not found: $ownerId") }
        }
        request.proxyOwnerId?.let { proxyId ->
            doc.proxyOwner = userProfileRepo.findById(proxyId)
                .orElseThrow { NoSuchElementException("Proxy owner not found: $proxyId") }
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
                // Fire legal hold event so notification module can email the owner
                if (newStatus == "LEGAL_HOLD") {
                    events.publishEvent(DocumentLegalHoldApplied(
                        aggregateId = doc.id!!,
                        title = doc.title,
                        primaryOwnerId = doc.primaryOwner.id!!,
                        proxyOwnerId = doc.proxyOwner?.id,
                        departmentId = doc.department.id!!,
                        actorId = actorId,
                    ))
                }
                // DRAFT -> IN_REVIEW kicks off the workflow engine. The engine
                // listens synchronously and joins this transaction, so if it
                // throws (broken workflow, missing assignees) the status update
                // rolls back and the document stays in DRAFT.
                if (oldStatus == "DRAFT" && newStatus == "IN_REVIEW") {
                    val latest = versionRepo.findLatestByDocumentId(doc.id!!)
                        ?: throw IllegalStateException(
                            "Cannot submit document ${doc.id} for review: no version uploaded"
                        )
                    events.publishEvent(DocumentSubmittedForReview(
                        aggregateId = doc.id!!,
                        versionId = latest.id!!,
                        departmentId = doc.department.id!!,
                        submitterId = actorId,
                        requiresLegalReview = doc.requiresLegalReview,
                        legalReviewerId = doc.legalReviewer?.id,
                        actorId = actorId,
                    ))
                }
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
        val hadPriorVersion = latest != null

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

        // New-version workflow re-entry (Pass 3d requirement).
        //
        // When an existing document gets a new version, the document must
        // re-enter the department workflow — users shouldn't be able to
        // slip edits through by uploading a version. We flip the doc to
        // IN_REVIEW and fire DocumentSubmittedForReview so the engine
        // creates a fresh instance bound to the new version id.
        //
        // Exclusions:
        //   - hadPriorVersion = false → this IS the initial version for a
        //     newly-created document; the caller (upload flow) wants the
        //     doc to stay in DRAFT until the user clicks Submit for Review
        //   - LEGAL_HOLD → document is frozen, no state changes allowed
        //   - DRAFT → user is still iterating on the initial submission;
        //     don't force them into review just because they uploaded a
        //     revision before submitting
        val shouldResubmit = hadPriorVersion &&
            doc.status != "DRAFT" &&
            doc.status != "LEGAL_HOLD" &&
            doc.status != "IN_REVIEW"
        if (shouldResubmit) {
            val oldStatus = doc.status
            doc.status = "IN_REVIEW"
            documentRepo.save(doc)

            events.publishEvent(DocumentStatusChanged(
                aggregateId = doc.id!!,
                previousStatus = oldStatus,
                newStatus = "IN_REVIEW",
                actorId = actorId,
            ))
            events.publishEvent(DocumentSubmittedForReview(
                aggregateId = doc.id!!,
                versionId = saved.id!!,
                departmentId = doc.department.id!!,
                submitterId = actorId,
                requiresLegalReview = doc.requiresLegalReview,
                legalReviewerId = doc.legalReviewer?.id,
                actorId = actorId,
            ))
        }

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
            primaryOwnerName = primaryOwner.displayName,
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
            primaryOwnerId = primaryOwner.id!!,
            primaryOwnerName = primaryOwner.displayName,
            proxyOwnerId = proxyOwner?.id,
            proxyOwnerName = proxyOwner?.displayName,
            requiresLegalReview = requiresLegalReview,
            legalReviewerId = legalReviewer?.id,
            legalReviewerName = legalReviewer?.displayName,
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
        contentType = contentType,
        ocrApplied = ocrApplied,
        ocrLanguage = ocrLanguage,
        extractedMetadata = extractedMetadata?.let {
            try {
                @Suppress("UNCHECKED_CAST")
                com.fasterxml.jackson.databind.ObjectMapper().readValue(it, Map::class.java) as Map<String, Any>
            } catch (_: Exception) { null }
        },
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
