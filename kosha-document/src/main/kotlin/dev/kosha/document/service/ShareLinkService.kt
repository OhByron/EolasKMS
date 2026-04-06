package dev.kosha.document.service

import dev.kosha.document.entity.ShareLink
import dev.kosha.document.repository.DocumentRepository
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.document.repository.ShareLinkRepository
import dev.kosha.identity.repository.UserProfileRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Create, resolve, list, and revoke share links.
 *
 * ## Token lifecycle
 *
 * 1. **Create** — generate 32 bytes of `SecureRandom`, base64url-encode
 *    to get the raw token (44 chars). SHA-256 hash it and store the hash
 *    in the DB. Return the raw token to the creator ONCE.
 *
 * 2. **Resolve** — the public endpoint receives the raw token from the
 *    URL path, hashes it, and looks up the hash in the DB. Multiple
 *    checks gate access: not expired, not revoked, not exhausted,
 *    document is still PUBLISHED.
 *
 * 3. **Revoke** — creator sets `revoked_at`, the resolve path rejects.
 *
 * The raw token is never stored. An attacker who dumps the share_link
 * table cannot reconstruct a valid URL.
 */
@Service
class ShareLinkService(
    private val shareLinkRepo: ShareLinkRepository,
    private val documentRepo: DocumentRepository,
    private val versionRepo: DocumentVersionRepository,
    private val userProfileRepo: UserProfileRepository,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val bcrypt = BCryptPasswordEncoder()

    private val linksCreated = meterRegistry.counter("eolas.share_links.created")
    private val linksAccessed = meterRegistry.counter("eolas.share_links.accessed", "outcome", "allowed")
    private val linksExpired = meterRegistry.counter("eolas.share_links.accessed", "outcome", "expired")
    private val linksRevoked = meterRegistry.counter("eolas.share_links.accessed", "outcome", "revoked")
    private val linksWrongStatus = meterRegistry.counter("eolas.share_links.accessed", "outcome", "wrong_status")

    companion object {
        const val TOKEN_BYTES = 32
        const val MAX_EXPIRY_DAYS = 90L
        const val DEFAULT_EXPIRY_DAYS = 7L
    }

    /**
     * Create a share link. Returns the raw token that becomes the URL
     * path segment. This is the only time the raw token is available —
     * it is NOT stored.
     *
     * @throws IllegalStateException if the document is not PUBLISHED
     */
    @Transactional
    fun create(
        documentId: UUID,
        versionId: UUID,
        creatorId: UUID,
        expiryDays: Long?,
        password: String?,
        maxAccess: Int?,
    ): ShareLinkCreated {
        val document = documentRepo.findById(documentId)
            .orElseThrow { NoSuchElementException("Document not found: $documentId") }
        if (document.isDeleted) throw NoSuchElementException("Document not found: $documentId")

        // PUBLISHED-only gate (roadmap decision from the competitive assessment).
        if (document.status != "PUBLISHED") {
            throw IllegalStateException(
                "Share links can only be created for published documents (current status: ${document.status})",
            )
        }

        val version = versionRepo.findById(versionId)
            .orElseThrow { NoSuchElementException("Version not found: $versionId") }
        if (version.document.id != documentId) {
            throw NoSuchElementException("Version $versionId does not belong to document $documentId")
        }

        val creator = userProfileRepo.findById(creatorId)
            .orElseThrow { NoSuchElementException("User not found: $creatorId") }

        val days = (expiryDays ?: DEFAULT_EXPIRY_DAYS).coerceIn(1, MAX_EXPIRY_DAYS)

        // Generate token
        val rawBytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(rawBytes)
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        val tokenHash = sha256(rawToken)

        val passwordHash = password?.takeIf { it.isNotBlank() }?.let { bcrypt.encode(it) }

        val link = ShareLink(
            document = document,
            version = version,
            tokenHash = tokenHash,
            createdBy = creator,
            expiresAt = OffsetDateTime.now().plusDays(days),
            passwordHash = passwordHash,
            maxAccess = maxAccess?.takeIf { it > 0 },
        )
        val saved = shareLinkRepo.save(link)
        linksCreated.increment()

        log.info(
            "Created share link {} for document {} version {} (expires in {} days, password={})",
            saved.id, documentId, versionId, days, passwordHash != null,
        )

        return ShareLinkCreated(
            id = saved.id!!,
            token = rawToken,
            expiresAt = saved.expiresAt,
            hasPassword = passwordHash != null,
            maxAccess = saved.maxAccess,
        )
    }

    /**
     * Resolve a share link from the raw token in the URL. Returns the
     * document/version info needed to render the public preview page,
     * or throws with a specific reason the link is invalid.
     *
     * Increments the access counter on success. The counter increment
     * is transactional with the resolution so a concurrent double-click
     * can't exceed `max_access`.
     */
    @Transactional
    fun resolve(rawToken: String, password: String?): ShareLinkResolved {
        val tokenHash = sha256(rawToken)
        val link = shareLinkRepo.findByTokenHash(tokenHash)
            ?: throw NoSuchElementException("Share link not found or invalid")

        if (link.isRevoked) {
            linksRevoked.increment()
            throw ShareLinkGoneException("This share link has been revoked.")
        }
        if (link.isExpired) {
            linksExpired.increment()
            throw ShareLinkGoneException("This share link has expired.")
        }
        if (link.isExhausted) {
            linksExpired.increment()
            throw ShareLinkGoneException("This share link has reached its access limit.")
        }

        // PUBLISHED-only resolution gate. If the doc is no longer published,
        // the link returns 410 without the viewer ever seeing content.
        val doc = link.document
        if (doc.isDeleted || doc.status != "PUBLISHED") {
            linksWrongStatus.increment()
            throw ShareLinkGoneException("This document is no longer available.")
        }

        // Password check
        if (link.passwordHash != null) {
            if (password.isNullOrBlank()) {
                throw ShareLinkPasswordRequiredException()
            }
            if (!bcrypt.matches(password, link.passwordHash)) {
                throw ShareLinkPasswordRequiredException("Incorrect password.")
            }
        }

        // Bump access counter
        link.accessCount++
        shareLinkRepo.save(link)
        linksAccessed.increment()

        val version = link.version
        return ShareLinkResolved(
            documentId = doc.id!!,
            documentTitle = doc.title,
            departmentName = doc.department.name,
            versionId = version.id!!,
            versionNumber = version.versionNumber,
            fileName = version.fileName,
            contentType = version.contentType,
            storageKey = version.storageKey,
            ocrApplied = version.ocrApplied,
            ocrStorageKey = version.ocrStorageKey,
        )
    }

    @Transactional(readOnly = true)
    fun listForDocument(documentId: UUID): List<ShareLinkSummary> =
        shareLinkRepo.findByDocumentIdOrderByCreatedAtDesc(documentId)
            .map { it.toSummary() }

    @Transactional
    fun revoke(linkId: UUID, actorId: UUID) {
        val link = shareLinkRepo.findById(linkId)
            .orElseThrow { NoSuchElementException("Share link not found: $linkId") }
        // Only the creator or a global admin can revoke. For v1 we
        // trust the caller (controller checks auth); a future pass
        // could add an owner check here.
        link.revokedAt = OffsetDateTime.now()
        shareLinkRepo.save(link)
        log.info("Revoked share link {} by user {}", linkId, actorId)
    }

    private fun ShareLink.toSummary() = ShareLinkSummary(
        id = id!!,
        versionNumber = version.versionNumber,
        expiresAt = expiresAt,
        hasPassword = passwordHash != null,
        maxAccess = maxAccess,
        accessCount = accessCount,
        revoked = revokedAt != null,
        createdAt = createdAt,
        createdByName = createdBy.displayName,
    )

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

// ── DTOs ──────────────────────────────────────────────────

data class ShareLinkCreated(
    val id: UUID,
    val token: String,
    val expiresAt: OffsetDateTime,
    val hasPassword: Boolean,
    val maxAccess: Int?,
)

data class ShareLinkResolved(
    val documentId: UUID,
    val documentTitle: String,
    val departmentName: String,
    val versionId: UUID,
    val versionNumber: String,
    val fileName: String,
    val contentType: String?,
    val storageKey: String?,
    val ocrApplied: Boolean,
    val ocrStorageKey: String?,
)

data class ShareLinkSummary(
    val id: UUID,
    val versionNumber: String,
    val expiresAt: OffsetDateTime,
    val hasPassword: Boolean,
    val maxAccess: Int?,
    val accessCount: Int,
    val revoked: Boolean,
    val createdAt: OffsetDateTime,
    val createdByName: String,
)

class ShareLinkGoneException(message: String) : RuntimeException(message)
class ShareLinkPasswordRequiredException(
    message: String = "This share link requires a password.",
) : RuntimeException(message)
