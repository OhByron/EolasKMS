package dev.kosha.notification.repository

import dev.kosha.notification.entity.NotificationSettings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NotificationSettingsRepository : JpaRepository<NotificationSettings, String>
