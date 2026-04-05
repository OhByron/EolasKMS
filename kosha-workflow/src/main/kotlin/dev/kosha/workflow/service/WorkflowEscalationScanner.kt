package dev.kosha.workflow.service

import dev.kosha.common.event.WorkflowStepEscalated
import dev.kosha.workflow.entity.WorkflowStepInstance
import dev.kosha.workflow.repository.WorkflowStepInstanceRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Periodic scanner that reassigns overdue workflow steps from the primary
 * assignee to the step's escalation contact.
 *
 * ## Cadence (admin-configurable)
 *
 * This service ticks every minute via a fixed-rate schedule, but the
 * actual scan runs only if `(now - last_scan_at) >= scan_interval_minutes`
 * as read from `notif.workflow_escalation_settings` on each tick. That
 * gives global admins a UI-driven knob without restarting the app:
 *
 *   - Update the singleton row via `PUT /api/v1/admin/workflow-escalation-settings`
 *   - The scanner's next minute-tick sees the new interval and respects it
 *
 * Why not @Scheduled(cron = ...) with a property? Because cron expressions
 * are resolved once at bean initialisation and cannot be changed at
 * runtime without manually deregistering and re-registering the task.
 * Polling every minute and gating on the DB lets the cadence be mutable
 * from the admin UI. The cost — an extra row read per minute when no
 * actual scan is due — is negligible.
 *
 * ## Multi-instance safety
 *
 * `last_scan_at` is stored in the shared settings row. Two Kosha
 * instances scanning the same database will race on the same row; the
 * loser sees a fresh `last_scan_at` on its next tick and skips. This is
 * not a true distributed lock — two instances could theoretically scan
 * within the same millisecond — but the worst case is duplicate
 * escalation emails, and `escalated_at IS NULL` in the query ensures a
 * single step is never escalated twice.
 *
 * ## Selection and reassignment
 *
 * Same rules as before: IN_PROGRESS steps past `due_at` with
 * `escalated_at IS NULL` are reassigned to the step definition's
 * escalation contact (falling back to any active global admin), their
 * `due_at` is reset to now + step's time limit, `escalated_at` is set,
 * and a [WorkflowStepEscalated] event fires.
 */
@Service
class WorkflowEscalationScanner(
    private val stepInstanceRepo: WorkflowStepInstanceRepository,
    private val events: ApplicationEventPublisher,
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 60_000L)
    fun tick() {
        val now = OffsetDateTime.now()
        if (!shouldRunNow(now)) return

        val overdue = stepInstanceRepo.findOverdueUnescalated(now)
        if (overdue.isEmpty()) {
            // Still mark the run as "done" so we don't spin on a DB read
            // every minute when the interval is long and there's nothing
            // to process.
            recordScanCompleted(now)
            return
        }

        log.info("Found {} overdue workflow step(s) to escalate", overdue.size)

        overdue.forEach { step ->
            try {
                escalateOne(step, now)
            } catch (ex: Exception) {
                log.error(
                    "Failed to escalate step {} on instance {}: {}",
                    step.id, step.workflowInstance.id, ex.message, ex,
                )
            }
        }

        recordScanCompleted(now)
    }

    /**
     * Read the configured scan interval and the timestamp of the last
     * successful scan. Returns true if enough time has elapsed that we
     * should scan again now. Returns true on first run (last_scan_at
     * IS NULL) so the scanner does not wait out a full interval before
     * its first pass after a deploy.
     *
     * Uses a native query rather than injecting the notification module's
     * repository to avoid `kosha-workflow` depending on
     * `kosha-notification` (which already depends on workflow events via
     * `kosha-common`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun shouldRunNow(now: OffsetDateTime): Boolean {
        return try {
            val row = entityManager.createNativeQuery(
                """
                SELECT scan_interval_minutes, last_scan_at
                FROM notif.workflow_escalation_settings
                WHERE id = 'default'
                """.trimIndent(),
            ).singleResult as Array<Any?>
            val intervalMinutes = (row[0] as Number).toInt()
            val lastScanAt = row[1] as? OffsetDateTime
                ?: return true // first-ever run

            val elapsedMinutes = java.time.Duration
                .between(lastScanAt, now)
                .toMinutes()
            elapsedMinutes >= intervalMinutes
        } catch (ex: Exception) {
            // If the settings row cannot be read (e.g. migration hasn't
            // run yet in a fresh environment), default to scanning. The
            // scan itself is idempotent thanks to escalated_at.
            log.warn("Could not read workflow escalation settings, defaulting to scan: {}", ex.message)
            true
        }
    }

    /**
     * Write `last_scan_at = now` on the singleton row. Wrapped in an
     * explicit `TransactionTemplate` block because the calling `tick()`
     * method cannot be made `@Transactional` — it loops over steps and
     * each `escalateOne` already runs in its own transaction, so a
     * surrounding transaction would join them all into one and negate
     * per-step isolation. TransactionTemplate lets us open one short
     * transaction just for this write.
     */
    private fun recordScanCompleted(now: OffsetDateTime) {
        try {
            transactionTemplate.executeWithoutResult {
                entityManager.createNativeQuery(
                    """
                    UPDATE notif.workflow_escalation_settings
                    SET last_scan_at = :now, updated_at = :now
                    WHERE id = 'default'
                    """.trimIndent(),
                )
                    .setParameter("now", now)
                    .executeUpdate()
            }
        } catch (ex: Exception) {
            log.warn("Could not update last_scan_at: {}", ex.message)
        }
    }

    @Transactional
    internal fun escalateOne(step: WorkflowStepInstance, now: OffsetDateTime) {
        val previous = step.assignedTo
        val stepDef = step.stepDefinition

        val newAssignee = stepDef.escalation ?: resolveGlobalAdmin()
        if (newAssignee == null) {
            log.error(
                "Step {} is overdue but no escalation contact and no active global admin found",
                step.id,
            )
            return
        }
        if (previous?.id == newAssignee.id) {
            step.escalatedAt = now
            step.updatedAt = now
            stepInstanceRepo.save(step)
            return
        }

        step.assignedTo = newAssignee
        step.escalatedAt = now
        step.dueAt = now.plusDays(stepDef.timeLimitDays.toLong())
        step.updatedAt = now
        stepInstanceRepo.save(step)

        events.publishEvent(
            WorkflowStepEscalated(
                aggregateId = step.id!!,
                workflowInstanceId = step.workflowInstance.id!!,
                documentId = step.workflowInstance.documentId,
                previousAssigneeId = previous?.id,
                newAssigneeId = newAssignee.id!!,
                stepName = stepDef.name,
                reason = "DEADLINE_MISSED",
            ),
        )

        log.info(
            "Escalated step {} (instance {}) from {} to {}",
            step.id,
            step.workflowInstance.id,
            previous?.displayName ?: "(unassigned)",
            newAssignee.displayName,
        )
    }

    private fun resolveGlobalAdmin(): dev.kosha.identity.entity.UserProfile? {
        return try {
            entityManager
                .createQuery(
                    """
                    SELECT u FROM UserProfile u
                    WHERE u.role = 'GLOBAL_ADMIN'
                      AND u.status = 'ACTIVE'
                    ORDER BY u.createdAt ASC
                    """.trimIndent(),
                    dev.kosha.identity.entity.UserProfile::class.java,
                )
                .setMaxResults(1)
                .resultList
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
