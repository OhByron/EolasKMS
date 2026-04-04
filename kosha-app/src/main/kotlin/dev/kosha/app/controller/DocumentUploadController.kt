package dev.kosha.app.controller

import dev.kosha.app.nats.AiTaskMessage
import dev.kosha.app.nats.NatsService
import dev.kosha.common.api.ApiResponse
import dev.kosha.document.entity.VersionMetadata
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.document.repository.VersionMetadataRepository
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika()

    /**
     * Check if a file with this hash already exists before uploading.
     * Returns matching document info if duplicate found.
     */
    @PostMapping("/check-duplicate")
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

        // Extract text with Tika
        log.info("Extracting text from '{}' ({} bytes, type={})", file.originalFilename, file.size, file.contentType)
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

        // Update version with file size and content hash
        version.fileSizeBytes = file.size
        version.contentHash = contentHash
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
