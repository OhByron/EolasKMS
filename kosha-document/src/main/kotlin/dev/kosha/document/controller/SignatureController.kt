package dev.kosha.document.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.document.service.SignatureResponse
import dev.kosha.document.service.SignatureService
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Document signature endpoints.
 *
 * - `GET /api/v1/documents/{docId}/signatures` — list all signatures
 *   for a document, newest first. Any authenticated user can read.
 * - `POST /api/v1/documents/{docId}/versions/{versionId}/sign` — create
 *   a signature. Any authenticated role can sign (the act of signing is
 *   not role-restricted — a contributor signing their own upload is a
 *   valid use case for e.g. "I certify this form is complete").
 *
 * The SIGN_OFF workflow integration doesn't go through this controller
 * — it calls [SignatureService.signFromWorkflow] directly from the
 * workflow action service. This controller is for manual signing from
 * the document detail page.
 */
@RestController
@RequestMapping("/api/v1/documents")
class SignatureController(
    private val signatureService: SignatureService,
    private val userProfileRepo: UserProfileRepository,
) {

    @GetMapping("/{docId}/signatures")
    @PreAuthorize("isAuthenticated()")
    fun listSignatures(@PathVariable docId: UUID): ApiResponse<List<SignatureResponse>> =
        ApiResponse(data = signatureService.listForDocument(docId))

    @PostMapping("/{docId}/versions/{versionId}/sign")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    fun sign(
        @PathVariable docId: UUID,
        @PathVariable versionId: UUID,
        @RequestBody request: SignRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<SignatureResponse> {
        val signerId = resolveUserId(jwt)
        return ApiResponse(
            data = signatureService.sign(
                documentId = docId,
                versionId = versionId,
                signerId = signerId,
                typedName = request.typedName,
                expectedContentHash = request.contentHash,
                ipAddress = null, // TODO: extract from X-Forwarded-For when behind a proxy
            ),
        )
    }

    private fun resolveUserId(jwt: Jwt): UUID {
        val keycloakId = UUID.fromString(jwt.subject)
        return userProfileRepo.findByKeycloakId(keycloakId)?.id
            ?: throw NoSuchElementException("User profile not found")
    }
}

data class SignRequest(
    val typedName: String,
    val contentHash: String? = null,
)
