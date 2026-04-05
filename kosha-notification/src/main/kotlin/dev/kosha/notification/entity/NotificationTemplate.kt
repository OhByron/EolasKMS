package dev.kosha.notification.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "notification_template", schema = "notif")
class NotificationTemplate(
    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(nullable = false, length = 20)
    val channel: String,

    @Column(name = "subject_template")
    val subjectTemplate: String? = null,

    @Column(name = "body_template", nullable = false)
    val bodyTemplate: String,

    @Column(nullable = false, length = 10)
    val locale: String = "en",
) : BaseEntity()
