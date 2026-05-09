package dev.kosha.app.controller

import dev.kosha.app.nats.AiTaskMessage
import dev.kosha.app.nats.NatsService
import dev.kosha.common.api.ApiResponse
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "ai_config")
class AiConfigEntity(
    @Id
    val id: String = "default",

    @Column(name = "llm_provider", nullable = false, length = 50)
    var llmProvider: String = "ollama",

    @Column(name = "llm_endpoint", nullable = false, length = 500)
    var llmEndpoint: String = "http://localhost:11434",

    @Column(name = "llm_model", nullable = false, length = 200)
    var llmModel: String = "gemma4:26b",

    @Column(name = "llm_api_key", length = 1000)
    var llmApiKey: String? = null,

    @Column(name = "llm_num_ctx", nullable = false)
    var llmNumCtx: Int = 16384,

    @Column(name = "summarization_enabled", nullable = false)
    var summarizationEnabled: Boolean = true,

    @Column(name = "keyword_extraction_enabled", nullable = false)
    var keywordExtractionEnabled: Boolean = true,

    @Column(name = "classification_enabled", nullable = false)
    var classificationEnabled: Boolean = true,

    @Column(name = "relationship_detection_enabled", nullable = false)
    var relationshipDetectionEnabled: Boolean = true,

    @Column(name = "ocr_enabled", nullable = false)
    var ocrEnabled: Boolean = false,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Repository
interface AiConfigRepository : JpaRepository<AiConfigEntity, String>

data class AiConfigDto(
    val llmProvider: String = "ollama",
    val llmEndpoint: String = "http://localhost:11434",
    val llmModel: String = "gemma4:26b",
    val llmApiKey: String? = null,
    val llmNumCtx: Int = 16384,
    val summarizationEnabled: Boolean = true,
    val keywordExtractionEnabled: Boolean = true,
    val classificationEnabled: Boolean = true,
    val relationshipDetectionEnabled: Boolean = true,
    val ocrEnabled: Boolean = false,
)

data class AiStatsDto(
    val totalProcessed: Long = 0,
    val totalPending: Long = 0,
    val averageConfidence: Double = 0.0,
    val lastProcessedAt: OffsetDateTime? = null,
)

data class ReprocessSummary(
    val scope: String,
    val queued: Int,
    val skipped: Int,
)

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
class AiAdminController(
    private val configRepo: AiConfigRepository,
    private val natsService: NatsService,
    private val jdbcTemplate: JdbcTemplate,
) {

    private fun loadConfig(): AiConfigEntity =
        configRepo.findById("default").orElseGet {
            configRepo.save(AiConfigEntity())
        }

    @GetMapping("/config")
    fun getConfig(): ApiResponse<AiConfigDto> {
        val entity = loadConfig()
        val masked = entity.llmApiKey?.let { key ->
            if (key.length > 8) "****${key.takeLast(4)}" else if (key.isNotEmpty()) "****" else null
        }
        return ApiResponse(data = entity.toDto().copy(llmApiKey = masked))
    }

    @GetMapping("/config/internal")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'SERVICE')")
    fun getConfigInternal(): ApiResponse<AiConfigDto> =
        ApiResponse(data = loadConfig().toDto())

    @PutMapping("/config")
    @Transactional
    fun updateConfig(@RequestBody dto: AiConfigDto): ApiResponse<AiConfigDto> {
        val entity = loadConfig()

        entity.llmProvider = dto.llmProvider
        entity.llmEndpoint = dto.llmEndpoint
        entity.llmModel = dto.llmModel
        entity.llmNumCtx = dto.llmNumCtx

        // Only update the key if it's not masked
        if (dto.llmApiKey != null && !dto.llmApiKey.startsWith("****")) {
            entity.llmApiKey = dto.llmApiKey
        }

        entity.summarizationEnabled = dto.summarizationEnabled
        entity.keywordExtractionEnabled = dto.keywordExtractionEnabled
        entity.classificationEnabled = dto.classificationEnabled
        entity.relationshipDetectionEnabled = dto.relationshipDetectionEnabled
        entity.ocrEnabled = dto.ocrEnabled
        entity.updatedAt = OffsetDateTime.now()

        configRepo.save(entity)

        val masked = entity.llmApiKey?.let { key ->
            if (key.length > 8) "****${key.takeLast(4)}" else if (key.isNotEmpty()) "****" else null
        }
        return ApiResponse(data = entity.toDto().copy(llmApiKey = masked))
    }

    @GetMapping("/stats")
    fun getStats() = ApiResponse(data = AiStatsDto())

    @PostMapping("/reprocess/{documentId}")
    fun reprocess(@PathVariable documentId: UUID): ApiResponse<Map<String, Any>> {
        val queued = queueLatestVersion(documentId)
        return ApiResponse(
            data = mapOf(
                "status" to if (queued) "queued" else "skipped",
                "documentId" to documentId.toString(),
            )
        )
    }

    /**
     * Queue an AI task for the latest version of every document.
     *
     * Used after a pipeline change (e.g. Gemma-replaces-spaCy) to refresh
     * existing documents with the new keyword/classification logic. Docs
     * without stored extracted text are skipped — text is read from
     * `doc.version_metadata.extracted_text` rather than re-running Tika.
     *
     * `scope=all`         → every document
     * `scope=unprocessed` → only documents without any current classification
     */
    @PostMapping("/reprocess")
    fun reprocessAll(
        @RequestParam(name = "scope", defaultValue = "all") scope: String,
    ): ApiResponse<ReprocessSummary> {
        val sql = when (scope) {
            "unprocessed" -> """
                SELECT d.id
                FROM doc.document d
                WHERE NOT EXISTS (
                    SELECT 1 FROM tax.document_classification dc WHERE dc.document_id = d.id
                )
            """.trimIndent()
            else -> "SELECT id FROM doc.document"
        }
        val documentIds = jdbcTemplate.queryForList(sql, UUID::class.java)

        var queued = 0
        var skipped = 0
        for (id in documentIds) {
            if (queueLatestVersion(id)) queued++ else skipped++
        }
        return ApiResponse(data = ReprocessSummary(scope = scope, queued = queued, skipped = skipped))
    }

    /** Returns true if a task was published, false if no usable version/text was found. */
    private fun queueLatestVersion(documentId: UUID): Boolean {
        // Pick the most recent version that has stored extracted text. Skipping
        // versions with no text avoids the sidecar receiving "[Binary document: ...]"
        // placeholders and noisily failing.
        val rows = jdbcTemplate.queryForList(
            """
            SELECT v.id AS version_id,
                   v.storage_key,
                   v.content_type,
                   m.extracted_text
            FROM doc.document_version v
            JOIN doc.version_metadata m ON m.version_id = v.id
            WHERE v.document_id = ?
              AND m.extracted_text IS NOT NULL
              AND length(m.extracted_text) > 50
            ORDER BY v.created_at DESC
            LIMIT 1
            """.trimIndent(),
            documentId,
        )
        if (rows.isEmpty()) return false

        val row = rows.first()
        val task = AiTaskMessage(
            taskType = "FULL_ANALYSIS",
            documentId = documentId.toString(),
            versionId = (row["version_id"] as UUID).toString(),
            storageKey = row["storage_key"] as String?,
            extractedText = row["extracted_text"] as String?,
            mimeType = row["content_type"] as String? ?: "application/octet-stream",
        )
        natsService.publishAiTask(task)
        return true
    }

    private fun AiConfigEntity.toDto() = AiConfigDto(
        llmProvider = llmProvider,
        llmEndpoint = llmEndpoint,
        llmModel = llmModel,
        llmApiKey = llmApiKey,
        llmNumCtx = llmNumCtx,
        summarizationEnabled = summarizationEnabled,
        keywordExtractionEnabled = keywordExtractionEnabled,
        classificationEnabled = classificationEnabled,
        relationshipDetectionEnabled = relationshipDetectionEnabled,
        ocrEnabled = ocrEnabled,
    )
}
