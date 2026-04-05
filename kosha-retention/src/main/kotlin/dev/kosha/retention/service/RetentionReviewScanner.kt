package dev.kosha.retention.service

import dev.kosha.common.event.RetentionReviewApproaching
import dev.kosha.common.event.RetentionReviewCritical
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.notification.service.NotificationSettingsService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Scheduled jobs for retention review notifications.
 *
 * The scanner runs at a frequent cron (daily at 07:00 by default) which acts
 * as a *tick*. On each tick it iterates every active department and decides
 * per-department whether to process reviews for that department based on its
 * configured scan interval:
 *
 * - The department's [scanIntervalHours] override (if set), or the global
 *   default from [notif.notification_settings].
 * - A department is processed only when `now() - last_scan_at >= intervalHours`
 *   (or when `last_scan_at` is null — first run).
 *
 * When a department is processed:
 * 1. Approaching deadlines (90/60/30 day warnings) — deduplicated via
 *    `ret.review_notification_sent`
 * 2. Critical overdue reviews (>30 days past due)
 * 3. `last_scan_at` is updated atomically on the department row
 *
 * The cron minimum and dept-level minimum is enforced by
 * [NotificationSettingsService] (24h). Admins can slow cadence but cannot disable.
 */
@Component
class RetentionReviewScanner(
    private val em: EntityManager,
    private val events: ApplicationEventPublisher,
    private val departmentRepo: DepartmentRepository,
    private val settingsService: NotificationSettingsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CRITICAL_THRESHOLD_DAYS = 30
        val WARNING_THRESHOLDS = listOf(90, 60, 30)
    }

    /**
     * The scheduled tick — runs daily at 07:00 by default. On each tick, every
     * department is evaluated and those whose interval has elapsed are processed.
     *
     * Kept as a single @Scheduled method so both approaching and critical scans
     * are bounded by the same per-department cadence and share the same
     * last_scan_at update. This is simpler than two separate crons.
     */
    @Scheduled(cron = "\${kosha.retention.scan-cron:0 0 7 * * *}")
    @Transactional
    fun scanTick() {
        val globalDefault = settingsService.getGlobalSettings().defaultScanIntervalHours
        log.info("Retention scan tick starting (global default interval: {}h)", globalDefault)

        val departments = departmentRepo.findAll().filter { it.status == "ACTIVE" }
        val now = OffsetDateTime.now()
        var processed = 0
        var skipped = 0

        for (dept in departments) {
            val deptId = dept.id ?: continue
            val interval = dept.scanIntervalHours ?: globalDefault
            val lastScan = dept.lastScanAt

            val shouldProcess = lastScan == null ||
                java.time.Duration.between(lastScan, now).toHours() >= interval

            if (!shouldProcess) {
                skipped++
                continue
            }

            log.info(
                "Processing department '{}' (interval={}h, last scan: {})",
                dept.name, interval, lastScan ?: "never"
            )

            try {
                scanApproachingForDepartment(deptId)
                scanOverdueForDepartment(deptId)

                dept.lastScanAt = now
                departmentRepo.save(dept)
                processed++
            } catch (ex: Exception) {
                log.error("Failed to scan department '{}' — will retry on next tick", dept.name, ex)
            }
        }

        log.info("Retention scan tick complete: {} processed, {} skipped", processed, skipped)
    }

    // ── Approaching scan (scoped to a department) ────────────────

    private fun scanApproachingForDepartment(departmentId: UUID): Int {
        var totalSent = 0

        for (threshold in WARNING_THRESHOLDS) {
            val sql = """
                SELECT
                    rr.id,
                    rr.document_id,
                    d.title,
                    rr.policy_id,
                    rp.name                                           AS policy_name,
                    d.primary_owner_id,
                    d.proxy_owner_id,
                    EXTRACT(DAY FROM rr.due_at - now())::BIGINT       AS days_until_due,
                    rr.due_at
                FROM ret.retention_review rr
                JOIN doc.document d ON d.id = rr.document_id
                JOIN ret.retention_policy rp ON rp.id = rr.policy_id
                LEFT JOIN ret.review_notification_sent ns
                    ON ns.review_id = rr.id AND ns.threshold_days = :threshold
                WHERE d.deleted_at IS NULL
                  AND d.department_id = :deptId
                  AND rr.completed_at IS NULL
                  AND rr.due_at > now()
                  AND rr.due_at <= now() + (:threshold || ' days')::INTERVAL
                  AND ns.review_id IS NULL
                ORDER BY days_until_due ASC
            """.trimIndent()

            val query = em.createNativeQuery(sql)
            query.setParameter("threshold", threshold)
            query.setParameter("deptId", departmentId)
            val rows = query.resultList

            for (row in rows) {
                val r = row as Array<*>
                val reviewId = r[0] as UUID
                val documentId = r[1] as UUID
                val title = r[2] as String
                val policyId = r[3] as UUID
                val policyName = r[4] as String
                val primaryOwnerId = r[5] as UUID
                val proxyOwnerId = r[6] as? UUID
                val daysUntilDue = (r[7] as Number).toLong()
                val dueAt = r[8].toOffsetDateTime()

                events.publishEvent(RetentionReviewApproaching(
                    aggregateId = reviewId,
                    documentId = documentId,
                    documentTitle = title,
                    policyId = policyId,
                    policyName = policyName,
                    primaryOwnerId = primaryOwnerId,
                    proxyOwnerId = proxyOwnerId,
                    daysUntilDue = daysUntilDue,
                    dueAt = dueAt,
                ))

                em.createNativeQuery(
                    "INSERT INTO ret.review_notification_sent (review_id, threshold_days) VALUES (:rid, :threshold) ON CONFLICT DO NOTHING"
                ).setParameter("rid", reviewId)
                 .setParameter("threshold", threshold)
                 .executeUpdate()

                totalSent++
            }
        }

        return totalSent
    }

    // ── Critical overdue scan (scoped to a department) ───────────

    private fun scanOverdueForDepartment(departmentId: UUID): Int {
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
              AND d.department_id = :deptId
              AND rr.completed_at IS NULL
              AND rr.due_at < now() - INTERVAL '$CRITICAL_THRESHOLD_DAYS days'
            ORDER BY rr.due_at ASC
        """.trimIndent()

        val query = em.createNativeQuery(sql)
        query.setParameter("deptId", departmentId)
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

    private fun Any?.toOffsetDateTime(): OffsetDateTime = when (this) {
        is OffsetDateTime -> this
        is Instant -> this.atOffset(ZoneOffset.UTC)
        is java.sql.Timestamp -> this.toInstant().atOffset(ZoneOffset.UTC)
        else -> OffsetDateTime.now()
    }
}
