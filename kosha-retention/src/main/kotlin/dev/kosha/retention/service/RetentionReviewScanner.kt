package dev.kosha.retention.service

import dev.kosha.common.event.RetentionReviewApproaching
import dev.kosha.common.event.RetentionReviewCritical
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
 * Scheduled jobs for retention review notifications:
 *
 * 1. **Approaching deadlines** — warns document owners at 90, 60, and 30 days
 *    before a review is due. Each (review, threshold) pair is sent only once,
 *    tracked in ret.review_notification_sent.
 *
 * 2. **Critical overdue** — alerts owners when reviews are more than 30 days
 *    past due.
 *
 * Both run daily at 07:00.
 */
@Component
class RetentionReviewScanner(
    private val em: EntityManager,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CRITICAL_THRESHOLD_DAYS = 30
        val WARNING_THRESHOLDS = listOf(90, 60, 30)
    }

    // ── Approaching deadline warnings ────────────────────────────

    @Scheduled(cron = "\${kosha.retention.scan-cron:0 0 7 * * *}")
    @Transactional
    fun scanApproachingReviews() {
        log.info("Scanning for approaching retention reviews ({}d thresholds)...", WARNING_THRESHOLDS)

        var totalSent = 0

        for (threshold in WARNING_THRESHOLDS) {
            // Find reviews due within the next `threshold` days that:
            // - are not yet completed
            // - have not already been notified at this threshold
            // - are not already overdue
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
                  AND rr.completed_at IS NULL
                  AND rr.due_at > now()
                  AND rr.due_at <= now() + (:threshold || ' days')::INTERVAL
                  AND ns.review_id IS NULL
                ORDER BY days_until_due ASC
            """.trimIndent()

            val query = em.createNativeQuery(sql)
            query.setParameter("threshold", threshold)
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
                val dueAt = when (val v = r[8]) {
                    is OffsetDateTime -> v
                    is Instant -> v.atOffset(ZoneOffset.UTC)
                    is java.sql.Timestamp -> v.toInstant().atOffset(ZoneOffset.UTC)
                    else -> OffsetDateTime.now()
                }

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

                // Record that we sent this threshold so we don't repeat it
                em.createNativeQuery(
                    "INSERT INTO ret.review_notification_sent (review_id, threshold_days) VALUES (:rid, :threshold) ON CONFLICT DO NOTHING"
                ).setParameter("rid", reviewId)
                 .setParameter("threshold", threshold)
                 .executeUpdate()

                totalSent++
            }

            if (rows.isNotEmpty()) {
                log.info("  {}d threshold: {} reviews notified", threshold, rows.size)
            }
        }

        log.info("Approaching review scan complete: {} notifications sent", totalSent)
    }

    // ── Critical overdue scan ────────────────────────────────────

    @Scheduled(cron = "\${kosha.retention.overdue-cron:0 5 7 * * *}")
    @Transactional(readOnly = true)
    fun scanOverdueReviews() {
        log.info("Scanning for critically overdue retention reviews (>{}d)...", CRITICAL_THRESHOLD_DAYS)

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
              AND rr.due_at < now() - INTERVAL '$CRITICAL_THRESHOLD_DAYS days'
            ORDER BY days_overdue DESC
        """.trimIndent()

        val rows = em.createNativeQuery(sql).resultList

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

        log.info("Overdue scan complete: {} critical reviews found", count)
    }
}
