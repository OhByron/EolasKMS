package dev.kosha.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Singleton settings row (id='default') holding global defaults for legal
 * review behaviour. Created by V024. Currently holds only the default time
 * limit for legal review steps — more fields may be added as the legal
 * review flow matures (notification cadence, escalation rules, etc.).
 */
@Entity
@Table(name = "legal_review_settings", schema = "notif")
class LegalReviewSettings(
    @Id
    @Column(length = 50)
    var id: String = "default",

    @Column(name = "default_time_limit_days", nullable = false)
    var defaultTimeLimitDays: Int = 5,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
