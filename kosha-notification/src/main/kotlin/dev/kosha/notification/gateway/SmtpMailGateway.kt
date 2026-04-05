package dev.kosha.notification.gateway

import dev.kosha.notification.entity.MailGatewayConfig
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import java.util.Properties

/**
 * SMTP-based mail gateway built from a [MailGatewayConfig] snapshot.
 * Thread-safe for concurrent sends because the underlying [JavaMailSenderImpl]
 * is stateless once configured.
 *
 * This is the only transport Kosha supports in v1. Future transports
 * (SendGrid HTTPS, SES HTTPS, Graph API) will implement [KoshaMailGateway]
 * independently.
 */
class SmtpMailGateway(
    private val config: MailGatewayConfig,
    plaintextPassword: String?,
) : KoshaMailGateway {

    private val sender: JavaMailSenderImpl = JavaMailSenderImpl().apply {
        host = config.host
        port = config.port
        username = config.username
        password = plaintextPassword
        protocol = "smtp"
        javaMailProperties = buildProperties()
    }

    private fun buildProperties(): Properties = Properties().apply {
        val hasAuth = !config.username.isNullOrBlank()

        put("mail.smtp.auth", hasAuth.toString())
        put("mail.smtp.connectiontimeout", config.connectionTimeoutMs.toString())
        put("mail.smtp.timeout", config.readTimeoutMs.toString())
        put("mail.smtp.writetimeout", config.readTimeoutMs.toString())

        when (config.encryption) {
            "starttls" -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            "tls" -> {
                put("mail.smtp.ssl.enable", "true")
            }
            "none" -> {
                // Explicitly disable both to avoid surprises
                put("mail.smtp.starttls.enable", "false")
                put("mail.smtp.ssl.enable", "false")
            }
        }

        // Self-signed cert override (use sparingly — on-prem Exchange mostly)
        if (config.skipTlsVerify && config.encryption != "none") {
            put("mail.smtp.ssl.trust", "*")
            put("mail.smtp.ssl.checkserveridentity", "false")
        }
    }

    override fun send(message: KoshaMailMessage) {
        val mime = sender.createMimeMessage()
        val helper = MimeMessageHelper(mime, false, "UTF-8")

        val fromName = message.fromName ?: config.fromName
        helper.setFrom(message.fromEmail, fromName)
        helper.setTo(message.to)
        helper.setSubject(message.subject)
        helper.setText(message.body, false)
        message.replyTo?.let { helper.setReplyTo(it) }

        sender.send(mime)
    }

    override fun testConnection() {
        // JavaMailSenderImpl.testConnection() opens a Transport, authenticates,
        // and closes it without sending. Throws MessagingException on failure.
        sender.testConnection()
    }
}
