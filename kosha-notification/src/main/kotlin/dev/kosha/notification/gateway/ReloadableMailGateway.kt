package dev.kosha.notification.gateway

import dev.kosha.notification.repository.MailGatewayConfigRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.encrypt.TextEncryptor
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * The bean that [dev.kosha.notification.service.NotificationService] depends on.
 *
 * Holds an [AtomicReference] to the currently active [KoshaMailGateway]. On
 * startup and whenever a [MailGatewayConfigChangedEvent] is fired, it rebuilds
 * the underlying gateway from the database configuration and atomically swaps
 * it in. In-flight sends observe a consistent snapshot — a concurrent save
 * will not corrupt an ongoing send.
 *
 * If the database config is missing or invalid, a safe "noop" gateway is
 * installed that logs but does not raise — this prevents a bad admin edit
 * from taking down the notification pipeline. The admin UI surfaces the error
 * via the last-test-result fields.
 */
@Component
class ReloadableMailGateway(
    private val configRepo: MailGatewayConfigRepository,
    private val encryptor: TextEncryptor,
) : KoshaMailGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val delegate = AtomicReference<KoshaMailGateway>(NoopGateway)

    @PostConstruct
    fun init() = reload()

    @EventListener
    fun onConfigChanged(event: MailGatewayConfigChangedEvent) {
        log.info("Mail gateway config changed — reloading...")
        reload()
    }

    fun reload() {
        try {
            val config = configRepo.findById("default").orElse(null)
            if (config == null) {
                log.warn("No mail gateway config found — using noop gateway. No emails will be sent.")
                delegate.set(NoopGateway)
                return
            }

            val plaintext = config.encryptedPassword?.takeIf { it.isNotBlank() }?.let {
                try {
                    encryptor.decrypt(it)
                } catch (e: Exception) {
                    log.error("Failed to decrypt mail gateway password — is KOSHA_CRYPTO_PASSWORD correct?", e)
                    null
                }
            }

            val newGateway = when (config.transport) {
                "smtp" -> SmtpMailGateway(config, plaintext)
                else -> {
                    log.warn("Unsupported mail transport '{}', falling back to noop", config.transport)
                    NoopGateway
                }
            }

            delegate.set(newGateway)
            log.info(
                "Mail gateway reloaded: provider={} host={}:{} encryption={}",
                config.provider, config.host, config.port, config.encryption
            )
        } catch (e: Exception) {
            log.error("Failed to load mail gateway config — keeping previous gateway active", e)
        }
    }

    override fun send(message: KoshaMailMessage) {
        delegate.get().send(message)
    }

    override fun testConnection() {
        delegate.get().testConnection()
    }

    /**
     * Safe fallback when no config is available or the transport is unknown.
     * Logs the attempt so operators can see what would have been sent.
     */
    private object NoopGateway : KoshaMailGateway {
        private val log = LoggerFactory.getLogger(javaClass)
        override fun send(message: KoshaMailMessage) {
            log.warn("Mail gateway not configured — dropping email to {} (subject: {})", message.to, message.subject)
        }
        override fun testConnection() {
            throw IllegalStateException("Mail gateway is not configured")
        }
    }
}
