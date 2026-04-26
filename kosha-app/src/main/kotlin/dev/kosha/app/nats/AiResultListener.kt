package dev.kosha.app.nats

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.kosha.document.entity.VersionMetadata
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.document.repository.VersionMetadataRepository
import dev.kosha.search.DocumentIndexData
import dev.kosha.search.OpenSearchService
import io.nats.client.Nats
import io.nats.client.Options
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.OffsetDateTime
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
    /** LLM-suggested synonyms for this candidate (Stage 2 propose-aliases). */
    val aliases: List<String> = emptyList(),
    /**
     * Confidence of the original unmatched keyword that triggered this candidate.
     * Used to populate the document_classification row that links the source
     * document to the new candidate term, so admins reviewing the candidate
     * can see which document(s) it came from.
     */
    val confidence: Double? = null,
)

data class OcrResult(
    val versionId: String,
    val documentId: String,
    val ocrStorageKey: String,
    val ocrFileSizeBytes: Long,
    val language: String,
    val ocrApplied: Boolean,
)

data class MetadataResult(
    val versionId: String,
    val documentId: String,
    val extractedMetadata: Map<String, Any>,
)

@Component
class AiResultListener(
    @Value("\${kosha.nats.url:nats://localhost:4222}") private val natsUrl: String,
    private val objectMapper: ObjectMapper,
    private val versionRepo: DocumentVersionRepository,
    private val metadataRepo: VersionMetadataRepository,
    private val txTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val searchService: OpenSearchService,
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

                dispatcher.subscribe("ai.ocr.completed") { msg ->
                    try {
                        val result = objectMapper.readValue<OcrResult>(msg.data)
                        log.info(
                            "Received OCR result for version {} (key={})",
                            result.versionId, result.ocrStorageKey,
                        )
                        saveOcrResult(result)
                    } catch (ex: Exception) {
                        log.error("Error processing OCR result: {}", ex.message, ex)
                    }
                }

                dispatcher.subscribe("ai.metadata.extracted") { msg ->
                    try {
                        val result = objectMapper.readValue<MetadataResult>(msg.data)
                        log.info(
                            "Received extracted metadata for version {} ({} fields)",
                            result.versionId, result.extractedMetadata.size,
                        )
                        saveMetadataResult(result)
                    } catch (ex: Exception) {
                        log.error("Error processing metadata result: {}", ex.message, ex)
                    }
                }

                log.info("AI result listener started on NATS (core subscription)")
            } catch (ex: Exception) {
                log.warn("Could not start AI result listener: {}", ex.message)
            }
        }
    }

    /**
     * Persist the OCR result from the AI sidecar. Sets the
     * `ocr_storage_key`, `ocr_applied`, and `ocr_language` fields
     * on the version row so the preview endpoint can prefer the
     * OCR'd PDF for rendering.
     */
    private fun saveOcrResult(result: OcrResult) {
        txTemplate.executeWithoutResult { _ ->
            val versionId = UUID.fromString(result.versionId)
            val version = versionRepo.findById(versionId).orElse(null) ?: run {
                log.warn("Version not found for OCR result: {}", result.versionId)
                return@executeWithoutResult
            }
            version.ocrStorageKey = result.ocrStorageKey
            version.ocrApplied = true
            version.ocrLanguage = result.language
            versionRepo.save(version)
            log.info(
                "Saved OCR result for version {} (key={}, lang={})",
                result.versionId, result.ocrStorageKey, result.language,
            )
        }
    }

    /**
     * Persist the structured metadata extracted by the AI sidecar's spaCy
     * NER pipeline. The JSONB column stores the raw JSON object so the
     * conditional workflow engine can evaluate JSON Logic expressions
     * against it without an intermediate deserialisation step in the hot
     * path.
     */
    private fun saveMetadataResult(result: MetadataResult) {
        txTemplate.executeWithoutResult { _ ->
            val versionId = UUID.fromString(result.versionId)
            val version = versionRepo.findById(versionId).orElse(null) ?: run {
                log.warn("Version not found for metadata result: {}", result.versionId)
                return@executeWithoutResult
            }
            version.extractedMetadata = result.extractedMetadata
            versionRepo.save(version)
            log.info(
                "Saved extracted metadata for version {} ({} fields)",
                result.versionId, result.extractedMetadata.size,
            )
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
        val documentId = UUID.fromString(result.documentId)
        txTemplate.executeWithoutResult { _ ->
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
        // Re-index the document AFTER the tx commits so OpenSearch sees the new
        // classifications. The upload-time index call leaves `tags` empty, so
        // until this fires, search-by-classified-term returns nothing.
        reindexDocumentWithTags(documentId)
    }

    /**
     * Push the document back into OpenSearch with its current classifications
     * inlined as `tags`. Same DocumentIndexData shape as the upload-path index
     * call — this is just the "tags now exist" refresh.
     *
     * Failure is logged and swallowed: search staleness is preferable to a
     * NATS message nak that would re-trigger the whole AI pipeline.
     */
    private fun reindexDocumentWithTags(documentId: UUID) {
        try {
            @Suppress("UNCHECKED_CAST")
            val docRow = jdbcTemplate.queryForMap(
                """
                SELECT d.title, d.description, d.status, d.doc_number,
                       dept.name AS dept_name, dept.id AS dept_id,
                       cat.name AS cat_name,
                       owner.display_name AS owner_name,
                       d.created_at
                FROM doc.document d
                JOIN ident.department dept ON dept.id = d.department_id
                LEFT JOIN doc.document_category cat ON cat.id = d.category_id
                LEFT JOIN ident.user_profile owner ON owner.id = d.primary_owner_id
                WHERE d.id = ?
                """.trimIndent(),
                documentId,
            )
            val tags = jdbcTemplate.queryForList(
                """
                SELECT t.label
                FROM tax.document_classification dc
                JOIN tax.taxonomy_term t ON t.id = dc.term_id
                WHERE dc.document_id = ?
                """.trimIndent(),
                String::class.java,
                documentId,
            )
            val latestText = jdbcTemplate.queryForList(
                """
                SELECT m.extracted_text
                FROM doc.document_version v
                JOIN doc.version_metadata m ON m.version_id = v.id
                WHERE v.document_id = ?
                  AND m.extracted_text IS NOT NULL
                ORDER BY v.created_at DESC
                LIMIT 1
                """.trimIndent(),
                String::class.java,
                documentId,
            ).firstOrNull().orEmpty()

            searchService.indexDocument(
                DocumentIndexData(
                    id = documentId.toString(),
                    title = docRow["title"] as? String ?: "",
                    description = docRow["description"] as? String,
                    content = latestText.take(100_000),
                    departmentName = docRow["dept_name"] as? String ?: "",
                    departmentId = (docRow["dept_id"] as? UUID)?.toString() ?: "",
                    status = docRow["status"] as? String ?: "",
                    docNumber = docRow["doc_number"] as? String,
                    categoryName = docRow["cat_name"] as? String,
                    primaryOwnerName = docRow["owner_name"] as? String,
                    tags = tags,
                    createdAt = (docRow["created_at"] as? OffsetDateTime)?.toString(),
                ),
            )
            log.debug("Re-indexed document {} with {} tag(s)", documentId, tags.size)
        } catch (ex: Exception) {
            log.warn("Failed to re-index document {} with tags: {}", documentId, ex.message)
        }
    }

    private fun saveCandidates(result: CandidateResult) {
        val documentId = UUID.fromString(result.documentId)
        txTemplate.executeWithoutResult { _ ->
            for (c in result.candidates) {
                val normalized = c.label.lowercase().trim()
                // Check if term already exists (exact match on normalized label)
                val existingId = jdbcTemplate.queryForList(
                    "SELECT id FROM tax.taxonomy_term WHERE normalized_label = ?",
                    java.util.UUID::class.java,
                    normalized,
                ).firstOrNull()

                val termId = existingId ?: run {
                    val newId = java.util.UUID.randomUUID()
                    jdbcTemplate.update(
                        """
                        INSERT INTO tax.taxonomy_term (id, label, normalized_label, description, source, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'CANDIDATE', now(), now())
                        """,
                        newId, c.label, normalized, c.description, c.source,
                    )
                    log.debug("Created CANDIDATE taxonomy term: '{}'", c.label)
                    newId
                }

                // Link the source document to this candidate term. Without this row
                // the term-detail page shows '0 documents' for every candidate even
                // though it was extracted from a real document, and admins reviewing
                // the candidate have no way to see what evidence triggered it.
                // Confidence floor of 0.5 if the sidecar didn't carry one through.
                val classificationConfidence = (c.confidence ?: 0.5).coerceIn(0.0, 1.0)
                jdbcTemplate.update(
                    """
                    INSERT INTO tax.document_classification (id, document_id, term_id, confidence, source, created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'AI', now(), now())
                    ON CONFLICT (document_id, term_id) DO UPDATE SET confidence = EXCLUDED.confidence, updated_at = now()
                    """,
                    documentId, termId, BigDecimal.valueOf(classificationConfidence),
                )

                // Persist Stage-2 LLM-suggested synonyms. Skipped if the alias matches
                // the canonical label or already exists for this term.
                for (rawAlias in c.aliases) {
                    val aliasLabel = rawAlias.trim()
                    if (aliasLabel.isEmpty()) continue
                    val normalizedAlias = aliasLabel.lowercase()
                    if (normalizedAlias == normalized) continue
                    jdbcTemplate.update(
                        """
                        INSERT INTO tax.taxonomy_term_alias
                            (id, term_id, alias_label, normalized_alias_label, source, created_at, updated_at)
                        VALUES (gen_random_uuid(), ?, ?, ?, 'AI_SUGGESTED', now(), now())
                        ON CONFLICT (term_id, normalized_alias_label) DO NOTHING
                        """,
                        termId, aliasLabel, normalizedAlias,
                    )
                }
            }
            log.info("Processed {} taxonomy candidates for document {}", result.candidates.size, result.documentId)
        }
        // Refresh OpenSearch so the new candidate-term tags become searchable
        // alongside the ACTIVE-term classifications already carried by the doc.
        reindexDocumentWithTags(documentId)
    }
}
