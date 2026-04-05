package dev.kosha.notification.repository

import dev.kosha.notification.entity.NotificationLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface NotificationLogRepository : JpaRepository<NotificationLog, UUID>
