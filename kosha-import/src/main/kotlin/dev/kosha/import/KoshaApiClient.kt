package dev.kosha.import

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * HTTP client wrapping the tiny slice of the Eòlas API that the bulk
 * importer needs. Uses `java.net.http.HttpClient` so we don't drag in
 * a heavier RestTemplate/WebClient dependency for a single-process
 * CLI tool.
 *
 * All requests carry the bearer token the user passed on the command
 * line. There is no refresh logic — if the token expires mid-import
 * the CLI aborts, the user gets a fresh token, and re-runs (state
 * file makes resumption cheap).
 */
@Component
class KoshaApiClient(private val mapper: ObjectMapper) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // Set by BulkImporter.run() before any request is made. Passed via
    // setter rather than constructor because the CLI options aren't
    // known at Spring context construction time.
    lateinit var baseUrl: String
    lateinit var token: String

    /**
     * Dry-run validation. Sends the raw CSV body to the backend and
     * receives a per-row verdict. No files are sent at this stage.
     *
     * When [autoProvisionOwners] is true, rows with an unknown
     * owner_email that are otherwise valid are treated as OK and
     * flagged for auto-provision (see DryRunRow.autoProvisionOwner).
     * Defaults to false to preserve the fail-loud rule.
     */
    fun validateCsv(csvContent: String, autoProvisionOwners: Boolean = false): DryRunResponse {
        val body = mapper.writeValueAsString(
            mapOf(
                "csvContent" to csvContent,
                "autoProvisionOwners" to autoProvisionOwners,
            ),
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/admin/import/validate"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IOException("Validate failed: HTTP ${resp.statusCode()}: ${resp.body()}")
        }
        val wrapped: ApiWrapper<DryRunResponse> = mapper.readValue(resp.body())
        return wrapped.data
    }

    /**
     * Create a document row. Mirrors the DocumentService.create contract —
     * the importer always sends `storageMode=VAULT` and `workflowType=LINEAR`
     * because those are the safe defaults for imported content.
     */
    fun createDocument(req: CreateDocumentPayload): CreatedDocument {
        val body = mapper.writeValueAsString(req)
        val httpReq = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/documents"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IOException("createDocument failed: HTTP ${resp.statusCode()}: ${resp.body()}")
        }
        val wrapped: ApiWrapper<CreatedDocument> = mapper.readValue(resp.body())
        return wrapped.data
    }

    // ── User import (roadmap 4.2.1) ──────────────────────────────

    /**
     * Dry-run validation for a user-import CSV. Mirrors the document
     * flow's validateCsv but hits a different backend endpoint with a
     * different response shape. Returns resolved department ids so the
     * CLI can skip a second lookup round-trip.
     */
    fun validateUserCsv(csvContent: String): UserDryRunResponse {
        val body = mapper.writeValueAsString(mapOf("csvContent" to csvContent))
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/admin/import/users/validate"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IOException("User dry-run failed: HTTP ${resp.statusCode()}: ${resp.body()}")
        }
        val wrapped: ApiWrapper<UserDryRunResponse> = mapper.readValue(resp.body())
        return wrapped.data
    }

    /**
     * Provision a user via the existing `POST /users/provision` endpoint.
     * Returns the new user's id + the temporary password that the admin
     * must communicate to the new user (or the password the admin set
     * explicitly via the CSV's temporary_password column).
     */
    fun provisionUser(req: ProvisionUserPayload): ProvisionedUserResponse {
        val body = mapper.writeValueAsString(req)
        val httpReq = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/users/provision"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IOException("provisionUser failed: HTTP ${resp.statusCode()}: ${resp.body()}")
        }
        val wrapped: ApiWrapper<ProvisionedUserResponse> = mapper.readValue(resp.body())
        return wrapped.data
    }

    // ── Document import (existing) ───────────────────────────────

    fun createVersion(documentId: String, fileName: String, fileSizeBytes: Long): CreatedVersion {
        val body = mapper.writeValueAsString(
            mapOf(
                "fileName" to fileName,
                "fileSizeBytes" to fileSizeBytes,
                "changeSummary" to "imported from CSV",
            ),
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/documents/$documentId/versions"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IOException("createVersion failed: HTTP ${resp.statusCode()}: ${resp.body()}")
        }
        val wrapped: ApiWrapper<CreatedVersion> = mapper.readValue(resp.body())
        return wrapped.data
    }

    /**
     * Upload file bytes to the existing upload endpoint. Uses a hand-
     * rolled multipart body because `java.net.http.HttpClient` doesn't
     * ship a multipart builder and we don't want another dep.
     *
     * Boundary is a random UUID so it cannot collide with file content.
     */
    fun uploadBytes(
        documentId: String,
        versionId: String,
        filePath: Path,
        contentType: String,
    ) {
        val boundary = "---kosha-import-${java.util.UUID.randomUUID()}"
        val fileName = filePath.fileName.toString()
        val fileBytes = Files.readAllBytes(filePath)

        val preamble = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val postamble = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val body = ByteArray(preamble.size + fileBytes.size + postamble.size)
        System.arraycopy(preamble, 0, body, 0, preamble.size)
        System.arraycopy(fileBytes, 0, body, preamble.size, fileBytes.size)
        System.arraycopy(postamble, 0, body, preamble.size + fileBytes.size, postamble.size)

        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/documents/$documentId/versions/$versionId/upload"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofMinutes(5)) // large files take time
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IOException("upload failed: HTTP ${resp.statusCode()}: ${resp.body()}")
        }
    }
}

// ── DTOs ──────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiWrapper<T>(val data: T)

data class CreateDocumentPayload(
    val title: String,
    val description: String?,
    val departmentId: String,
    val categoryId: String?,
    val storageMode: String = "VAULT",
    val workflowType: String = "LINEAR",
    val ownerId: String?,
    val requiresLegalReview: Boolean,
    val legalReviewerId: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreatedDocument(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreatedVersion(val id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DryRunResponse(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val rows: List<DryRunRow>,
    val globalErrors: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DryRunRow(
    val row: Int,
    val filePath: String,
    val ok: Boolean,
    val errors: List<String> = emptyList(),
    val resolved: ResolvedIds? = null,
    val autoProvisionOwner: AutoProvisionOwnerHint? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AutoProvisionOwnerHint(
    val email: String,
    val displayName: String,
    val departmentId: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResolvedIds(
    val departmentId: String,
    val categoryId: String?,
    val ownerId: String,
    val legalReviewerId: String?,
)

// ── User import DTOs (roadmap 4.2.1) ────────────────────────

data class ProvisionUserPayload(
    val email: String,
    val displayName: String,
    val departmentId: String,
    val role: String,
    val temporaryPassword: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProvisionedUserResponse(
    val user: ProvisionedUserInfo,
    val temporaryPassword: String,
    val mustChangePasswordOnFirstLogin: Boolean,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProvisionedUserInfo(
    val id: String,
    val email: String,
    val displayName: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserDryRunResponse(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val rows: List<UserDryRunRow>,
    val globalErrors: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserDryRunRow(
    val row: Int,
    val email: String,
    val ok: Boolean,
    val errors: List<String> = emptyList(),
    val resolvedDepartmentId: String? = null,
)
