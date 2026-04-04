package dev.kosha.app.nats

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.kosha.document.entity.VersionMetadata
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.document.repository.VersionMetadataRepository
import io.nats.client.Nats
import io.nats.client.Options
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.concurrent.thread

data class SummaryResult(
    val versionId: String,
    val summary: String,
    val confidence: Double,
)

data class KeywordResult(
    val versionId: String,
    val keywords: List<ExtractedKeywordDto>,
)

data class ExtractedKeywordDto(
    val keyword: String,
    val frequency: Int,
    val confidence: Double,
)

data class ClassificationResult(
    val documentId: String,
    val classifications: List<ClassificationDto>,
)

data class ClassificationDto(
    val documentId: String,
    val termId: String,
    val confidence: Double,
    val source: String,
)

data class CandidateResult(
    val documentId: String,
    val candidates: List<CandidateDto>,
)

data class CandidateDto(
    val label: String,
    val description: String?,
    val source: String,
)

@Component
class AiResultListener(
    @Value("\${kosha.nats.url:nats://localhost:4222}") private val natsUrl: String,
    private val objectMapper: ObjectMapper,
    private val versionRepo: DocumentVersionRepository,
    private val metadataRepo: VersionMetadataRepository,
    private val txTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun startListening() {
        thread(isDaemon = true, name = "nats-ai-result-listener") {
            try {
                val opts = Options.Builder()
                    .server(natsUrl)
                    .reconnectWait(java.time.Duration.ofSeconds(2))
                    .maxReconnects(-1)
                    .build()
                val conn = Nats.connect(opts)

                val dispatcher = conn.createDispatcher()

                dispatcher.subscribe("ai.summary.completed") { msg ->
                    try {
                        val result = objectMapper.readValue<SummaryResult>(msg.data)
                        log.info("Received summary result for version {}", result.versionId)
                        saveSummary(result)
                    } catch (ex: Exception) {
                        log.error("Error processing summary result: {}", ex.message, ex)
                    }
                }

                dispatcher.subscribe("ai.keywords.extracted") { msg ->
                    try {
                        val result = objectMapper.readValue<KeywordResult>(msg.data)
                        log.info("Received {} keywords for version {}", result.keywords.size, result.versionId)
                        saveKeywords(result)
                    } catch (ex: Exception) {
                        log.error("Error processing keyword result: {}", ex.message, ex)
                    }
                }

                dispatcher.subscribe("ai.classification.completed") { msg ->
                    try {
                        val result = objectMapper.readValue<ClassificationResult>(msg.data)
                        log.info("Received {} classifications for document {}", result.classifications.size, result.documentId)
                        saveClassifications(result)
                    } catch (ex: Exception) {
                        log.error("Error processing classification result: {}", ex.message, ex)
                    }
                }

                dispatcher.subscribe("ai.taxonomy.candidates") { msg ->
                    try {
                        val result = objectMapper.readValue<CandidateResult>(msg.data)
                        log.info("Received {} taxonomy candidates for document {}", result.candidates.size, result.documentId)
                        saveCandidates(result)
                    } catch (ex: Exception) {
                        log.error("Error processing taxonomy candidates: {}", ex.message, ex)
                    }
                }

                log.info("AI result listener started on NATS (core subscription)")
            } catch (ex: Exception) {
                log.warn("Could not start AI result listener: {}", ex.message)
            }
        }
    }

    private fun saveSummary(result: SummaryResult) {
        txTemplate.executeWithoutResult { _ ->
            val versionId = UUID.fromString(result.versionId)
            val version = versionRepo.findById(versionId).orElse(null) ?: run {
                log.warn("Version not found for AI result: {}", result.versionId)
                return@executeWithoutResult
            }

            val existing = metadataRepo.findByVersionId(versionId)
            if (existing != null) {
                existing.summary = result.summary
                existing.aiConfidence = BigDecimal.valueOf(result.confidence)
                metadataRepo.save(existing)
                log.info("Updated summary for version {}", result.versionId)
            } else {
                metadataRepo.save(
                    VersionMetadata(
                        version = version,
                        summary = result.summary,
                        aiConfidence = BigDecimal.valueOf(result.confidence),
                    )
                )
                log.info("Created summary for version {}", result.versionId)
            }
        }
    }

    private fun saveKeywords(result: KeywordResult) {
        txTemplate.executeWithoutResult { _ ->
            val versionId = UUID.fromString(result.versionId)

            // Delete existing keywords for this version
            jdbcTemplate.update("DELETE FROM tax.extracted_keyword WHERE version_id = ?", versionId)

            // Insert new keywords
            for (kw in result.keywords) {
                jdbcTemplate.update(
                    "INSERT INTO tax.extracted_keyword (id, version_id, keyword, frequency, confidence, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, ?, now(), now())",
                    versionId, kw.keyword, kw.frequency, BigDecimal.valueOf(kw.confidence),
                )
            }
            log.info("Saved {} keywords for version {}", result.keywords.size, result.versionId)
        }
    }

    private fun saveClassifications(result: ClassificationResult) {
        txTemplate.executeWithoutResult { _ ->
            val documentId = UUID.fromString(result.documentId)

            for (c in result.classifications) {
                val termId = UUID.fromString(c.termId)
                // Upsert: insert or update confidence
                jdbcTemplate.update(
                    """
                    INSERT INTO tax.document_classification (id, document_id, term_id, confidence, source, created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, now(), now())
                    ON CONFLICT (document_id, term_id) DO UPDATE SET confidence = EXCLUDED.confidence, updated_at = now()
                    """,
                    documentId, termId, BigDecimal.valueOf(c.confidence), c.source,
                )
            }
            log.info("Saved {} classifications for document {}", result.classifications.size, result.documentId)
        }
    }

    private fun saveCandidates(result: CandidateResult) {
        txTemplate.executeWithoutResult { _ ->
            for (c in result.candidates) {
                val normalized = c.label.lowercase().trim()
                // Check if term already exists (exact match on normalized label)
                val exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tax.taxonomy_term WHERE normalized_label = ?",
                    Int::class.java,
                    normalized,
                ) ?: 0

                if (exists == 0) {
                    jdbcTemplate.update(
                        """
                        INSERT INTO tax.taxonomy_term (id, label, normalized_label, description, source, status, created_at, updated_at)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, 'CANDIDATE', now(), now())
                        """,
                        c.label, normalized, c.description, c.source,
                    )
                    log.debug("Created CANDIDATE taxonomy term: '{}'", c.label)
                }
            }
            log.info("Processed {} taxonomy candidates", result.candidates.size)
        }
    }
}
