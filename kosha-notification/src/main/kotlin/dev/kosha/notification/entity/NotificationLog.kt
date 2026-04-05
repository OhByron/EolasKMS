package dev.kosha.notification.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "notification_log", schema = "notif")
class NotificationLog(
    @Column(name = "template_id")
    val templateId: UUID? = null,

    @Column(name = "recipient_id")
    val recipientId: UUID? = null,

    @Column(nullable = false, length = 20)
    val channel: String = "EMAIL",

    val subject: String? = null,

    val body: String? = null,

    @Column(nullable = false, length = 20)
    var status: String = "PENDING",

    @Column(name = "error_detail")
    var errorDetail: String? = null,

    @Column(name = "sent_at")
    var sentAt: OffsetDateTime? = null,
) : BaseEntity()
