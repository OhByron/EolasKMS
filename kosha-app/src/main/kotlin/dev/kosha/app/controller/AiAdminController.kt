package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
    var llmModel: String = "llama3:8b",

    @Column(name = "llm_api_key", length = 1000)
    var llmApiKey: String? = null,

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
    val llmModel: String = "llama3:8b",
    val llmApiKey: String? = null,
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

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
class AiAdminController(
    private val configRepo: AiConfigRepository,
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
    fun getConfigInternal(): ApiResponse<AiConfigDto> =
        ApiResponse(data = loadConfig().toDto())

    @PutMapping("/config")
    @Transactional
    fun updateConfig(@RequestBody dto: AiConfigDto): ApiResponse<AiConfigDto> {
        val entity = loadConfig()

        entity.llmProvider = dto.llmProvider
        entity.llmEndpoint = dto.llmEndpoint
        entity.llmModel = dto.llmModel

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
    fun reprocess(@PathVariable documentId: UUID) =
        ApiResponse(data = mapOf("status" to "queued", "documentId" to documentId.toString()))

    private fun AiConfigEntity.toDto() = AiConfigDto(
        llmProvider = llmProvider,
        llmEndpoint = llmEndpoint,
        llmModel = llmModel,
        llmApiKey = llmApiKey,
        summarizationEnabled = summarizationEnabled,
        keywordExtractionEnabled = keywordExtractionEnabled,
        classificationEnabled = classificationEnabled,
        relationshipDetectionEnabled = relationshipDetectionEnabled,
        ocrEnabled = ocrEnabled,
    )
}
