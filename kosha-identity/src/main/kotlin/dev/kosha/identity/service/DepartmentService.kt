package dev.kosha.identity.service

import dev.kosha.identity.dto.CreateDepartmentRequest
import dev.kosha.identity.dto.DepartmentResponse
import dev.kosha.identity.dto.UpdateDepartmentRequest
import dev.kosha.identity.entity.Department
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class DepartmentService(
    private val departmentRepo: DepartmentRepository,
    private val userProfileRepo: UserProfileRepository,
) {

    fun findAll(pageable: Pageable): Page<DepartmentResponse> =
        departmentRepo.findAll(pageable).map { it.toResponse() }

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

        return departmentRepo.save(department).toResponse()
    }

    private fun Department.toResponse() = DepartmentResponse(
        id = id!!,
        name = name,
        description = description,
        managerUserId = manager?.id,
        parentDeptId = parent?.id,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
