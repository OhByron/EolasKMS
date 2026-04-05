package dev.kosha.reporting.dto

import java.time.OffsetDateTime
import java.util.UUID

// ── Aging Report ─────────────────────────────────────────────────

data class AgingReportRow(
    val documentId: UUID,
    val docNumber: String,
    val title: String,
    val departmentId: UUID,
    val departmentName: String,
    val status: String,
    val categoryName: String?,
    val retentionPolicyName: String?,
    val retentionPeriod: String?,
    val actionOnExpiry: String?,
    val createdAt: OffsetDateTime,
    val ageDays: Long,
    val ageBand: String,
    val latestVersionNumber: String?,
    val nextReviewAt: OffsetDateTime?,
    val hasOverdueReview: Boolean,
)

data class AgingReportSummary(
    val totalDocuments: Long,
    val byAgeBand: List<AgeBandCount>,
    val byDepartment: List<DepartmentAgingSummary>,
)

data class AgeBandCount(
    val ageBand: String,
    val count: Long,
)

data class DepartmentAgingSummary(
    val departmentId: UUID,
    val departmentName: String,
    val totalDocuments: Long,
    val avgAgeDays: Long,
    val oldestDocumentDays: Long,
)

// ── Critical Items Report ────────────────────────────────────────

data class CriticalItemRow(
    val documentId: UUID,
    val docNumber: String,
    val title: String,
    val departmentId: UUID,
    val departmentName: String,
    val status: String,
    val retentionPolicyName: String,
    val retentionPeriod: String,
    val actionOnExpiry: String,
    val createdAt: OffsetDateTime,
    val ageDays: Long,
    val reviewDueAt: OffsetDateTime,
    val daysOverdue: Long,
    val severity: String,
    val reviewId: UUID,
)

data class CriticalItemsSummary(
    val totalCriticalItems: Long,
    val bySeverity: List<SeverityCount>,
    val byDepartment: List<DepartmentCriticalSummary>,
)

data class SeverityCount(
    val severity: String,
    val count: Long,
)

data class DepartmentCriticalSummary(
    val departmentId: UUID,
    val departmentName: String,
    val criticalCount: Long,
    val oldestOverdueDays: Long,
)

// ── Legal Hold Report ────────────────────────────────────────────

data class LegalHoldRow(
    val documentId: UUID,
    val docNumber: String,
    val title: String,
    val departmentId: UUID,
    val departmentName: String,
    val categoryName: String?,
    val createdAt: OffsetDateTime,
    val holdSinceDays: Long,
    val latestVersionNumber: String?,
    val createdByName: String?,
    val retentionPolicyName: String?,
    val originalRetentionPeriod: String?,
)

data class LegalHoldSummary(
    val totalOnHold: Long,
    val byDepartment: List<DepartmentHoldSummary>,
)

data class DepartmentHoldSummary(
    val departmentId: UUID,
    val departmentName: String,
    val holdCount: Long,
    val avgHoldDays: Long,
)
