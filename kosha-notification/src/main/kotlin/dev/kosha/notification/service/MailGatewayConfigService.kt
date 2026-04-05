package dev.kosha.notification.service

import dev.kosha.notification.dto.MailGatewayConfigResponse
import dev.kosha.notification.dto.TestGatewayRequest
import dev.kosha.notification.dto.TestResult
import dev.kosha.notification.dto.UpdateMailGatewayRequest
import dev.kosha.notification.entity.MailGatewayConfig
import dev.kosha.notification.gateway.KoshaMailMessage
import dev.kosha.notification.gateway.MailGatewayConfigChangedEvent
import dev.kosha.notification.gateway.SmtpMailGateway
import dev.kosha.notification.repository.MailGatewayConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.encrypt.TextEncryptor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
@Transactional(readOnly = true)
class MailGatewayConfigService(
    private val configRepo: MailGatewayConfigRepository,
    private val encryptor: TextEncryptor,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getConfig(): MailGatewayConfigResponse {
        val config = configRepo.findById("default")
            .orElseGet { configRepo.save(MailGatewayConfig()) }
        return config.toResponse()
    }

    @Transactional
    fun updateConfig(request: UpdateMailGatewayRequest): MailGatewayConfigResponse {
        validateRequest(request)

        val config = configRepo.findById("default")
            .orElseGet { MailGatewayConfig() }

        config.provider = request.provider
        config.transport = request.transport
        config.host = request.host
        config.port = request.port
        config.encryption = request.encryption
        config.skipTlsVerify = request.skipTlsVerify
        config.username = request.username
        config.fromEmail = request.fromEmail
        config.fromName = request.fromName
        config.replyToEmail = request.replyToEmail
        config.region = request.region
        config.sandboxMode = request.sandboxMode
        config.connectionTimeoutMs = request.connectionTimeoutMs
        config.readTimeoutMs = request.readTimeoutMs
        config.updatedAt = OffsetDateTime.now()

        // Password: null = keep existing, empty = clear, non-empty = re-encrypt
        when {
            request.password == null -> { /* keep existing */ }
            request.password.isEmpty() -> config.encryptedPassword = null
            else -> config.encryptedPassword = encryptor.encrypt(request.password)
        }

        val saved = configRepo.save(config)
        events.publishEvent(MailGatewayConfigChangedEvent())
        log.info("Mail gateway config updated: provider={} host={}:{}", saved.provider, saved.host, saved.port)

        return saved.toResponse()
    }

    /**
     * Build a transient gateway from the form data (without persisting)
     * and try to open an SMTP connection. Used by the "Test Connection"
     * button in the admin UI.
     */
    @Transactional
    fun testConnection(request: TestGatewayRequest): TestResult {
        val (_, plaintext) = buildTransientConfig(request)
        val gateway = SmtpMailGateway(buildEntityFromRequest(request), plaintext)

        return try {
            gateway.testConnection()
            recordTestResult(success = true, error = null)
            TestResult(success = true, message = "Connection successful")
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            recordTestResult(success = false, error = msg)
            TestResult(success = false, message = "Connection failed", detail = msg)
        }
    }

    /**
     * Build a transient gateway and send a test message to [request.testRecipient].
     * Does not modify the stored config apart from the last-test-result fields.
     */
    @Transactional
    fun testSend(request: TestGatewayRequest): TestResult {
        val recipient = request.testRecipient
            ?: return TestResult(success = false, message = "No test recipient specified")

        val (_, plaintext) = buildTransientConfig(request)
        val gateway = SmtpMailGateway(buildEntityFromRequest(request), plaintext)

        return try {
            gateway.send(KoshaMailMessage(
                to = recipient,
                subject = "Eòlas — Mail Gateway Test",
                body = """
                    This is a test message from Eòlas.

                    If you're reading this, your mail gateway is configured correctly.

                    Provider: ${request.provider}
                    Host: ${request.host}:${request.port}
                    Encryption: ${request.encryption}
                    Sent: ${OffsetDateTime.now()}
                """.trimIndent(),
                fromEmail = request.fromEmail,
                fromName = request.fromName,
            ))
            recordTestResult(success = true, error = null)
            TestResult(success = true, message = "Test email sent to $recipient")
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            recordTestResult(success = false, error = msg)
            TestResult(success = false, message = "Send failed", detail = msg)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Build an [MailGatewayConfig] from a request *without* saving it.
     * If the request password is null, we load the stored password so the
     * admin can test an existing config without re-entering credentials.
     */
    private fun buildTransientConfig(request: TestGatewayRequest): Pair<MailGatewayConfig, String?> {
        val plaintext: String? = when {
            request.password != null -> request.password.takeIf { it.isNotBlank() }
            else -> {
                // Fall back to the stored password
                configRepo.findById("default").orElse(null)
                    ?.encryptedPassword
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        try { encryptor.decrypt(it) }
                        catch (e: Exception) {
                            log.warn("Failed to decrypt stored password for test: {}", e.message)
                            null
                        }
                    }
            }
        }
        return buildEntityFromRequest(request) to plaintext
    }

    private fun buildEntityFromRequest(request: TestGatewayRequest): MailGatewayConfig =
        MailGatewayConfig(
            provider = request.provider,
            transport = request.transport,
            host = request.host,
            port = request.port,
            encryption = request.encryption,
            skipTlsVerify = request.skipTlsVerify,
            username = request.username,
            fromEmail = request.fromEmail,
            fromName = request.fromName,
            connectionTimeoutMs = request.connectionTimeoutMs,
            readTimeoutMs = request.readTimeoutMs,
        )

    @Transactional
    fun recordTestResult(success: Boolean, error: String?) {
        val config = configRepo.findById("default").orElse(null) ?: return
        config.lastTestedAt = OffsetDateTime.now()
        config.lastTestSuccess = success
        config.lastTestError = error?.take(2000)
        configRepo.save(config)
    }

    private fun validateRequest(request: UpdateMailGatewayRequest) {
        require(request.host.isNotBlank()) { "Host is required" }
        require(request.port in 1..65535) { "Port must be between 1 and 65535" }
        require(request.encryption in listOf("starttls", "tls", "none")) {
            "Encryption must be one of: starttls, tls, none"
        }
        require(request.fromEmail.isNotBlank()) { "From email is required" }
        require(request.fromEmail.contains("@")) { "From email must be a valid address" }
    }

    private fun MailGatewayConfig.toResponse() = MailGatewayConfigResponse(
        provider = provider,
        transport = transport,
        host = host,
        port = port,
        encryption = encryption,
        skipTlsVerify = skipTlsVerify,
        username = username,
        hasPassword = !encryptedPassword.isNullOrBlank(),
        fromEmail = fromEmail,
        fromName = fromName,
        replyToEmail = replyToEmail,
        region = region,
        sandboxMode = sandboxMode,
        connectionTimeoutMs = connectionTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        lastTestedAt = lastTestedAt,
        lastTestSuccess = lastTestSuccess,
        lastTestError = lastTestError,
        updatedAt = updatedAt,
    )
}
