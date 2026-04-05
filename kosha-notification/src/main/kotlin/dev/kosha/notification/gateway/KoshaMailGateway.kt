package dev.kosha.notification.gateway

/**
 * Kosha's own mail gateway abstraction. Notification code depends on this
 * rather than `JavaMailSender` directly so that alternative transports
 * (provider HTTPS APIs) can be added later without changing callers.
 */
interface KoshaMailGateway {
    fun send(message: KoshaMailMessage)
    fun testConnection()
}

data class KoshaMailMessage(
    val to: String,
    val subject: String,
    val body: String,
    val fromEmail: String,
    val fromName: String? = null,
    val replyTo: String? = null,
)
