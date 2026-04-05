package dev.kosha.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Singleton settings row (id = 'default') holding global notification defaults.
 * The retention scanner reads [defaultScanIntervalHours] whenever a department
 * has not set its own override.
 */
@Entity
@Table(name = "notification_settings", schema = "notif")
class NotificationSettings(
    @Id
    @Column(length = 50)
    var id: String = "default",

    @Column(name = "default_scan_interval_hours", nullable = false)
    var defaultScanIntervalHours: Int = 24,

    @Column(name = "min_scan_interval_hours", nullable = false)
    var minScanIntervalHours: Int = 24,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
