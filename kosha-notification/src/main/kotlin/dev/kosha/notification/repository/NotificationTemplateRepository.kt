package dev.kosha.notification.repository

import dev.kosha.notification.entity.NotificationTemplate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, UUID> {
    fun findByEventTypeAndChannelAndLocale(eventType: String, channel: String, locale: String): NotificationTemplate?
    fun findByEventTypeAndChannel(eventType: String, channel: String): List<NotificationTemplate>
}
