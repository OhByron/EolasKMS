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

    /**
     * Departments flagged as legal-review providers. Used by the upload
     * form to build the legal reviewer dropdown.
     */
    fun findByHandlesLegalReviewTrueAndStatus(status: String): List<Department>

    /**
     * All active departments ordered by name. Backs the uploadable-departments
     * endpoint for global admins (who can file into any department).
     */
    fun findByStatusOrderByNameAsc(status: String): List<Department>
}

@Repository
interface UserProfileRepository : JpaRepository<UserProfile, UUID> {
    fun findByKeycloakId(keycloakId: UUID): UserProfile?
    fun findByDepartmentId(departmentId: UUID, pageable: Pageable): Page<UserProfile>
    fun findByEmail(email: String): UserProfile?

    @Query("SELECT u FROM UserProfile u WHERE u.status = :status")
    fun findByStatus(status: String, pageable: Pageable): Page<UserProfile>

    /**
     * Active users from every department flagged as handles_legal_review.
     * Used to populate the legal reviewer dropdown on the upload form.
     * Ordered by department then name so the dropdown is predictable.
     */
    @Query("""
        SELECT u FROM UserProfile u
        WHERE u.status = 'ACTIVE'
          AND u.department.status = 'ACTIVE'
          AND u.department.handlesLegalReview = true
        ORDER BY u.department.name ASC, u.displayName ASC
    """)
    fun findActiveLegalReviewers(): List<UserProfile>
}

@Repository
interface AccessGroupRepository : JpaRepository<AccessGroup, UUID> {
    fun findByDepartmentId(departmentId: UUID): List<AccessGroup>
}
