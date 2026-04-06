package dev.kosha.workflow.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Builds the data object that JSON Logic expressions evaluate against.
 *
 * The workflow engine calls [buildContext] when a step with a non-null
 * `conditionJson` is about to be promoted. The result is a flat-ish
 * `Map<String, Any?>` with these top-level keys:
 *
 * ```
 * {
 *   "document": {
 *     "category": "Policy",
 *     "department": "Finance",
 *     "title": "Travel Expense Policy",
 *     "status": "IN_REVIEW",
 *     "tags": ["travel", "expense"]
 *   },
 *   "metadata": {
 *     "amounts": [15000, 3500],
 *     "currency": "GBP",
 *     "effective_date": "2026-03-15",
 *     "parties": ["Acme Corp", "Widget Ltd"],
 *     "jurisdiction": "England and Wales",
 *     "document_number": "DOC-00042"
 *   }
 * }
 * ```
 *
 * ## Why native queries
 *
 * `kosha-workflow` does not depend on `kosha-document`. The entities
 * for Document, DocumentVersion, DocumentCategory, and taxonomy terms
 * are not on the classpath. Rather than adding a cross-module dependency
 * (which would create a cycle because `kosha-document` already listens
 * on workflow events from `kosha-common`), we fetch the data via native
 * SQL. This keeps the module graph clean and the context builder
 * self-contained.
 *
 * ## Caching
 *
 * No caching — the context is built once per step evaluation at
 * submission or advancement time, which is an infrequent event.
 * Building it takes 2–3 SQL queries, all against indexed columns.
 */
@Component
class WorkflowConditionContext(
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Build the JSON Logic data context for a given document + version.
     * Returns a map suitable for passing to `JsonLogic.apply(rule, data)`.
     */
    fun buildContext(documentId: UUID, versionId: UUID): Map<String, Any?> {
        val docInfo = loadDocumentInfo(documentId)
        val metadata = loadExtractedMetadata(versionId)

        return mapOf(
            "document" to docInfo,
            "metadata" to metadata,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadDocumentInfo(documentId: UUID): Map<String, Any?> {
        return try {
            val row = entityManager.createNativeQuery(
                """
                SELECT d.title, d.status, dept.name AS dept_name,
                       cat.name AS cat_name
                FROM doc.document d
                JOIN ident.department dept ON dept.id = d.department_id
                LEFT JOIN doc.document_category cat ON cat.id = d.category_id
                WHERE d.id = :docId
                """.trimIndent(),
            )
                .setParameter("docId", documentId)
                .singleResult as Array<Any?>

            mapOf(
                "title" to (row[0] as? String),
                "status" to (row[1] as? String),
                "department" to (row[2] as? String),
                "category" to (row[3] as? String),
            )
        } catch (ex: Exception) {
            log.warn("Failed to load document info for condition context: {}", ex.message)
            emptyMap()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadExtractedMetadata(versionId: UUID): Map<String, Any?> {
        return try {
            val raw = entityManager.createNativeQuery(
                """
                SELECT extracted_metadata::text
                FROM doc.document_version
                WHERE id = :versionId AND extracted_metadata IS NOT NULL
                """.trimIndent(),
            )
                .setParameter("versionId", versionId)
                .resultList
                .firstOrNull() as? String

            if (raw.isNullOrBlank()) return emptyMap()
            objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>
        } catch (ex: Exception) {
            log.warn("Failed to load extracted metadata for condition context: {}", ex.message)
            emptyMap()
        }
    }
}
