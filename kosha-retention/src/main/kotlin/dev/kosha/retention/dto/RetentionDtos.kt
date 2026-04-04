package dev.kosha.retention.dto

import java.time.OffsetDateTime
import java.util.UUID

data class CreateRetentionPolicyRequest(
    val name: String,
    val description: String? = null,
    val retentionPeriod: String,
    val reviewInterval: String? = null,
    val actionOnExpiry: String,
    val departmentId: UUID? = null,
)

data class UpdateRetentionPolicyRequest(
    val name: String? = null,
    val description: String? = null,
    val actionOnExpiry: String? = null,
    val status: String? = null,
)

data class RetentionPolicyResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val retentionPeriod: String,
    val reviewInterval: String?,
    val actionOnExpiry: String,
    val departmentId: UUID?,
    val status: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
