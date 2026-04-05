package dev.kosha.identity.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.service.UserProfileService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Exposes the list of users available to pick as a document's legal reviewer.
 *
 * The list is the union of all active members of departments flagged with
 * `handles_legal_review=true`. Used by the document upload form to populate
 * the "Legal reviewer" dropdown when the submitter ticks "Requires legal review".
 *
 * This is a read-only endpoint — administration of who's in the list happens
 * indirectly by flagging departments (global admin) and adding/removing
 * users from those departments (dept admin of the legal department).
 */
@RestController
@RequestMapping("/api/v1/legal-reviewers")
class LegalReviewersController(
    private val userProfileService: UserProfileService,
) {

    // Any authenticated user who might submit a document needs to read the
    // legal reviewer list (it populates the upload form dropdown).
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list() = ApiResponse(data = userProfileService.findLegalReviewers())
}
