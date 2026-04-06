package dev.kosha.app.licence

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Reads and validates the Eòlas licence key.
 *
 * The key is a base64-encoded JSON payload + RSA signature. The public
 * verification key ships in the application resources. The private
 * signing key is held offline by the vendor and never published.
 *
 * If no key is provided, or the key is invalid/expired, the system
 * runs as Community tier with full functionality. The licence is a
 * label, not a lock. Nothing stops working when a key expires.
 */
@Service
class LicenceService(
    @Value("\${kosha.licence.key:}") private val envKey: String,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var _tier: String = "community"
    private var _organisation: String? = null
    private var _expiresAt: LocalDate? = null
    private var _licenceId: String? = null
    private var _maxUsers: Int? = null
    private var _features: Map<String, Any> = emptyMap()

    val tier: String get() = _tier
    val organisation: String? get() = _organisation
    val expiresAt: LocalDate? get() = _expiresAt
    val licenceId: String? get() = _licenceId
    val maxUsers: Int? get() = _maxUsers
    val features: Map<String, Any> get() = _features

    val expired: Boolean get() = _expiresAt?.isBefore(LocalDate.now()) == true
    val effectiveTier: String get() = if (expired) "community" else _tier

    @PostConstruct
    fun init() {
        // DB key takes precedence over env var so admins can apply a key
        // via the UI without restarting the application.
        val dbKey = try {
            jdbcTemplate.queryForObject(
                "SELECT licence_key FROM public.licence_config WHERE id = 'default'",
                String::class.java,
            )
        } catch (_: Exception) { null }

        val rawKey = dbKey?.takeIf { it.isNotBlank() } ?: envKey

        if (rawKey.isBlank()) {
            log.info("No licence key configured. Running as Community tier.")
            return
        }

        try {
            val decoded = validateAndDecode(rawKey)
            if (decoded != null) {
                _tier = decoded.tier
                _organisation = decoded.organisation
                _expiresAt = decoded.expiresAt?.let { LocalDate.parse(it) }
                _licenceId = decoded.licenceId
                _maxUsers = decoded.maxUsers
                _features = decoded.features ?: emptyMap()

                if (expired) {
                    log.warn(
                        "Licence '{}' for '{}' expired on {}. Running as Community tier.",
                        _licenceId, _organisation, _expiresAt,
                    )
                } else {
                    log.info(
                        "Licence '{}' validated: tier={}, org={}, expires={}",
                        _licenceId, _tier, _organisation, _expiresAt ?: "never",
                    )
                }
            } else {
                log.warn("Licence key signature verification failed. Running as Community tier.")
            }
        } catch (ex: Exception) {
            log.warn("Failed to parse licence key: {}. Running as Community tier.", ex.message)
        }
    }

    /**
     * Apply a new licence key via the admin UI. Validates the key
     * first; if valid, persists it to the DB and updates the in-memory
     * state. If invalid, throws without changing anything.
     */
    fun applyKey(newKey: String, actorId: UUID?): LicenceSummary {
        val decoded = validateAndDecode(newKey)
            ?: throw IllegalArgumentException("Invalid licence key: signature verification failed")

        // Persist to DB so it survives restarts
        jdbcTemplate.update(
            """
            UPDATE public.licence_config
            SET licence_key = ?, applied_at = ?, applied_by = ?, updated_at = ?
            WHERE id = 'default'
            """,
            newKey, OffsetDateTime.now(), actorId, OffsetDateTime.now(),
        )

        // Update in-memory state
        _tier = decoded.tier
        _organisation = decoded.organisation
        _expiresAt = decoded.expiresAt?.let { LocalDate.parse(it) }
        _licenceId = decoded.licenceId
        _maxUsers = decoded.maxUsers
        _features = decoded.features ?: emptyMap()

        log.info(
            "Licence applied via admin UI: tier={}, org={}, expires={}",
            _tier, _organisation, _expiresAt ?: "never",
        )

        return getSummary()
    }

    /**
     * Returns a summary safe to expose via the REST API and the
     * admin panel. Does not include the raw key or signature.
     */
    fun getSummary(): LicenceSummary = LicenceSummary(
        tier = effectiveTier,
        organisation = organisation,
        licenceId = licenceId,
        expiresAt = expiresAt?.toString(),
        expired = expired,
        maxUsers = maxUsers,
        features = features,
    )

    /**
     * Validate the key signature and decode the payload.
     *
     * Key format: base64(json_payload) + "." + base64(rsa_signature)
     *
     * Returns the decoded claims if valid, null if signature fails.
     */
    private fun validateAndDecode(key: String): LicenceClaims? {
        val parts = key.trim().split(".")
        if (parts.size != 2) {
            log.warn("Licence key format invalid (expected payload.signature)")
            return null
        }

        val payloadBytes = Base64.getUrlDecoder().decode(parts[0])
        val signatureBytes = Base64.getUrlDecoder().decode(parts[1])

        val publicKey = loadPublicKey()
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(publicKey)
        sig.update(payloadBytes)

        if (!sig.verify(signatureBytes)) {
            return null
        }

        return objectMapper.readValue(payloadBytes, LicenceClaims::class.java)
    }

    private fun loadPublicKey(): PublicKey {
        val pem = ClassPathResource("licence/public-key.pem").inputStream
            .bufferedReader().readText()
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(pem)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }
}

// ── DTOs ──────────────────────────────────────────────────

data class LicenceClaims(
    val licenceId: String? = null,
    val organisation: String? = null,
    val tier: String = "community",
    val issuedAt: String? = null,
    val expiresAt: String? = null,
    val maxUsers: Int? = null,
    val features: Map<String, Any>? = null,
)

data class LicenceSummary(
    val tier: String,
    val organisation: String?,
    val licenceId: String?,
    val expiresAt: String?,
    val expired: Boolean,
    val maxUsers: Int?,
    val features: Map<String, Any>,
)
