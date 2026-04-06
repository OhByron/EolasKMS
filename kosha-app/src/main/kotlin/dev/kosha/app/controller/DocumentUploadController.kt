package dev.kosha.app.controller

import dev.kosha.app.nats.AiTaskMessage
import dev.kosha.app.nats.NatsService
import dev.kosha.common.api.ApiResponse
import dev.kosha.document.entity.VersionMetadata
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.document.repository.VersionMetadataRepository
import dev.kosha.storage.MinioStorageService
import io.micrometer.core.instrument.MeterRegistry
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/api/v1/documents")
class DocumentUploadController(
    private val versionRepo: DocumentVersionRepository,
    private val metadataRepo: VersionMetadataRepository,
    private val natsService: NatsService,
    private val jdbcTemplate: JdbcTemplate,
    private val storage: MinioStorageService,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika()

    // Pass 4 instrument-as-we-go: track storage-writes by outcome so
    // Pass 6 dashboards don't need to retrofit this.
    private val uploadsSucceeded = meterRegistry.counter("eolas.uploads.stored", "outcome", "success")
    private val uploadsFailed = meterRegistry.counter("eolas.uploads.stored", "outcome", "failure")

    /**
     * Check if a file with this hash already exists before uploading.
     * Returns matching document info if duplicate found.
     */
    @PostMapping("/check-duplicate")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN','EDITOR','CONTRIBUTOR')")
    fun checkDuplicate(@RequestParam("file") file: MultipartFile): ApiResponse<Map<String, Any?>> {
        val bytes = file.bytes
        val hash = computeSha256(bytes)

        val matches = jdbcTemplate.queryForList(
            """
            SELECT dv.content_hash, dv.document_id, dv.version_number, dv.file_name,
                   d.title, d.status
            FROM doc.document_version dv
            JOIN doc.document d ON d.id = dv.document_id
            WHERE dv.content_hash = ? AND d.deleted_at IS NULL
            ORDER BY dv.created_at DESC
            LIMIT 1
            """,
            hash,
        )

        return if (matches.isNotEmpty()) {
            val match = matches[0]
            ApiResponse(
                data = mapOf(
                    "duplicate" to true,
                    "contentHash" to hash,
                    "existingDocumentId" to match["document_id"]?.toString(),
                    "existingTitle" to match["title"]?.toString(),
                    "existingVersion" to match["version_number"]?.toString(),
                    "existingStatus" to match["status"]?.toString(),
                )
            )
        } else {
            ApiResponse(data = mapOf("duplicate" to false, "contentHash" to hash))
        }
    }

    @PostMapping("/{docId}/versions/{versionId}/upload")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN','EDITOR','CONTRIBUTOR')")
    @Transactional
    fun uploadFile(
        @PathVariable docId: UUID,
        @PathVariable versionId: UUID,
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<Map<String, Any>> {
        val version = versionRepo.findById(versionId).orElseThrow {
            NoSuchElementException("Version not found: $versionId")
        }

        val bytes = file.bytes
        val contentHash = computeSha256(bytes)

        // Content type resolution: clients (including browsers and CLI
        // tools) often send generic application/octet-stream for uploads,
        // which defeats downstream preview routing that needs to know
        // whether the file is a PDF, Office doc, or image. Use Tika to
        // detect from magic bytes + filename when the claimed type is
        // missing or generic; trust the client otherwise.
        val claimedType = file.contentType
        val contentType = if (claimedType.isNullOrBlank() || claimedType == "application/octet-stream") {
            val detected = try {
                tika.detect(bytes.inputStream(), file.originalFilename ?: "unknown")
            } catch (ex: Exception) {
                log.warn("Tika MIME detection failed for '{}': {}", file.originalFilename, ex.message)
                "application/octet-stream"
            }
            log.debug("Client claimed {}, Tika detected {}", claimedType, detected)
            detected
        } else {
            claimedType
        }

        // Persist the original bytes to MinIO. This is the first step —
        // before Tika, before metadata — so that a successful storage write
        // means the bytes are safe even if later steps (AI queue, DB write)
        // throw. On failure we fail the request immediately: a document
        // version row without its bytes is a broken state we refuse to
        // create. The caller can retry the upload.
        val storageKey = storage.originalKey(docId, versionId)
        try {
            storage.put(storageKey, bytes, contentType)
            uploadsSucceeded.increment()
        } catch (ex: Exception) {
            uploadsFailed.increment()
            log.error("Failed to store '{}' in MinIO at key {}: {}", file.originalFilename, storageKey, ex.message)
            throw IllegalStateException("Storage write failed: ${ex.message}", ex)
        }

        // Extract text with Tika for full-text search and AI processing.
        // Tika failures are non-fatal — we already have the bytes safely
        // in MinIO, so the worst case is a doc that's previewable but
        // not searchable until someone reruns extraction.
        log.info("Extracting text from '{}' ({} bytes, type={})", file.originalFilename, file.size, contentType)
        val extractedText = try {
            tika.parseToString(bytes.inputStream())
        } catch (ex: Exception) {
            log.warn("Tika text extraction failed for '{}': {}", file.originalFilename, ex.message)
            ""
        }
        log.info("Tika extracted {} chars from '{}'", extractedText.length, file.originalFilename)

        // Store extracted text in version metadata
        val metadata = metadataRepo.findByVersionId(versionId) ?: VersionMetadata(version = version)
        metadata.extractedText = extractedText
        metadataRepo.save(metadata)

        // Update version with file size, content hash, storage key, and mime type.
        // storageKey + contentType are new in Pass 4.1 — they power preview.
        version.fileSizeBytes = file.size
        version.contentHash = contentHash
        version.storageKey = storageKey
        version.contentType = contentType
        versionRepo.save(version)

        // Publish AI task with the extracted text
        if (natsService.isConnected() && extractedText.isNotBlank()) {
            val task = AiTaskMessage(
                taskType = "FULL_ANALYSIS",
                documentId = docId.toString(),
                versionId = versionId.toString(),
                storageKey = version.storageKey,
                extractedText = extractedText,
                mimeType = file.contentType ?: "application/octet-stream",
            )
            natsService.publishAiTask(task)
            log.info("AI task published for version {} with {} chars", versionId, extractedText.length)
        }

        return ApiResponse(
            data = mapOf(
                "versionId" to versionId.toString(),
                "fileName" to (file.originalFilename ?: "unknown"),
                "fileSize" to file.size,
                "contentHash" to contentHash,
                "extractedTextLength" to extractedText.length,
                "aiTaskSubmitted" to natsService.isConnected(),
            )
        )
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
