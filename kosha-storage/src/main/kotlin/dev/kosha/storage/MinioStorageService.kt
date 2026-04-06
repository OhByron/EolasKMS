package dev.kosha.storage

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.UUID

/**
 * Object storage backed by MinIO (or any S3-compatible endpoint).
 *
 * Before Pass 4.1 the rest of the codebase held MinIO as an *intended*
 * dependency — the `kosha-storage` module pulled in the client library
 * but nothing actually called it. Document uploads flowed through Tika
 * to extract text, then discarded the original bytes. That worked for
 * full-text search but left preview, re-download, and version history
 * without anything to stream.
 *
 * This service is the missing layer. It is deliberately small: put, get,
 * stat, delete. Every caller goes through a derived key pattern so the
 * storage layer never has to know about document ids, version ids, or
 * the preview/original distinction directly — those are just strings
 * from the caller's point of view.
 *
 * ## Key pattern convention
 *
 * Callers are expected to compose keys using a `{namespace}/{id}/{id}`
 * style, e.g.:
 *
 *   - `documents/{documentId}/{versionId}` — the original uploaded bytes
 *   - `previews/{documentId}/{versionId}.pdf` — LibreOffice-converted PDF
 *     for Office docs (Pass 4.1 Office preview)
 *
 * No validation is done on the key string — the service trusts callers
 * to compose sensible paths. Keys must be stable across redeploys.
 *
 * ## Bucket bootstrap
 *
 * On first startup the service ensures the configured bucket exists by
 * calling `MakeBucket` if `BucketExists` returns false. This keeps the
 * deployment story to "point Kosha at a MinIO instance and go" without
 * requiring a manual mc command.
 */
@Service
class MinioStorageService(
    @Value("\${kosha.storage.minio.endpoint}") private val endpoint: String,
    @Value("\${kosha.storage.minio.access-key}") private val accessKey: String,
    @Value("\${kosha.storage.minio.secret-key}") private val secretKey: String,
    @Value("\${kosha.storage.minio.bucket}") private val bucket: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: MinioClient = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build()

    init {
        ensureBucket()
    }

    private fun ensureBucket() {
        try {
            val exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                log.info("Created MinIO bucket '{}'", bucket)
            } else {
                log.info("Using existing MinIO bucket '{}'", bucket)
            }
        } catch (ex: Exception) {
            // Don't fail app startup — MinIO might be temporarily
            // unavailable. Log loudly; the first put/get will retry.
            log.error("Could not verify MinIO bucket '{}' at startup: {}", bucket, ex.message)
        }
    }

    /**
     * Store a byte array at the given key. Content type is preserved as
     * object metadata so GET can return it. Size is required because the
     * MinIO SDK needs it up-front for the multipart strategy.
     */
    fun put(key: String, bytes: ByteArray, contentType: String) {
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(key)
                .stream(bytes.inputStream(), bytes.size.toLong(), -1)
                .contentType(contentType)
                .build(),
        )
        log.debug("Stored {} bytes at {} ({})", bytes.size, key, contentType)
    }

    /**
     * Store a streaming payload. Used by large uploads where we don't
     * want to buffer the whole file in memory. `size` may be -1 if
     * unknown, in which case the client will use a size-indifferent
     * multipart upload.
     */
    fun put(key: String, input: InputStream, size: Long, contentType: String) {
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(key)
                .stream(input, size, if (size < 0) 10 * 1024 * 1024 else -1)
                .contentType(contentType)
                .build(),
        )
    }

    /**
     * Open a streaming read of the object. Caller is responsible for
     * closing the returned stream. Throws if the key doesn't exist — let
     * it propagate to a 404 via the global exception handler.
     */
    fun get(key: String): InputStream =
        client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build())

    /**
     * Metadata lookup without downloading the body. Returns a
     * framework-agnostic [ObjectStat] so callers in other modules
     * don't need the MinIO SDK on their classpath (which would
     * force every consumer to pull `io.minio`). Returns null if the
     * object doesn't exist or the server is unreachable — the
     * distinction doesn't matter to preview callers.
     */
    fun stat(key: String): ObjectStat? {
        return try {
            val resp = client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build())
            ObjectStat(
                key = key,
                sizeBytes = resp.size(),
                contentType = resp.contentType(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun exists(key: String): Boolean = stat(key) != null

    fun delete(key: String) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
        } catch (ex: Exception) {
            log.warn("Failed to delete {}: {}", key, ex.message)
        }
    }

    // ── Key composers ────────────────────────────────────────────

    /**
     * Canonical key for an original uploaded file. Stable across
     * redeploys because it uses only ids.
     */
    fun originalKey(documentId: UUID, versionId: UUID): String =
        "documents/$documentId/$versionId"

    /**
     * Canonical key for a converted-to-PDF preview of an Office document.
     * Used by the Pass 4.1 LibreOffice sidecar path (opt-in compose
     * profile). Native-preview formats (PDF/image/text) stream the
     * [originalKey] directly and this method is unused for them.
     */
    fun previewKey(documentId: UUID, versionId: UUID): String =
        "previews/$documentId/$versionId.pdf"
}

/**
 * Storage-agnostic stat response. Mirrors the subset of MinIO's
 * StatObjectResponse that callers actually need, so adding a new
 * field requires a deliberate update here rather than implicitly
 * leaking the full MinIO SDK across every module that touches storage.
 */
data class ObjectStat(
    val key: String,
    val sizeBytes: Long,
    val contentType: String?,
)
