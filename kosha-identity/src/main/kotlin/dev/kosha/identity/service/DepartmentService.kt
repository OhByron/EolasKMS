package dev.kosha.identity.service

import dev.kosha.identity.dto.CreateDepartmentRequest
import dev.kosha.identity.dto.DepartmentResponse
import dev.kosha.identity.dto.UpdateDepartmentRequest
import dev.kosha.identity.entity.Department
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
class DepartmentService(
    private val departmentRepo: DepartmentRepository,
    private val userProfileRepo: UserProfileRepository,
    private val authorityService: AuthorityService,
) {

    fun findAll(pageable: Pageable): Page<DepartmentResponse> =
        departmentRepo.findAll(pageable).map { it.toResponse() }

    /**
     * Departments the given user can upload documents into.
     *
     * Today the rule is simple: GLOBAL_ADMIN can upload into any active
     * department, and every other role can only upload into their own home
     * department. There is no cross-department ACL table yet; when one is
     * added this method becomes the single place to extend.
     *
     * Returns an empty list if the user's profile or department is missing
     * or inactive — the upload form will render this as "you have nowhere
     * to file a document" which is the correct UX for a deactivated account.
     */
    fun findUploadableFor(keycloakId: UUID): List<DepartmentResponse> {
        val user = userProfileRepo.findByKeycloakId(keycloakId) ?: return emptyList()
        if (user.status != "ACTIVE") return emptyList()

        return if (user.role == "GLOBAL_ADMIN") {
            departmentRepo.findByStatusOrderByNameAsc("ACTIVE").map { it.toResponse() }
        } else {
            val home = user.department
            if (home.status == "ACTIVE") listOf(home.toResponse()) else emptyList()
        }
    }

    fun findById(id: UUID): DepartmentResponse =
        departmentRepo.findById(id)
            .orElseThrow { NoSuchElementException("Department not found: $id") }
            .toResponse()

    @Transactional
    fun create(request: CreateDepartmentRequest): DepartmentResponse {
        val parent = request.parentDeptId?.let {
            departmentRepo.findById(it)
                .orElseThrow { NoSuchElementException("Parent department not found: $it") }
        }

        val department = Department(
            name = request.name,
            description = request.description,
            parent = parent,
        )
        return departmentRepo.save(department).toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpdateDepartmentRequest): DepartmentResponse {
        val department = departmentRepo.findById(id)
            .orElseThrow { NoSuchElementException("Department not found: $id") }

        request.name?.let { department.name = it }
        request.description?.let { department.description = it }
        request.status?.let { department.status = it }
        request.parentDeptId?.let { parentId ->
            department.parent = departmentRepo.findById(parentId)
                .orElseThrow { NoSuchElementException("Parent department not found: $parentId") }
        }
        request.managerUserId?.let { managerId ->
            department.manager = userProfileRepo.findById(managerId)
                .orElseThrow { NoSuchElementException("Manager user not found: $managerId") }
        }

        // handles_legal_review toggle — only GLOBAL_ADMIN should be able to
        // set this (company-wide concern). The controller-level @PreAuthorize
        // on PATCH /departments/{id} authorises any DEPT_ADMIN scoped to the
        // department, which is fine for ordinary fields but not for this
        // privilege-elevating flag, so we re-check here.
        request.handlesLegalReview?.let {
            checkGlobalAdminAuthority()
            department.handlesLegalReview = it
        }

        return departmentRepo.save(department).toResponse()
    }

    /**
     * Field-level GLOBAL_ADMIN check for [update]. Defense in depth on top
     * of the controller-level @PreAuthorize, which can't inspect request
     * body fields. Reads the current authentication from SecurityContextHolder
     * and delegates to [AuthorityService.isGlobalAdmin] for the same role
     * resolution used by the @authorityService SpEL beans elsewhere.
     */
    private fun checkGlobalAdminAuthority() {
        val auth = SecurityContextHolder.getContext().authentication
        if (!authorityService.isGlobalAdmin(auth)) {
            throw AccessDeniedException("Only GLOBAL_ADMIN can change handlesLegalReview")
        }
    }

    private fun Department.toResponse() = DepartmentResponse(
        id = id!!,
        name = name,
        description = description,
        managerUserId = manager?.id,
        parentDeptId = parent?.id,
        status = status,
        handlesLegalReview = handlesLegalReview,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
