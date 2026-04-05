package dev.kosha.notification.repository

import dev.kosha.notification.entity.MailGatewayConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MailGatewayConfigRepository : JpaRepository<MailGatewayConfig, String>
