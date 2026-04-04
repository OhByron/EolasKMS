package dev.kosha.identity.dto

import java.time.OffsetDateTime
import java.util.UUID

// --- Department ---

data class CreateDepartmentRequest(
    val name: String,
    val description: String? = null,
    val parentDeptId: UUID? = null,
)

data class UpdateDepartmentRequest(
    val name: String? = null,
    val description: String? = null,
    val managerUserId: UUID? = null,
    val parentDeptId: UUID? = null,
    val status: String? = null,
)

data class DepartmentResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val managerUserId: UUID?,
    val parentDeptId: UUID?,
    val status: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

// --- User Profile ---

data class CreateUserProfileRequest(
    val keycloakId: UUID,
    val displayName: String,
    val email: String,
    val departmentId: UUID,
    val role: String,
)

data class UpdateUserProfileRequest(
    val displayName: String? = null,
    val email: String? = null,
    val departmentId: UUID? = null,
    val role: String? = null,
    val status: String? = null,
)

data class UserProfileResponse(
    val id: UUID,
    val keycloakId: UUID,
    val displayName: String,
    val email: String,
    val departmentId: UUID,
    val departmentName: String,
    val role: String,
    val status: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

// --- Access Group ---

data class CreateAccessGroupRequest(
    val name: String,
    val externalRef: String? = null,
    val departmentId: UUID? = null,
)

data class AccessGroupResponse(
    val id: UUID,
    val name: String,
    val externalRef: String?,
    val departmentId: UUID?,
    val createdAt: OffsetDateTime,
)
