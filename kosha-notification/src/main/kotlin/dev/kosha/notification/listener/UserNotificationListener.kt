package dev.kosha.notification.listener

import dev.kosha.common.event.UserPasswordReset
import dev.kosha.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Handles user-lifecycle notifications fired by the identity module.
 * Currently only reacts to admin-initiated password resets — more events
 * (welcome emails, deactivation notices) can land here later.
 */
@Component
class UserNotificationListener(
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onPasswordReset(event: UserPasswordReset) {
        log.info("Emailing password reset to {}", event.userEmail)

        val vars = mapOf(
            "userName" to event.userDisplayName,
            "departmentName" to event.departmentName,
            "temporaryPassword" to event.temporaryPassword,
        )

        notificationService.sendEmail(
            eventType = "user.password.reset",
            recipientEmail = event.userEmail,
            recipientId = event.aggregateId,
            vars = vars,
        )
    }
}
