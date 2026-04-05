package dev.kosha.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "mail_gateway_config", schema = "notif")
class MailGatewayConfig(
    @Id
    @Column(length = 50)
    var id: String = "default",

    @Column(nullable = false, length = 50)
    var provider: String = "mailpit",

    @Column(nullable = false, length = 20)
    var transport: String = "smtp",

    @Column(nullable = false, length = 255)
    var host: String = "localhost",

    @Column(nullable = false)
    var port: Int = 1025,

    @Column(nullable = false, length = 20)
    var encryption: String = "none",

    @Column(name = "skip_tls_verify", nullable = false)
    var skipTlsVerify: Boolean = false,

    @Column(length = 255)
    var username: String? = null,

    @Column(name = "encrypted_password", columnDefinition = "TEXT")
    var encryptedPassword: String? = null,

    @Column(name = "from_email", nullable = false, length = 255)
    var fromEmail: String = "notifications@eolaskms.com",

    @Column(name = "from_name", nullable = false, length = 255)
    var fromName: String = "Eòlas",

    @Column(name = "reply_to_email", length = 255)
    var replyToEmail: String? = null,

    @Column(length = 50)
    var region: String? = null,

    @Column(name = "sandbox_mode", nullable = false)
    var sandboxMode: Boolean = false,

    @Column(name = "connection_timeout_ms", nullable = false)
    var connectionTimeoutMs: Int = 10000,

    @Column(name = "read_timeout_ms", nullable = false)
    var readTimeoutMs: Int = 10000,

    @Column(name = "last_tested_at")
    var lastTestedAt: OffsetDateTime? = null,

    @Column(name = "last_test_success")
    var lastTestSuccess: Boolean? = null,

    @Column(name = "last_test_error", columnDefinition = "TEXT")
    var lastTestError: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
