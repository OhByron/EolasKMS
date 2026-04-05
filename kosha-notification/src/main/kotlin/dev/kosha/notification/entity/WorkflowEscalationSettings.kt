package dev.kosha.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Singleton settings row (id='default') controlling the workflow escalation
 * scanner's cadence. Created by V027. The scanner reads this row on every
 * tick to decide whether enough time has elapsed since the last run, and
 * writes back `lastScanAt` after each successful scan so the cadence
 * survives restarts and multi-instance deployments.
 *
 * Values are persisted in minutes rather than a cron expression so the
 * admin UI can offer a friendly dropdown ("every 15 minutes", "every
 * hour", etc.) and the service layer can validate against a closed list.
 */
@Entity
@Table(name = "workflow_escalation_settings", schema = "notif")
class WorkflowEscalationSettings(
    @Id
    @Column(length = 50)
    var id: String = "default",

    @Column(name = "scan_interval_minutes", nullable = false)
    var scanIntervalMinutes: Int = 15,

    @Column(name = "last_scan_at")
    var lastScanAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
