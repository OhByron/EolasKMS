package dev.kosha.document.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.document.service.ShareLinkCreated
import dev.kosha.document.service.ShareLinkService
import dev.kosha.document.service.ShareLinkSummary
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Authenticated share link management endpoints.
 *
 * - `POST /api/v1/documents/{docId}/share-links` — create a share link
 * - `GET /api/v1/documents/{docId}/share-links` — list share links for a doc
 * - `DELETE /api/v1/documents/{docId}/share-links/{linkId}` — revoke a link
 *
 * The public resolution endpoint lives at `/api/v1/share/{token}` in
 * [PublicShareController] and requires no authentication.
 */
@RestController
@RequestMapping("/api/v1/documents/{docId}/share-links")
class ShareLinkController(
    private val shareLinkService: ShareLinkService,
    private val userProfileRepo: UserProfileRepository,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    fun create(
        @PathVariable docId: UUID,
        @RequestBody request: CreateShareLinkRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<ShareLinkCreated> {
        val creatorId = resolveUserId(jwt)
        return ApiResponse(
            data = shareLinkService.create(
                documentId = docId,
                versionId = request.versionId,
                creatorId = creatorId,
                expiryDays = request.expiryDays,
                password = request.password,
                maxAccess = request.maxAccess,
            ),
        )
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(@PathVariable docId: UUID): ApiResponse<List<ShareLinkSummary>> =
        ApiResponse(data = shareLinkService.listForDocument(docId))

    @DeleteMapping("/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    fun revoke(
        @PathVariable docId: UUID,
        @PathVariable linkId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ) {
        shareLinkService.revoke(linkId, resolveUserId(jwt))
    }

    private fun resolveUserId(jwt: Jwt): UUID {
        val keycloakId = UUID.fromString(jwt.subject)
        return userProfileRepo.findByKeycloakId(keycloakId)?.id
            ?: throw NoSuchElementException("User profile not found")
    }
}

data class CreateShareLinkRequest(
    val versionId: UUID,
    val expiryDays: Long? = null,
    val password: String? = null,
    val maxAccess: Int? = null,
)
