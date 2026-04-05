package dev.kosha.notification.dto

import java.time.OffsetDateTime

/**
 * Response DTO for the admin UI. The password is never returned —
 * only a boolean flag indicating whether one is currently stored.
 */
data class MailGatewayConfigResponse(
    val provider: String,
    val transport: String,
    val host: String,
    val port: Int,
    val encryption: String,
    val skipTlsVerify: Boolean,
    val username: String?,
    val hasPassword: Boolean,
    val fromEmail: String,
    val fromName: String,
    val replyToEmail: String?,
    val region: String?,
    val sandboxMode: Boolean,
    val connectionTimeoutMs: Int,
    val readTimeoutMs: Int,
    val lastTestedAt: OffsetDateTime?,
    val lastTestSuccess: Boolean?,
    val lastTestError: String?,
    val updatedAt: OffsetDateTime,
)

/**
 * Request DTO for updating the config.
 *
 * Password handling: if `password` is null, the existing stored password is
 * preserved. If it's a non-blank string, it replaces the existing password.
 * An empty string explicitly clears the password.
 */
data class UpdateMailGatewayRequest(
    val provider: String,
    val transport: String = "smtp",
    val host: String,
    val port: Int,
    val encryption: String,
    val skipTlsVerify: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    val fromEmail: String,
    val fromName: String,
    val replyToEmail: String? = null,
    val region: String? = null,
    val sandboxMode: Boolean = false,
    val connectionTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 10000,
)

/**
 * Request DTO for "Test Connection" / "Test Send" — uses the same fields
 * as the update request but includes an optional test recipient address.
 */
data class TestGatewayRequest(
    val provider: String,
    val transport: String = "smtp",
    val host: String,
    val port: Int,
    val encryption: String,
    val skipTlsVerify: Boolean = false,
    val username: String? = null,
    val password: String? = null, // null = use stored password
    val fromEmail: String,
    val fromName: String,
    val testRecipient: String? = null,
    val connectionTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 10000,
)

data class TestResult(
    val success: Boolean,
    val message: String,
    val detail: String? = null,
)

/**
 * A provider preset shown in the UI dropdown. Tells the frontend which fields
 * to show, what defaults to pre-fill, and which fields are hardcoded.
 */
data class ProviderPreset(
    val key: String,
    val label: String,
    val description: String,
    val transport: String,
    val defaultHost: String?,       // null = user must supply (e.g. on-prem SMTP)
    val defaultPort: Int,
    val defaultEncryption: String,
    val fixedUsername: String? = null,  // e.g. "apikey" for SendGrid
    val showUsername: Boolean = true,
    val showPassword: Boolean = true,
    val showRegion: Boolean = false,
    val regions: List<RegionOption> = emptyList(),
    val showSkipTlsVerify: Boolean = false,
    val warning: String? = null,
    val hostHint: String? = null,
    val devOnly: Boolean = false,
)

data class RegionOption(
    val key: String,
    val label: String,
    val host: String,
)
