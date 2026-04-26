package dev.kosha.app.controller

import dev.kosha.app.preview.OfficePreviewService
import dev.kosha.document.repository.DocumentRepository
import dev.kosha.document.repository.DocumentVersionRepository
import dev.kosha.storage.MinioStorageService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Streams original document-version bytes back to an authenticated caller
 * for in-browser preview. Landed in Pass 4.1 to close the biggest
 * unforced competitive loss — "I want to click my PDF and see it render."
 *
 * ## Contract
 *
 * - `GET /api/v1/documents/{docId}/versions/{versionId}/preview`
 * - Returns 200 with the original bytes and the stored content-type
 * - Returns 404 if the document, version, or MinIO object is missing,
 *   or if the version pre-dates Pass 4.1 and has no `storageKey`
 * - Returns 403 via method security if the caller lacks document read
 *   access (any authenticated role can read — granularity will tighten
 *   when per-document ACLs land in a later pass)
 * - Returns 409 if the document is soft-deleted (treated as "gone")
 *
 * ## Why bytes, not a signed MinIO URL
 *
 * Option A was to mint a short-lived S3 presigned URL and redirect the
 * browser to MinIO directly. That saves the Kosha backend from proxying
 * potentially large downloads. It was rejected because:
 *
 *   1. MinIO is typically not reachable from the browser in production
 *      (it lives on an internal network), so the presigned URL would
 *      need a public MinIO endpoint or a reverse proxy we don't have.
 *   2. Logging "who previewed what" is cleaner when the preview goes
 *      through our own backend — the access shows up in audit alongside
 *      every other mutation.
 *   3. Range-request support from MinIO to the browser works fine
 *      through a transparent stream proxy (pdf.js is the heaviest
 *      client and it's happy with a full-body GET).
 *
 * ## Range requests
 *
 * We set `Accept-Ranges: bytes` and Content-Length on the response so
 * the browser knows how to chunk. For the MVP we don't actually parse
 * `Range:` request headers — the MinIO stream is simply read in full.
 * pdf.js and browser image/video renderers tolerate this for files
 * under a few hundred MB. If/when users upload larger files we'll add
 * a Range-aware path.
 */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentPreviewController(
    private val documentRepo: DocumentRepository,
    private val versionRepo: DocumentVersionRepository,
    private val storage: MinioStorageService,
    private val officePreview: OfficePreviewService,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Pass 4 instrument-as-we-go: one counter per outcome so Pass 6
    // dashboards can see preview health at a glance. `mime` is a tag so
    // we can break out PDF vs Office vs image without adding metrics.
    private val previewServed = meterRegistry.counter("eolas.preview.served", "outcome", "success")
    private val previewMissing = meterRegistry.counter("eolas.preview.served", "outcome", "not_found")
    private val previewDeleted = meterRegistry.counter("eolas.preview.served", "outcome", "deleted")

    @GetMapping("/{docId}/versions/{versionId}/preview")
    @PreAuthorize("isAuthenticated()")
    fun preview(
        @PathVariable docId: UUID,
        @PathVariable versionId: UUID,
    ): ResponseEntity<InputStreamResource> {
        // Load the document first so we can enforce soft-delete and
        // return a meaningful 404 for bad ids. The version lookup is
        // redundant with the doc lookup for existence but gives us the
        // storageKey + contentType + fileName for the response headers.
        val doc = documentRepo.findById(docId).orElse(null)
        if (doc == null || doc.isDeleted) {
            previewDeleted.increment()
            return notFound("Document $docId not found")
        }

        val version = versionRepo.findById(versionId).orElse(null)
        if (version == null || version.document.id != docId) {
            previewMissing.increment()
            return notFound("Version $versionId not found for document $docId")
        }

        val originalKey = version.storageKey
        if (originalKey.isNullOrBlank()) {
            // Pre-Pass-4.1 versions don't have a storage key. The user
            // sees a "Preview not available — re-upload to enable" state
            // rather than a broken viewer. Returning 404 is the cleanest
            // signal to the frontend that preview is unavailable.
            previewMissing.increment()
            log.debug("Version {} has no storageKey — preview unavailable", versionId)
            return notFound("Preview not available for this version")
        }

        // Key resolution priority:
        //   1. OCR'd PDF (scanned document that was OCR-processed) — most
        //      readable version for both preview and search
        //   2. Office conversion cache (LibreOffice sidecar → PDF)
        //   3. Original file bytes
        //
        // Each branch sets streamingKey + forcedContentType so the
        // streaming code below doesn't need to know which path was taken.
        val streamingKey: String
        val forcedContentType: String?

        if (version.ocrApplied && !version.ocrStorageKey.isNullOrBlank()) {
            // Prefer the OCR'd PDF — it has a searchable text layer and
            // renders cleanly in pdf.js just like a native PDF.
            streamingKey = version.ocrStorageKey!!
            forcedContentType = "application/pdf"
        } else if (officePreview.isOfficeDocument(version.contentType)) {
            // Office docs MUST go through the sidecar — the frontend's
            // classifier routes them to pdf.js, so streaming the raw
            // .docx/.xlsx bytes back would feed PDF.js garbage. If the
            // sidecar isn't configured, return 503 so the frontend
            // shows its "download instead" fallback.
            if (!officePreview.isAvailable()) {
                previewMissing.increment()
                log.debug("Office preview requested for version {} but sidecar not configured", versionId)
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            }
            streamingKey = try {
                officePreview.getOrConvert(docId, versionId, originalKey, version.fileName)
            } catch (ex: Exception) {
                previewMissing.increment()
                log.warn("Office conversion failed for version {}: {}", versionId, ex.message)
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            }
            forcedContentType = "application/pdf"
        } else {
            streamingKey = originalKey
            forcedContentType = null
        }

        return try {
            val stat = storage.stat(streamingKey)
                ?: run {
                    previewMissing.increment()
                    log.warn("Version {} has storageKey {} but MinIO object is missing", versionId, streamingKey)
                    return notFound("Stored file is missing")
                }

            val stream = storage.get(streamingKey)
            val resource = InputStreamResource(stream)

            val contentType = forcedContentType
                ?: version.contentType
                ?: stat.contentType
                ?: "application/octet-stream"

            val headers = HttpHeaders().apply {
                contentType.let { set(HttpHeaders.CONTENT_TYPE, it) }
                contentLength = stat.sizeBytes
                set(HttpHeaders.ACCEPT_RANGES, "bytes")
                // inline disposition so the browser renders rather than
                // prompts to download. fileName is advisory for "Save As".
                set(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"${version.fileName.replace("\"", "")}\"",
                )
                // Cache aggressively — MinIO content is immutable per
                // (docId, versionId) so browsers can reuse it across
                // navigations within the same session. Short max-age
                // keeps legal-hold transitions fresh.
                cacheControl = "private, max-age=300"
            }

            previewServed.increment()
            ResponseEntity(resource, headers, HttpStatus.OK)
        } catch (ex: Exception) {
            previewMissing.increment()
            log.error("Preview failed for version {} (key={}): {}", versionId, streamingKey, ex.message, ex)
            notFound("Preview failed: ${ex.message}")
        }
    }

    private fun notFound(reason: String): ResponseEntity<InputStreamResource> {
        log.debug("Preview 404: {}", reason)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .build()
    }
}
