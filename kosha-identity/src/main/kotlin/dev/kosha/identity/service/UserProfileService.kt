package dev.kosha.identity.service

import dev.kosha.identity.dto.CreateUserProfileRequest
import dev.kosha.identity.dto.UpdateUserProfileRequest
import dev.kosha.identity.dto.UserProfileResponse
import dev.kosha.identity.entity.UserProfile
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.identity.security.AuthorityService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class UserProfileService(
    private val userProfileRepo: UserProfileRepository,
    private val departmentRepo: DepartmentRepository,
    private val authorityService: AuthorityService,
) {

    companion object {
        val VALID_ROLES = setOf("GLOBAL_ADMIN", "DEPT_ADMIN", "EDITOR", "CONTRIBUTOR")
        val VALID_STATUSES = setOf("ACTIVE", "INACTIVE")
        val ELEVATED_ROLES = setOf("GLOBAL_ADMIN", "DEPT_ADMIN")
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

        // Defense-in-depth check on top of the controller-level
        // @PreAuthorize("@authorityService.canManageUser(...)"). The
        // controller already enforces dept-scope and blocks self-edit; the
        // remaining concern is field-level role escalation, which the
        // annotation can't see (the new role lives in the request body).
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
     * Field-level role-escalation block for [update].
     *
     * Promoting a user to GLOBAL_ADMIN or DEPT_ADMIN is GLOBAL_ADMIN-only.
     * The controller's `canManageUser` SpEL check already enforces:
     *   - GLOBAL_ADMIN can manage anyone
     *   - DEPT_ADMIN can only manage users currently in their own department
     *   - no self-management through this path (`/me` is the self-service surface)
     *   - missing/non-JWT auth fails closed
     *
     * So by the time control reaches this method we know the caller is a
     * legitimately-scoped admin. The only remaining risk is a DEPT_ADMIN
     * elevating someone in their dept to GLOBAL_ADMIN/DEPT_ADMIN, which is
     * what we block here.
     *
     * `targetUser` is intentionally unused today — kept on the signature as
     * a seam for future field-level rules ("can't promote a user above your
     * own role", etc.) without changing the call site.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun checkUpdateAuthority(
        targetUser: UserProfile,
        request: UpdateUserProfileRequest,
    ) {
        val auth = SecurityContextHolder.getContext().authentication
        if (authorityService.isGlobalAdmin(auth)) return

        request.role?.let { newRole ->
            if (newRole in ELEVATED_ROLES) {
                throw AccessDeniedException("Promoting users to $newRole is GLOBAL_ADMIN-only")
            }
        }
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
