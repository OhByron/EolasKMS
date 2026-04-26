package dev.kosha.app.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration

/**
 * On first boot, generate a strong random password for the seed admin
 * (`admin@kosha.dev`) and print it once to the kosha-api logs in a clearly
 * formatted banner.
 *
 * Why: the realm export ships the seed admin with the hardcoded password
 * "admin" purely as a survival fallback if this bootstrap step fails.
 * Operators should always sign in with the password printed in this banner.
 *
 * Idempotent via `public.system_bootstrap.bootstrap_completed_at` — runs
 * exactly once per database lifetime. To re-run after a failure, set that
 * column back to NULL and restart kosha-api.
 *
 * If anything in this step fails, the realm-export fallback password
 * still works and the operator can investigate. We never block app startup
 * on bootstrap.
 */
@Component
class BootstrapAdminInitializer(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${kosha.keycloak.server-url}") private val keycloakUrl: String,
    @Value("\${kosha.keycloak.realm}") private val realm: String,
    @Value("\${kosha.keycloak.master-username:admin}") private val masterUser: String,
    @Value("\${kosha.keycloak.master-password:admin}") private val masterPass: String,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        private const val SEED_ADMIN_EMAIL = "admin@kosha.dev"
        private const val PASSWORD_LENGTH = 24
        // Avoid ambiguous chars (0/O, 1/l/I) so the password can be read off
        // a terminal log line without misreading.
        private const val PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    }

    override fun run(args: ApplicationArguments) {
        val completedAt = try {
            jdbcTemplate.queryForObject(
                "SELECT bootstrap_completed_at FROM public.system_bootstrap WHERE id = 'default'",
                java.sql.Timestamp::class.java,
            )
        } catch (ex: Exception) {
            log.warn("system_bootstrap not readable; skipping seed-admin initialisation", ex)
            return
        }

        if (completedAt != null) {
            log.debug("Bootstrap already completed at {}; skipping", completedAt)
            return
        }

        val generatedPassword = generatePassword()

        try {
            val token = getMasterAdminToken()
            val userId = lookupSeedUserId(token) ?: run {
                log.error(
                    "Seed admin '{}' not found in Keycloak realm '{}'. Has the realm import completed? " +
                    "Bootstrap will not retry until system_bootstrap.bootstrap_completed_at is reset to NULL.",
                    SEED_ADMIN_EMAIL, realm,
                )
                return
            }
            setSeedUserPassword(token, userId, generatedPassword)
            clearRequiredActions(token, userId)

            jdbcTemplate.update(
                """
                UPDATE public.system_bootstrap
                SET bootstrap_completed_at = now(),
                    bootstrap_admin_email = ?
                WHERE id = 'default'
                """.trimIndent(),
                SEED_ADMIN_EMAIL,
            )

            log.info(buildBanner(SEED_ADMIN_EMAIL, generatedPassword))
        } catch (ex: Exception) {
            log.error(
                "Bootstrap admin initialisation failed. The realm-export fallback password " +
                "(admin / admin) still works for emergency access. Investigate Keycloak " +
                "connectivity and reset system_bootstrap.bootstrap_completed_at to retry.",
                ex,
            )
        }
    }

    private fun generatePassword(): String {
        val rng = SecureRandom()
        return (1..PASSWORD_LENGTH)
            .map { PASSWORD_ALPHABET[rng.nextInt(PASSWORD_ALPHABET.length)] }
            .joinToString("")
    }

    private fun getMasterAdminToken(): String {
        val body = "username=${masterUser}&password=${masterPass}&grant_type=password&client_id=admin-cli"
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${keycloakUrl.trimEnd('/')}/realms/master/protocol/openid-connect/token"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            error("Keycloak master token request failed: HTTP ${resp.statusCode()}")
        }
        return objectMapper.readTree(resp.body()).path("access_token").asText()
    }

    private fun lookupSeedUserId(token: String): String? {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${keycloakUrl.trimEnd('/')}/admin/realms/$realm/users?email=$SEED_ADMIN_EMAIL&exact=true"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        val users = objectMapper.readTree(resp.body())
        return if (users.isArray && users.size() > 0) users[0].path("id").asText() else null
    }

    private fun setSeedUserPassword(token: String, userId: String, password: String) {
        val payload = objectMapper.writeValueAsString(mapOf(
            "type" to "password",
            "value" to password,
            "temporary" to false,
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${keycloakUrl.trimEnd('/')}/admin/realms/$realm/users/$userId/reset-password"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("Keycloak reset-password failed: HTTP ${resp.statusCode()} ${resp.body()}")
        }
    }

    private fun clearRequiredActions(token: String, userId: String) {
        val payload = objectMapper.writeValueAsString(mapOf(
            "requiredActions" to emptyList<String>(),
            "emailVerified" to true,
            "enabled" to true,
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${keycloakUrl.trimEnd('/')}/admin/realms/$realm/users/$userId"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("Keycloak update-user (clear required actions) failed: HTTP ${resp.statusCode()}")
        }
    }

    private fun buildBanner(email: String, password: String): String {
        val sep = "=".repeat(76)
        return buildString {
            append('\n').append(sep).append('\n')
            append("  EOLAS KMS  -  FIRST-BOOT ADMIN ACCOUNT\n")
            append("  ").append("-".repeat(72)).append('\n')
            append("    email      : ").append(email).append('\n')
            append("    password   : ").append(password).append('\n')
            append("    sign-in URL: http://localhost:5173 (or your configured kosha-web origin)\n")
            append("  ").append("-".repeat(72)).append('\n')
            append("  This password is shown ONLY ONCE. Save it now (e.g. password manager).\n")
            append("  After signing in, create your own GLOBAL_ADMIN user and either change\n")
            append("  this seed account's password or delete the account before exposing this\n")
            append("  instance to anyone outside your local machine.\n")
            append(sep).append('\n')
        }
    }
}
