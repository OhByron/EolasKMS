package dev.kosha.identity.repository

import dev.kosha.identity.entity.AccessGroup
import dev.kosha.identity.entity.Department
import dev.kosha.identity.entity.UserProfile
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DepartmentRepository : JpaRepository<Department, UUID> {
    fun findByStatus(status: String, pageable: Pageable): Page<Department>
    fun findByParentId(parentId: UUID, pageable: Pageable): Page<Department>
}

@Repository
interface UserProfileRepository : JpaRepository<UserProfile, UUID> {
    fun findByKeycloakId(keycloakId: UUID): UserProfile?
    fun findByDepartmentId(departmentId: UUID, pageable: Pageable): Page<UserProfile>
    fun findByEmail(email: String): UserProfile?

    @Query("SELECT u FROM UserProfile u WHERE u.status = :status")
    fun findByStatus(status: String, pageable: Pageable): Page<UserProfile>
}

@Repository
interface AccessGroupRepository : JpaRepository<AccessGroup, UUID> {
    fun findByDepartmentId(departmentId: UUID): List<AccessGroup>
}
