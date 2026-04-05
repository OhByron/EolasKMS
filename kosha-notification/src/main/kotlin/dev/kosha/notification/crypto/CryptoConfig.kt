package dev.kosha.notification.crypto

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.encrypt.TextEncryptor

/**
 * Provides a [TextEncryptor] bean used to encrypt sensitive config values
 * (currently: SMTP passwords / API keys for the mail gateway) before persisting
 * them to the database.
 *
 * ## Key material
 *
 * In production, set both environment variables:
 * - `KOSHA_CRYPTO_PASSWORD` — the master key; losing this = losing access to all
 *   encrypted values.
 * - `KOSHA_CRYPTO_SALT` — a hex-encoded salt, at least 8 bytes. Generate with:
 *   `openssl rand -hex 16`
 *
 * ## Dev defaults
 *
 * If the env vars are not set, the bean falls back to well-known dev values and
 * logs a WARNING. This keeps local development working without env tweaks but
 * is clearly unsafe for production.
 */
@Configuration
class CryptoConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun textEncryptor(
        @Value("\${kosha.crypto.password:dev-only-password-change-me}") password: String,
        @Value("\${kosha.crypto.salt:deadbeefcafef00d}") salt: String,
    ): TextEncryptor {
        if (password == "dev-only-password-change-me") {
            log.warn("╔══════════════════════════════════════════════════════════════════╗")
            log.warn("║ KOSHA_CRYPTO_PASSWORD is not set — using DEV default key.        ║")
            log.warn("║ Do NOT deploy to production without setting:                     ║")
            log.warn("║   KOSHA_CRYPTO_PASSWORD  (a strong random string)                ║")
            log.warn("║   KOSHA_CRYPTO_SALT      (hex-encoded, >= 16 chars)              ║")
            log.warn("╚══════════════════════════════════════════════════════════════════╝")
        }
        return Encryptors.delux(password, salt)
    }
}
