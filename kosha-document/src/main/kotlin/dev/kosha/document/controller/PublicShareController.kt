package dev.kosha.document.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.document.service.ShareLinkGoneException
import dev.kosha.document.service.ShareLinkPasswordRequiredException
import dev.kosha.document.service.ShareLinkResolved
import dev.kosha.document.service.ShareLinkService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public share link resolution. No authentication required — the token
 * in the URL IS the credential.
 *
 * Uses POST rather than GET because the body may carry a password for
 * password-protected links. GET with a request body is technically
 * undefined in HTTP semantics; POST is the correct verb when the client
 * sends state that affects the response.
 *
 * ## Response codes
 *
 * - **200** — token valid, document still PUBLISHED, access granted.
 *   Response body contains document metadata + version info the
 *   frontend needs to render the preview.
 * - **401** — password required or incorrect (mapped from
 *   [ShareLinkPasswordRequiredException]).
 * - **410 Gone** — link expired, revoked, exhausted, or document is
 *   no longer PUBLISHED (mapped from [ShareLinkGoneException]).
 * - **404** — token not found.
 *
 * The preview itself is fetched by the frontend from the same
 * `/api/v1/documents/{docId}/versions/{versionId}/preview` endpoint
 * used for authenticated previews — except in this flow the frontend
 * calls it with the token info rather than a Bearer token. For v1
 * the public page renders the metadata returned here and shows a
 * "Preview not available in public view" note; a future pass can
 * add a signed-URL or proxy path so the anonymous viewer sees the
 * actual rendered content without needing a JWT.
 */
@RestController
@RequestMapping("/api/v1/share")
class PublicShareController(
    private val shareLinkService: ShareLinkService,
) {

    @PostMapping("/{token}")
    fun resolve(
        @PathVariable token: String,
        @RequestBody(required = false) body: ShareResolveRequest?,
    ): ResponseEntity<*> {
        return try {
            val resolved = shareLinkService.resolve(token, body?.password)
            ResponseEntity.ok(ApiResponse(data = resolved))
        } catch (ex: ShareLinkPasswordRequiredException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                mapOf("error" to ex.message, "passwordRequired" to true),
            )
        } catch (ex: ShareLinkGoneException) {
            ResponseEntity.status(HttpStatus.GONE).body(
                mapOf("error" to ex.message),
            )
        } catch (ex: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("error" to ex.message),
            )
        }
    }
}

data class ShareResolveRequest(
    val password: String? = null,
)
