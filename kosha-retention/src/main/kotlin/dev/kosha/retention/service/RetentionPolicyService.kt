package dev.kosha.retention.service

import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.retention.dto.CreateRetentionPolicyRequest
import dev.kosha.retention.dto.RetentionPolicyResponse
import dev.kosha.retention.dto.UpdateRetentionPolicyRequest
import dev.kosha.retention.entity.RetentionPolicy
import dev.kosha.retention.repository.RetentionPolicyRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class RetentionPolicyService(
    private val policyRepo: RetentionPolicyRepository,
    private val deptRepo: DepartmentRepository,
) {

    fun findAll(pageable: Pageable): Page<RetentionPolicyResponse> =
        policyRepo.findAll(pageable).map { it.toResponse() }

    fun findById(id: UUID): RetentionPolicyResponse =
        policyRepo.findById(id)
            .orElseThrow { NoSuchElementException("Retention policy not found: $id") }
            .toResponse()

    @Transactional
    fun create(request: CreateRetentionPolicyRequest): RetentionPolicyResponse {
        val dept = request.departmentId?.let {
            deptRepo.findById(it).orElseThrow { NoSuchElementException("Department not found: $it") }
        }
        val policy = RetentionPolicy(
            name = request.name,
            description = request.description,
            retentionPeriod = request.retentionPeriod,
            reviewInterval = request.reviewInterval,
            actionOnExpiry = request.actionOnExpiry,
            department = dept,
        )
        return policyRepo.save(policy).toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpdateRetentionPolicyRequest): RetentionPolicyResponse {
        val policy = policyRepo.findById(id)
            .orElseThrow { NoSuchElementException("Retention policy not found: $id") }

        request.name?.let { policy.name = it }
        request.description?.let { policy.description = it }
        request.actionOnExpiry?.let { policy.actionOnExpiry = it }
        request.status?.let { policy.status = it }

        return policyRepo.save(policy).toResponse()
    }

    private fun RetentionPolicy.toResponse() = RetentionPolicyResponse(
        id = id!!,
        name = name,
        description = description,
        retentionPeriod = retentionPeriod,
        reviewInterval = reviewInterval,
        actionOnExpiry = actionOnExpiry,
        departmentId = department?.id,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
