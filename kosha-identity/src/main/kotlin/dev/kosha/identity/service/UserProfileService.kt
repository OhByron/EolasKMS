package dev.kosha.identity.service

import dev.kosha.identity.dto.CreateUserProfileRequest
import dev.kosha.identity.dto.UpdateUserProfileRequest
import dev.kosha.identity.dto.UserProfileResponse
import dev.kosha.identity.entity.UserProfile
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class UserProfileService(
    private val userProfileRepo: UserProfileRepository,
    private val departmentRepo: DepartmentRepository,
) {

    companion object {
        val VALID_ROLES = setOf("GLOBAL_ADMIN", "DEPT_ADMIN", "EDITOR", "CONTRIBUTOR")
        val VALID_STATUSES = setOf("ACTIVE", "INACTIVE")
    }

    fun findAll(pageable: Pageable): Page<UserProfileResponse> =
        userProfileRepo.findAll(pageable).map { it.toResponse() }

    fun findById(id: UUID): UserProfileResponse =
        userProfileRepo.findById(id)
            .orElseThrow { NoSuchElementException("User not found: $id") }
            .toResponse()

    fun findByKeycloakId(keycloakId: UUID): UserProfileResponse? =
        userProfileRepo.findByKeycloakId(keycloakId)?.toResponse()

    fun findByDepartment(departmentId: UUID, pageable: Pageable): Page<UserProfileResponse> =
        userProfileRepo.findByDepartmentId(departmentId, pageable).map { it.toResponse() }

    /**
     * Active users from departments flagged as handles_legal_review=true.
     * Drives the legal reviewer dropdown on the document upload form.
     * Returns a simple list (not paginated) — the total is expected to be
     * small (one or two departments worth of members in typical SMB setups).
     */
    fun findLegalReviewers(): List<UserProfileResponse> =
        userProfileRepo.findActiveLegalReviewers().map { it.toResponse() }

    @Transactional
    fun create(request: CreateUserProfileRequest): UserProfileResponse {
        val department = departmentRepo.findById(request.departmentId)
            .orElseThrow { NoSuchElementException("Department not found: ${request.departmentId}") }

        val user = UserProfile(
            keycloakId = request.keycloakId,
            displayName = request.displayName,
            email = request.email,
            department = department,
            role = request.role,
        )
        return userProfileRepo.save(user).toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpdateUserProfileRequest): UserProfileResponse {
        val user = userProfileRepo.findById(id)
            .orElseThrow { NoSuchElementException("User not found: $id") }

        // Authority check — the caller must be allowed to manage this user.
        // Stubbed for dev; real implementation will inspect the current JWT
        // and enforce dept admin scope. See checkUpdateAuthority() below.
        checkUpdateAuthority(user, request)

        request.role?.let {
            require(it in VALID_ROLES) { "Invalid role '$it'. Must be one of: $VALID_ROLES" }
            user.role = it
        }
        request.status?.let {
            require(it in VALID_STATUSES) { "Invalid status '$it'. Must be one of: $VALID_STATUSES" }
            user.status = it
        }
        request.displayName?.let { user.displayName = it }
        request.email?.let { user.email = it }
        request.departmentId?.let { deptId ->
            user.department = departmentRepo.findById(deptId)
                .orElseThrow { NoSuchElementException("Department not found: $deptId") }
        }

        return userProfileRepo.save(user).toResponse()
    }

    /**
     * Authority stub — in production this inspects the current JWT to verify:
     *
     * - GLOBAL_ADMIN can edit any user.
     * - DEPT_ADMIN can edit any user whose current department matches one they
     *   administer. They may change that user's role (within their dept),
     *   toggle their status, or transfer them *out* to another department.
     *   Transferring users *into* a department they don't administer is always
     *   allowed — the sending admin is losing a team member, and adding to the
     *   target dept is out of the receiving admin's control in this simple
     *   model. A future consent workflow can tighten this.
     * - Users may not edit themselves via this endpoint (self-service goes
     *   through /api/v1/me instead).
     *
     * Currently a no-op in dev because JWT roles are not fully wired up.
     * When restored, throw [org.springframework.security.access.AccessDeniedException]
     * on violation so the controller advice returns 403.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun checkUpdateAuthority(
        targetUser: UserProfile,
        request: UpdateUserProfileRequest,
    ) {
        // TODO: read current JWT, enforce the rules above. See session memory
        //       "Workflow requirements" for the authority model.
    }

    private fun UserProfile.toResponse() = UserProfileResponse(
        id = id!!,
        keycloakId = keycloakId,
        displayName = displayName,
        email = email,
        departmentId = department.id!!,
        departmentName = department.name,
        role = role,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
