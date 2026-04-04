package dev.kosha.retention.repository

import dev.kosha.retention.entity.RetentionPolicy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RetentionPolicyRepository : JpaRepository<RetentionPolicy, UUID> {
    fun findByStatus(status: String, pageable: Pageable): Page<RetentionPolicy>
}
