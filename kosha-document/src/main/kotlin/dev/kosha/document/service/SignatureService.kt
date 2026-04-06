package dev.kosha.document.service

import dev.kosha.document.entity.DocumentSignature
import dev.kosha.document.repository.DocumentRepository
import dev.kosha.document.repository.DocumentSignatureRepository
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.identity.repository.UserProfileRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Creates and queries document signatures.
 *
 * ## Design constraints
 *
 * - **Immutable records.** There is no `update` or `delete` method.
 *   Signatures are write-once audit records. If a signer needs to
 *   "unsign", the correct path is to upload a new version and sign
 *   that instead — the old signature stays as history.
 *
 * - **Per-version uniqueness.** A user can sign a given version at
 *   most once (enforced by a DB unique constraint). Calling `sign()`
 *   twice for the same user + version throws rather than silently
 *   succeeding, because a duplicate call implies a UI bug or a
 *   replay attack.
 *
 * - **Content hash verification.** The caller provides the expected
 *   content hash (from the version row, which was set at upload time
 *   by the upload endpoint). The service verifies that the version's
 *   stored hash matches, so we catch any tampering between "user
 *   viewed the document" and "user clicked sign". If the hashes
 *   don't match, the sign operation is rejected.
 */
@Service
class SignatureService(
    private val signatureRepo: DocumentSignatureRepository,
    private val documentRepo: DocumentRepository,
    private val versionRepo: DocumentVersionRepository,
    private val userProfileRepo: UserProfileRepository,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val signaturesCreated = meterRegistry.counter("eolas.signatures.created")

    @Transactional(readOnly = true)
    fun listForDocument(documentId: UUID): List<SignatureResponse> =
        signatureRepo.findByDocumentIdOrderBySignedAtDesc(documentId)
            .map { it.toResponse() }

    @Transactional(readOnly = true)
    fun listForVersion(versionId: UUID): List<SignatureResponse> =
        signatureRepo.findByVersionIdOrderBySignedAtDesc(versionId)
            .map { it.toResponse() }

    /**
     * Create a signature record for a user on a specific version.
     *
     * @param documentId the document being signed
     * @param versionId the specific version being attested to
     * @param signerId the user signing (from JWT subject resolution)
     * @param typedName the signer's typed name affirmation
     * @param expectedContentHash the SHA-256 hash the signer expects
     *        the version to have — verified against the stored hash
     * @param ipAddress optional IP address for audit trail
     */
    @Transactional
    fun sign(
        documentId: UUID,
        versionId: UUID,
        signerId: UUID,
        typedName: String,
        expectedContentHash: String?,
        ipAddress: String?,
    ): SignatureResponse {
        require(typedName.isNotBlank()) { "Typed name is required for signing" }

        val document = documentRepo.findById(documentId)
            .orElseThrow { NoSuchElementException("Document not found: $documentId") }
        if (document.isDeleted) throw NoSuchElementException("Document not found: $documentId")

        val version = versionRepo.findById(versionId)
            .orElseThrow { NoSuchElementException("Version not found: $versionId") }
        if (version.document.id != documentId) {
            throw NoSuchElementException("Version $versionId does not belong to document $documentId")
        }

        val signer = userProfileRepo.findById(signerId)
            .orElseThrow { NoSuchElementException("User not found: $signerId") }

        // Content hash verification — if the caller provides an expected
        // hash, it must match the stored hash on the version. This catches
        // any modification between "user previewed the doc" and "user
        // clicked sign." If the version has no stored hash (pre-Pass-4.1
        // uploads), we skip the check rather than blocking the sign —
        // the signed_at timestamp and signer identity are the important
        // attestation fields.
        val storedHash = version.contentHash
        if (expectedContentHash != null && storedHash != null && expectedContentHash != storedHash) {
            throw IllegalStateException(
                "Content hash mismatch: the document may have been modified since you viewed it. " +
                    "Please refresh and try again.",
            )
        }

        // Duplicate check — the DB constraint catches this too, but
        // throwing a readable message is friendlier than a constraint
        // violation exception surfacing through the error handler.
        if (signatureRepo.existsByDocumentIdAndVersionIdAndSignerId(documentId, versionId, signerId)) {
            throw IllegalStateException(
                "You have already signed this version of the document.",
            )
        }

        val signature = DocumentSignature(
            document = document,
            version = version,
            signer = signer,
            typedName = typedName,
            contentHash = storedHash ?: "unknown",
            ipAddress = ipAddress,
        )
        val saved = signatureRepo.save(signature)
        signaturesCreated.increment()

        log.info(
            "Signature created: user {} signed document {} version {} (hash={})",
            signer.email, documentId, versionId, storedHash?.take(16),
        )

        return saved.toResponse()
    }

    /**
     * Called by the workflow engine when a SIGN_OFF step is approved.
     * Creates a signature record automatically so the approver doesn't
     * have to sign separately. The typed name is the approver's display
     * name and the IP is null (server-side action).
     */
    @Transactional
    fun signFromWorkflow(
        documentId: UUID,
        versionId: UUID,
        signerId: UUID,
    ): SignatureResponse {
        val signer = userProfileRepo.findById(signerId)
            .orElseThrow { NoSuchElementException("User not found: $signerId") }
        return sign(
            documentId = documentId,
            versionId = versionId,
            signerId = signerId,
            typedName = signer.displayName,
            expectedContentHash = null, // server-side, no client hash to verify
            ipAddress = null,
        )
    }

    private fun DocumentSignature.toResponse() = SignatureResponse(
        id = id!!,
        documentId = document.id!!,
        versionId = version.id!!,
        versionNumber = version.versionNumber,
        signerId = signer.id!!,
        signerName = signer.displayName,
        signerEmail = signer.email,
        typedName = typedName,
        contentHash = contentHash,
        signedAt = signedAt,
    )
}

data class SignatureResponse(
    val id: UUID,
    val documentId: UUID,
    val versionId: UUID,
    val versionNumber: String,
    val signerId: UUID,
    val signerName: String,
    val signerEmail: String,
    val typedName: String,
    val contentHash: String,
    val signedAt: OffsetDateTime,
)
