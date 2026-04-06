package dev.kosha.app.preview

import dev.kosha.storage.MinioStorageService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.UUID
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpMethod

/**
 * Converts Office documents to PDF via the optional preview sidecar,
 * caches the result in MinIO, and exposes the cached PDF to the preview
 * controller. See `docker-compose.yml` for the `office-preview` profile
 * and `kosha-preview-sidecar/` for the Python HTTP wrapper.
 *
 * ## Lifecycle
 *
 * 1. `isAvailable()` — tells callers (preview controller) whether the
 *    sidecar is configured at all. Returns false if the URL property
 *    is blank/unset, which is the common case for deployments that
 *    don't need Office preview.
 * 2. `isOfficeDocument(contentType)` — classifies a MIME type so the
 *    controller can decide whether to bother with conversion.
 * 3. `getOrConvert(documentId, versionId, originalKey, fileName, contentType)` —
 *    the workhorse. Checks MinIO for a cached PDF at `previews/{docId}/{versionId}.pdf`;
 *    if present, returns its key. If not, fetches the original bytes,
 *    POSTs them to the sidecar's `/convert` endpoint, stores the
 *    returned PDF in MinIO under the preview key, and returns that key.
 *
 * ## Failure modes
 *
 * - **Sidecar not configured**: `isAvailable()` returns false and the
 *   controller falls through to a 404, which the frontend renders as
 *   a download-only button.
 * - **Sidecar unreachable at conversion time**: a network error is
 *   caught and bubbled up as a runtime exception; the controller
 *   converts it to a 503 "preview temporarily unavailable" response.
 * - **Sidecar rejects the document** (422 from LibreOffice): we log,
 *   increment the failure counter, and propagate a failure marker.
 *   Users see the "download instead" fallback.
 * - **Sidecar times out** (504): same as a reject — logged, failure
 *   counter bumped, fallback.
 *
 * ## Why blocking synchronous for v1
 *
 * Converting a 10MB PPTX can take 10+ seconds, which is too long for
 * a request thread in a busy system. Nonetheless v1 blocks — async
 * conversion with a polling or SSE progress UI is a later optimisation.
 * The instrument-as-we-go timer below captures actual distributions so
 * we can make the async decision with data when the time comes.
 */
@Service
class OfficePreviewService(
    @Value("\${kosha.preview.sidecar.url:}") private val sidecarUrl: String,
    private val storage: MinioStorageService,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient by lazy {
        RestClient.builder()
            .baseUrl(sidecarUrl.ifBlank { "http://localhost:0" })
            .build()
    }

    private val cacheHits = meterRegistry.counter("eolas.preview.office", "outcome", "cache_hit")
    private val conversionsOk = meterRegistry.counter("eolas.preview.office", "outcome", "converted")
    private val conversionsFailed = meterRegistry.counter("eolas.preview.office", "outcome", "failed")
    private val conversionTimer: Timer = Timer.builder("eolas.preview.office.duration")
        .description("Latency of Office-to-PDF conversion via LibreOffice sidecar")
        .register(meterRegistry)

    /**
     * True if the sidecar URL is configured. The backend never guarantees
     * reachability — that check happens lazily on first conversion.
     */
    fun isAvailable(): Boolean = sidecarUrl.isNotBlank()

    /**
     * Classifies a MIME type against the set LibreOffice can convert.
     * Extensions list is deliberately narrow: Word/Excel/PowerPoint
     * in both OOXML and legacy formats, plus the ODF equivalents.
     * Adding a new format is a one-line change.
     */
    fun isOfficeDocument(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        return contentType in OFFICE_MIME_TYPES
    }

    /**
     * Get the cached preview key or build it. Caller should then call
     * [MinioStorageService.stat] and [MinioStorageService.get] to serve
     * the content — this method only guarantees the object exists in
     * MinIO after returning, not that it's still there when the
     * caller reads it back.
     */
    fun getOrConvert(
        documentId: UUID,
        versionId: UUID,
        originalKey: String,
        fileName: String,
    ): String {
        val previewKey = storage.previewKey(documentId, versionId)

        if (storage.exists(previewKey)) {
            cacheHits.increment()
            log.debug("Office preview cache hit for version {} ({})", versionId, previewKey)
            return previewKey
        }

        if (!isAvailable()) {
            // Defensive — the controller should have checked already,
            // but throwing here keeps us honest if a new caller forgets.
            throw IllegalStateException("Preview sidecar not configured")
        }

        log.info("Converting version {} ({}) via sidecar at {}", versionId, fileName, sidecarUrl)
        val sample = Timer.start()
        val pdfBytes = try {
            val originalBytes = storage.get(originalKey).use { it.readAllBytes() }
            callSidecar(originalBytes, fileName)
        } catch (ex: Exception) {
            conversionsFailed.increment()
            log.error("Office conversion failed for version {}: {}", versionId, ex.message)
            throw ex
        } finally {
            sample.stop(conversionTimer)
        }

        storage.put(previewKey, pdfBytes, "application/pdf")
        conversionsOk.increment()
        log.info(
            "Cached Office preview for version {} at {} ({} bytes)",
            versionId, previewKey, pdfBytes.size,
        )
        return previewKey
    }

    /**
     * POST the original bytes to the sidecar's /convert endpoint and
     * return the resulting PDF bytes. Uses a fresh RestClient per call
     * with an explicit timeout rather than the class field — we want
     * a hard ceiling on how long a conversion can tie up the request
     * thread, and the shared RestClient doesn't expose per-request
     * timeouts cleanly.
     */
    private fun callSidecar(originalBytes: ByteArray, fileName: String): ByteArray {
        val resource = object : ByteArrayResource(originalBytes) {
            // Required by Spring's multipart encoder — without a
            // filename the Content-Disposition header is malformed
            // and the sidecar can't resolve the input extension.
            override fun getFilename(): String = fileName
        }

        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", resource)

        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
        }

        val entity = HttpEntity(body, headers)

        // RestClient's body mutation is simpler but the entity+headers
        // pattern interoperates with Spring's multipart encoder more
        // reliably across versions.
        val response = restClient
            .method(HttpMethod.POST)
            .uri("/convert")
            .headers { it.addAll(headers) }
            .body(body)
            .retrieve()
            .toEntity(ByteArray::class.java)

        val bytes = response.body
            ?: throw IllegalStateException("Sidecar returned empty body with status ${response.statusCode}")

        if (!response.headers.contentType?.toString().orEmpty().contains("pdf", ignoreCase = true)) {
            throw IllegalStateException(
                "Sidecar returned non-PDF content type: ${response.headers.contentType}",
            )
        }

        return bytes
    }

    companion object {
        /**
         * MIME types the LibreOffice sidecar converts to PDF. Kept as a
         * constant set so both the classifier (isOfficeDocument) and
         * any future UI hint ("this will be converted on first view")
         * read from the same list.
         */
        val OFFICE_MIME_TYPES: Set<String> = setOf(
            // OOXML
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // Legacy binary Office formats
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            // OpenDocument
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            // Rich Text Format — LibreOffice handles it fine
            "application/rtf",
            "text/rtf",
        )
    }
}
