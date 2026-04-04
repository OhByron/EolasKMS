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

        request.displayName?.let { user.displayName = it }
        request.email?.let { user.email = it }
        request.role?.let { user.role = it }
        request.status?.let { user.status = it }
        request.departmentId?.let { deptId ->
            user.department = departmentRepo.findById(deptId)
                .orElseThrow { NoSuchElementException("Department not found: $deptId") }
        }

        return userProfileRepo.save(user).toResponse()
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
