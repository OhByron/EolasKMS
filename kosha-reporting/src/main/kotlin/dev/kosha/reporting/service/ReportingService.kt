package dev.kosha.reporting.service

import dev.kosha.common.event.RetentionReviewCritical
import dev.kosha.reporting.dto.*
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
@Transactional(readOnly = true)
class ReportingService(
    private val em: EntityManager,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Aging Report ─────────────────────────────────────────────

    fun agingReport(departmentId: UUID?, status: String?, pageable: Pageable): Page<AgingReportRow> {
        val where = buildString {
            append("WHERE d.deleted_at IS NULL ")
            if (departmentId != null) append("AND d.department_id = :deptId ")
            if (status != null) append("AND d.status = :status ")
        }

        val sql = """
            SELECT
                d.id                                          AS document_id,
                d.doc_number,
                d.title,
                d.department_id,
                dept.name                                     AS department_name,
                d.status,
                cat.name                                      AS category_name,
                rp.name                                       AS retention_policy_name,
                rp.retention_period,
                rp.action_on_expiry,
                d.created_at,
                EXTRACT(DAY FROM now() - d.created_at)::BIGINT AS age_days,
                CASE
                    WHEN age(now(), d.created_at) < interval '6 months'  THEN '0-6 months'
                    WHEN age(now(), d.created_at) < interval '1 year'    THEN '6-12 months'
                    WHEN age(now(), d.created_at) < interval '3 years'   THEN '1-3 years'
                    WHEN age(now(), d.created_at) < interval '5 years'   THEN '3-5 years'
                    WHEN age(now(), d.created_at) < interval '7 years'   THEN '5-7 years'
                    ELSE '7+ years'
                END                                           AS age_band,
                lv.version_number                             AS latest_version_number,
                d.next_review_at,
                CASE WHEN EXISTS (
                    SELECT 1 FROM ret.retention_review rr
                    WHERE rr.document_id = d.id
                      AND rr.completed_at IS NULL
                      AND rr.due_at < now()
                ) THEN true ELSE false END                    AS has_overdue_review
            FROM doc.document d
            JOIN ident.department dept ON dept.id = d.department_id
            LEFT JOIN doc.document_category cat ON cat.id = d.category_id
            LEFT JOIN ret.retention_policy rp ON rp.id = d.retention_policy_id
            LEFT JOIN LATERAL (
                SELECT dv.version_number
                FROM doc.document_version dv
                WHERE dv.document_id = d.id
                ORDER BY dv.created_at DESC
                LIMIT 1
            ) lv ON true
            $where
            ORDER BY age_days DESC
        """.trimIndent()

        val countSql = """
            SELECT count(*) FROM doc.document d $where
        """.trimIndent()

        val count = createNativeQuery(countSql, departmentId, status)
            .singleResult.toLong()

        val rows = createNativeQuery(sql, departmentId, status)
            .setFirstResult(pageable.offset.toInt())
            .setMaxResults(pageable.pageSize)
            .resultList
            .map { it as Array<*> }
            .map { r ->
                AgingReportRow(
                    documentId = r[0] as UUID,
                    docNumber = (r[1] as? String) ?: "",
                    title = r[2] as String,
                    departmentId = r[3] as UUID,
                    departmentName = r[4] as String,
                    status = r[5] as String,
                    categoryName = r[6] as String?,
                    retentionPolicyName = r[7] as String?,
                    retentionPeriod = r[8] as String?,
                    actionOnExpiry = r[9] as String?,
                    createdAt = r[10].toOffsetDateTime(),
                    ageDays = (r[11] as Number).toLong(),
                    ageBand = r[12] as String,
                    latestVersionNumber = r[13] as String?,
                    nextReviewAt = r[14]?.toOffsetDateTime(),
                    hasOverdueReview = r[15] as Boolean,
                )
            }

        return PageImpl(rows, pageable, count)
    }

    fun agingReportSummary(departmentId: UUID?): AgingReportSummary {
        val deptFilter = if (departmentId != null) "AND d.department_id = :deptId" else ""

        val totalSql = """
            SELECT count(*) FROM doc.document d
            WHERE d.deleted_at IS NULL $deptFilter
        """.trimIndent()
        val total = createNativeQuery(totalSql, departmentId, null).singleResult.toLong()

        val bandSql = """
            SELECT
                CASE
                    WHEN age(now(), d.created_at) < interval '6 months'  THEN '0-6 months'
                    WHEN age(now(), d.created_at) < interval '1 year'    THEN '6-12 months'
                    WHEN age(now(), d.created_at) < interval '3 years'   THEN '1-3 years'
                    WHEN age(now(), d.created_at) < interval '5 years'   THEN '3-5 years'
                    WHEN age(now(), d.created_at) < interval '7 years'   THEN '5-7 years'
                    ELSE '7+ years'
                END AS age_band,
                count(*)
            FROM doc.document d
            WHERE d.deleted_at IS NULL $deptFilter
            GROUP BY 1 ORDER BY min(d.created_at)
        """.trimIndent()
        val bands = createNativeQuery(bandSql, departmentId, null)
            .resultList.map { it as Array<*> }
            .map { AgeBandCount(ageBand = it[0] as String, count = (it[1] as Number).toLong()) }

        val deptSql = """
            SELECT
                d.department_id,
                dept.name,
                count(*),
                avg(EXTRACT(DAY FROM now() - d.created_at))::BIGINT,
                max(EXTRACT(DAY FROM now() - d.created_at))::BIGINT
            FROM doc.document d
            JOIN ident.department dept ON dept.id = d.department_id
            WHERE d.deleted_at IS NULL $deptFilter
            GROUP BY d.department_id, dept.name
            ORDER BY count(*) DESC
        """.trimIndent()
        val depts = createNativeQuery(deptSql, departmentId, null)
            .resultList.map { it as Array<*> }
            .map { DepartmentAgingSummary(
                departmentId = it[0] as UUID,
                departmentName = it[1] as String,
                totalDocuments = (it[2] as Number).toLong(),
                avgAgeDays = (it[3] as Number).toLong(),
                oldestDocumentDays = (it[4] as Number).toLong(),
            ) }

        return AgingReportSummary(totalDocuments = total, byAgeBand = bands, byDepartment = depts)
    }

    // ── Critical Items Report ────────────────────────────────────

    fun criticalItems(departmentId: UUID?, minDaysOverdue: Int?, pageable: Pageable): Page<CriticalItemRow> {
        val minDays = minDaysOverdue ?: 0

        val where = buildString {
            append("WHERE d.deleted_at IS NULL AND rr.completed_at IS NULL AND rr.due_at < now() ")
            append("AND EXTRACT(DAY FROM now() - rr.due_at) >= :minDays ")
            if (departmentId != null) append("AND d.department_id = :deptId ")
        }

        val sql = """
            SELECT
                d.id                                              AS document_id,
                d.doc_number,
                d.title,
                d.department_id,
                dept.name                                         AS department_name,
                d.status,
                rp.name                                           AS retention_policy_name,
                rp.retention_period,
                rp.action_on_expiry,
                d.created_at,
                EXTRACT(DAY FROM now() - d.created_at)::BIGINT    AS age_days,
                rr.due_at                                         AS review_due_at,
                EXTRACT(DAY FROM now() - rr.due_at)::BIGINT       AS days_overdue,
                CASE
                    WHEN EXTRACT(DAY FROM now() - rr.due_at) > 180 THEN 'CRITICAL'
                    WHEN EXTRACT(DAY FROM now() - rr.due_at) > 90  THEN 'HIGH'
                    WHEN EXTRACT(DAY FROM now() - rr.due_at) > 30  THEN 'MEDIUM'
                    ELSE 'LOW'
                END                                               AS severity,
                rr.id                                             AS review_id
            FROM ret.retention_review rr
            JOIN doc.document d ON d.id = rr.document_id
            JOIN ident.department dept ON dept.id = d.department_id
            JOIN ret.retention_policy rp ON rp.id = rr.policy_id
            $where
            ORDER BY days_overdue DESC
        """.trimIndent()

        val countSql = """
            SELECT count(*)
            FROM ret.retention_review rr
            JOIN doc.document d ON d.id = rr.document_id
            $where
        """.trimIndent()

        val countQuery = em.createNativeQuery(countSql)
        countQuery.setParameter("minDays", minDays)
        if (departmentId != null) countQuery.setParameter("deptId", departmentId)
        val count = countQuery.singleResult.toLong()

        val query = em.createNativeQuery(sql)
        query.setParameter("minDays", minDays)
        if (departmentId != null) query.setParameter("deptId", departmentId)

        val rows = query
            .setFirstResult(pageable.offset.toInt())
            .setMaxResults(pageable.pageSize)
            .resultList
            .map { it as Array<*> }
            .map { r ->
                CriticalItemRow(
                    documentId = r[0] as UUID,
                    docNumber = (r[1] as? String) ?: "",
                    title = r[2] as String,
                    departmentId = r[3] as UUID,
                    departmentName = r[4] as String,
                    status = r[5] as String,
                    retentionPolicyName = r[6] as String,
                    retentionPeriod = r[7] as String,
                    actionOnExpiry = r[8] as String,
                    createdAt = r[9].toOffsetDateTime(),
                    ageDays = (r[10] as Number).toLong(),
                    reviewDueAt = r[11].toOffsetDateTime(),
                    daysOverdue = (r[12] as Number).toLong(),
                    severity = r[13] as String,
                    reviewId = r[14] as UUID,
                )
            }

        return PageImpl(rows, pageable, count)
    }

    fun criticalItemsSummary(departmentId: UUID?): CriticalItemsSummary {
        val deptFilter = if (departmentId != null) "AND d.department_id = :deptId" else ""

        val totalSql = """
            SELECT count(*)
            FROM ret.retention_review rr
            JOIN doc.document d ON d.id = rr.document_id
            WHERE d.deleted_at IS NULL AND rr.completed_at IS NULL AND rr.due_at < now()
            $deptFilter
        """.trimIndent()
        val totalQuery = em.createNativeQuery(totalSql)
        if (departmentId != null) totalQuery.setParameter("deptId", departmentId)
        val total = totalQuery.singleResult.toLong()

        val sevSql = """
            SELECT
                CASE
                    WHEN EXTRACT(DAY FROM now() - rr.due_at) > 180 THEN 'CRITICAL'
                    WHEN EXTRACT(DAY FROM now() - rr.due_at) > 90  THEN 'HIGH'
                    WHEN EXTRACT(DAY FROM now() - rr.due_at) > 30  THEN 'MEDIUM'
                    ELSE 'LOW'
                END AS severity,
                count(*)
            FROM ret.retention_review rr
            JOIN doc.document d ON d.id = rr.document_id
            WHERE d.deleted_at IS NULL AND rr.completed_at IS NULL AND rr.due_at < now()
            $deptFilter
            GROUP BY 1
            ORDER BY min(EXTRACT(DAY FROM now() - rr.due_at)) DESC
        """.trimIndent()
        val sevQuery = em.createNativeQuery(sevSql)
        if (departmentId != null) sevQuery.setParameter("deptId", departmentId)
        val severities = sevQuery.resultList.map { it as Array<*> }
            .map { SeverityCount(severity = it[0] as String, count = (it[1] as Number).toLong()) }

        val deptSql = """
            SELECT
                d.department_id,
                dept.name,
                count(*),
                max(EXTRACT(DAY FROM now() - rr.due_at))::BIGINT
            FROM ret.retention_review rr
            JOIN doc.document d ON d.id = rr.document_id
            JOIN ident.department dept ON dept.id = d.department_id
            WHERE d.deleted_at IS NULL AND rr.completed_at IS NULL AND rr.due_at < now()
            $deptFilter
            GROUP BY d.department_id, dept.name
            ORDER BY count(*) DESC
        """.trimIndent()
        val deptQuery = em.createNativeQuery(deptSql)
        if (departmentId != null) deptQuery.setParameter("deptId", departmentId)
        val depts = deptQuery.resultList.map { it as Array<*> }
            .map { DepartmentCriticalSummary(
                departmentId = it[0] as UUID,
                departmentName = it[1] as String,
                criticalCount = (it[2] as Number).toLong(),
                oldestOverdueDays = (it[3] as Number).toLong(),
            ) }

        return CriticalItemsSummary(totalCriticalItems = total, bySeverity = severities, byDepartment = depts)
    }

    // ── Legal Hold Report ────────────────────────────────────────

    fun legalHolds(departmentId: UUID?, pageable: Pageable): Page<LegalHoldRow> {
        val deptFilter = if (departmentId != null) "AND d.department_id = :deptId" else ""

        val sql = """
            SELECT
                d.id                                              AS document_id,
                d.doc_number,
                d.title,
                d.department_id,
                dept.name                                         AS department_name,
                cat.name                                          AS category_name,
                d.created_at,
                EXTRACT(DAY FROM now() - d.created_at)::BIGINT    AS hold_since_days,
                lv.version_number                                 AS latest_version_number,
                up.display_name                                   AS created_by_name,
                rp.name                                           AS retention_policy_name,
                rp.retention_period                               AS original_retention_period
            FROM doc.document d
            JOIN ident.department dept ON dept.id = d.department_id
            LEFT JOIN doc.document_category cat ON cat.id = d.category_id
            LEFT JOIN ident.user_profile up ON up.id = d.created_by
            LEFT JOIN ret.retention_policy rp ON rp.id = d.retention_policy_id
            LEFT JOIN LATERAL (
                SELECT dv.version_number
                FROM doc.document_version dv
                WHERE dv.document_id = d.id
                ORDER BY dv.created_at DESC
                LIMIT 1
            ) lv ON true
            WHERE d.deleted_at IS NULL AND d.status = 'LEGAL_HOLD'
            $deptFilter
            ORDER BY d.created_at ASC
        """.trimIndent()

        val countSql = """
            SELECT count(*)
            FROM doc.document d
            WHERE d.deleted_at IS NULL AND d.status = 'LEGAL_HOLD'
            $deptFilter
        """.trimIndent()

        val countQuery = em.createNativeQuery(countSql)
        if (departmentId != null) countQuery.setParameter("deptId", departmentId)
        val count = countQuery.singleResult.toLong()

        val query = em.createNativeQuery(sql)
        if (departmentId != null) query.setParameter("deptId", departmentId)

        val rows = query
            .setFirstResult(pageable.offset.toInt())
            .setMaxResults(pageable.pageSize)
            .resultList
            .map { it as Array<*> }
            .map { r ->
                LegalHoldRow(
                    documentId = r[0] as UUID,
                    docNumber = (r[1] as? String) ?: "",
                    title = r[2] as String,
                    departmentId = r[3] as UUID,
                    departmentName = r[4] as String,
                    categoryName = r[5] as String?,
                    createdAt = r[6].toOffsetDateTime(),
                    holdSinceDays = (r[7] as Number).toLong(),
                    latestVersionNumber = r[8] as String?,
                    createdByName = r[9] as String?,
                    retentionPolicyName = r[10] as String?,
                    originalRetentionPeriod = r[11] as String?,
                )
            }

        return PageImpl(rows, pageable, count)
    }

    fun legalHoldSummary(departmentId: UUID?): LegalHoldSummary {
        val deptFilter = if (departmentId != null) "AND d.department_id = :deptId" else ""

        val totalSql = """
            SELECT count(*)
            FROM doc.document d
            WHERE d.deleted_at IS NULL AND d.status = 'LEGAL_HOLD'
            $deptFilter
        """.trimIndent()
        val totalQuery = em.createNativeQuery(totalSql)
        if (departmentId != null) totalQuery.setParameter("deptId", departmentId)
        val total = totalQuery.singleResult.toLong()

        val deptSql = """
            SELECT
                d.department_id,
                dept.name,
                count(*),
                avg(EXTRACT(DAY FROM now() - d.created_at))::BIGINT
            FROM doc.document d
            JOIN ident.department dept ON dept.id = d.department_id
            WHERE d.deleted_at IS NULL AND d.status = 'LEGAL_HOLD'
            $deptFilter
            GROUP BY d.department_id, dept.name
            ORDER BY count(*) DESC
        """.trimIndent()
        val deptQuery = em.createNativeQuery(deptSql)
        if (departmentId != null) deptQuery.setParameter("deptId", departmentId)
        val depts = deptQuery.resultList.map { it as Array<*> }
            .map { DepartmentHoldSummary(
                departmentId = it[0] as UUID,
                departmentName = it[1] as String,
                holdCount = (it[2] as Number).toLong(),
                avgHoldDays = (it[3] as Number).toLong(),
            ) }

        return LegalHoldSummary(totalOnHold = total, byDepartment = depts)
    }

    // ── Critical Item Notifications ─────────────────────────────

    /**
     * Send retention review notifications for specific review IDs.
     * Returns the number of notifications dispatched.
     */
    fun notifySelected(reviewIds: List<UUID>): Int {
        if (reviewIds.isEmpty()) return 0
        return queryCriticalReviews("AND rr.id IN (:ids)", reviewIds).also {
            log.info("Dispatched {} critical review notifications (selected)", it)
        }
    }

    /**
     * Send retention review notifications for ALL critically overdue reviews.
     * Optionally filtered by department.
     */
    fun notifyAll(departmentId: UUID?): Int {
        val deptFilter = if (departmentId != null) "AND d.department_id = :deptId" else ""
        return queryCriticalReviews(deptFilter, null, departmentId).also {
            log.info("Dispatched {} critical review notifications (all, dept={})", it, departmentId ?: "all")
        }
    }

    private fun queryCriticalReviews(extraFilter: String, reviewIds: List<UUID>? = null, departmentId: UUID? = null): Int {
        val sql = """
            SELECT
                rr.id,
                rr.document_id,
                d.title,
                rr.policy_id,
                d.primary_owner_id,
                d.proxy_owner_id,
                EXTRACT(DAY FROM now() - rr.due_at)::BIGINT AS days_overdue
            FROM ret.retention_review rr
            JOIN doc.document d ON d.id = rr.document_id
            WHERE d.deleted_at IS NULL
              AND rr.completed_at IS NULL
              AND rr.due_at < now()
              $extraFilter
        """.trimIndent()

        val query = em.createNativeQuery(sql)
        if (reviewIds != null) query.setParameter("ids", reviewIds)
        if (departmentId != null) query.setParameter("deptId", departmentId)

        val rows = query.resultList
        var count = 0
        for (row in rows) {
            val r = row as Array<*>
            events.publishEvent(RetentionReviewCritical(
                aggregateId = r[0] as UUID,
                documentId = r[1] as UUID,
                documentTitle = r[2] as String,
                policyId = r[3] as UUID,
                primaryOwnerId = r[4] as UUID,
                proxyOwnerId = r[5] as? UUID,
                daysOverdue = (r[6] as Number).toLong(),
            ))
            count++
        }
        return count
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun createNativeQuery(sql: String, departmentId: UUID?, status: String?) =
        em.createNativeQuery(sql).also { q ->
            if (departmentId != null && sql.contains(":deptId")) q.setParameter("deptId", departmentId)
            if (status != null && sql.contains(":status")) q.setParameter("status", status)
        }

    private fun Any?.toOffsetDateTime(): OffsetDateTime = when (this) {
        is OffsetDateTime -> this
        is Instant -> this.atOffset(ZoneOffset.UTC)
        is java.sql.Timestamp -> this.toInstant().atOffset(ZoneOffset.UTC)
        else -> throw IllegalArgumentException("Cannot convert ${this?.javaClass} to OffsetDateTime")
    }

    private fun Any?.toLong(): Long = (this as Number).toLong()
}
