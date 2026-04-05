package dev.kosha.notification.service

import dev.kosha.notification.entity.NotificationLog
import dev.kosha.notification.repository.NotificationLogRepository
import dev.kosha.notification.repository.NotificationTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class NotificationService(
    private val templateRepo: NotificationTemplateRepository,
    private val logRepo: NotificationLogRepository,
    private val mailSender: JavaMailSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Send an email notification using a template identified by event type.
     * Template variables in the form {{variableName}} are replaced with values from the vars map.
     */
    @Transactional
    fun sendEmail(
        eventType: String,
        recipientEmail: String,
        recipientId: UUID?,
        vars: Map<String, String>,
        locale: String = "en",
    ) {
        val template = templateRepo.findByEventTypeAndChannelAndLocale(eventType, "EMAIL", locale)
        if (template == null) {
            log.warn("No email template found for event type '{}' locale '{}'", eventType, locale)
            return
        }

        val subject = renderTemplate(template.subjectTemplate ?: "", vars)
        val body = renderTemplate(template.bodyTemplate, vars)

        val entry = NotificationLog(
            templateId = template.id,
            recipientId = recipientId,
            channel = "EMAIL",
            subject = subject,
            body = body,
        )

        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, "UTF-8")
            helper.setFrom("notifications@kosha.local")
            helper.setTo(recipientEmail)
            helper.setSubject(subject)
            helper.setText(body, false)
            mailSender.send(message)

            entry.status = "SENT"
            entry.sentAt = OffsetDateTime.now()
            log.info("Sent '{}' email to {}", eventType, recipientEmail)
        } catch (ex: Exception) {
            entry.status = "FAILED"
            entry.errorDetail = ex.message?.take(2000)
            log.error("Failed to send '{}' email to {}: {}", eventType, recipientEmail, ex.message)
        }

        logRepo.save(entry)
    }

    private fun renderTemplate(template: String, vars: Map<String, String>): String {
        var result = template
        for ((key, value) in vars) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
