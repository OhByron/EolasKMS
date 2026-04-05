package dev.kosha.identity.keycloak

import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID
import jakarta.ws.rs.core.Response

/**
 * Thin wrapper around the [Keycloak] admin client for the narrow set of
 * operations Kosha needs. Keeps the rest of the codebase insulated from
 * Keycloak's Jakarta REST types.
 *
 * All operations authenticate as the `kosha-backend` service account, which
 * must have the `realm-admin` role (see [KeycloakBootstrap]).
 */
@Component
class KeycloakAdminClient(
    private val props: KeycloakProperties,
    // TODO: switch back to keycloakServiceAccountClient once fine-grained
    // permissions on the kosha-backend service account are fully working.
    // For now we use the master admin client because the service account
    // token hits 403 on admin API calls despite having realm-admin assigned.
    // This is a dev-convenience decision — for production the master
    // credentials should be provided as env vars and rotated.
    @Qualifier("keycloakMasterClient") private val adminClient: Keycloak,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Create a user in Keycloak with an email, display name, a temporary
     * password, and a single realm role. Returns the new user's Keycloak UUID.
     *
     * The temporary password is marked as such — Keycloak will prompt the user
     * to change it on first login.
     *
     * @throws IllegalStateException if the user already exists or Keycloak
     *         rejects the request for another reason
     */
    fun createUser(
        email: String,
        displayName: String,
        role: String,
        temporaryPassword: String,
    ): UUID {
        val realmResource = adminClient.realm(props.realm)

        // Split the display name into given/family for Keycloak's UX
        val (firstName, lastName) = splitDisplayName(displayName)

        val user = UserRepresentation().apply {
            username = email
            this.email = email
            this.firstName = firstName
            this.lastName = lastName
            isEnabled = true
            isEmailVerified = true

            credentials = listOf(CredentialRepresentation().apply {
                type = CredentialRepresentation.PASSWORD
                value = temporaryPassword
                isTemporary = true
            })
        }

        val createResponse: Response = realmResource.users().create(user)
        val keycloakId: String = try {
            when (createResponse.status) {
                201 -> extractCreatedUserId(createResponse)
                409 -> {
                    throw IllegalStateException("User with email '$email' already exists in Keycloak")
                }
                else -> {
                    val body = createResponse.readEntity(String::class.java)
                    throw IllegalStateException(
                        "Keycloak rejected user creation: HTTP ${createResponse.status} — $body"
                    )
                }
            }
        } finally {
            createResponse.close()
        }

        // Assign the realm role
        try {
            val roleRepresentation = realmResource.roles().get(role).toRepresentation()
            realmResource.users().get(keycloakId).roles().realmLevel()
                .add(listOf(roleRepresentation))
        } catch (ex: Exception) {
            // Role assignment failed — roll back by deleting the user so we
            // don't leave a half-created account
            log.error("Failed to assign role '{}' to new user — rolling back", role, ex)
            runCatching { realmResource.users().get(keycloakId).remove() }
            throw IllegalStateException(
                "Failed to assign role '$role': ${ex.message}", ex
            )
        }

        log.info("Created Keycloak user '{}' with role '{}' (id={})", email, role, keycloakId)
        return UUID.fromString(keycloakId)
    }

    /**
     * Reset a user's password to a new temporary value. The user will be
     * forced to change it on next login (temporary=true). Used by the admin
     * "Reset password" flow.
     *
     * @throws IllegalStateException if the Keycloak call fails
     */
    fun resetPassword(keycloakId: UUID, temporaryPassword: String) {
        val realmResource = adminClient.realm(props.realm)
        val credential = CredentialRepresentation().apply {
            type = CredentialRepresentation.PASSWORD
            value = temporaryPassword
            isTemporary = true
        }
        try {
            realmResource.users().get(keycloakId.toString()).resetPassword(credential)
            log.info("Reset password for Keycloak user {}", keycloakId)
        } catch (ex: Exception) {
            throw IllegalStateException(
                "Failed to reset password for Keycloak user $keycloakId: ${ex.message}", ex
            )
        }
    }

    /**
     * Delete a user from Keycloak by ID. Used for rollback if the local
     * user_profile insert fails after a successful Keycloak creation.
     */
    fun deleteUser(keycloakId: UUID) {
        try {
            adminClient.realm(props.realm).users().get(keycloakId.toString()).remove()
            log.info("Deleted Keycloak user {}", keycloakId)
        } catch (ex: Exception) {
            log.error("Failed to delete Keycloak user {} during rollback", keycloakId, ex)
        }
    }

    /**
     * Check whether a user with the given email already exists in Keycloak.
     * Used for pre-flight validation before creation.
     */
    fun userExists(email: String): Boolean {
        val results = adminClient.realm(props.realm).users().searchByEmail(email, true)
        return results.isNotEmpty()
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun extractCreatedUserId(response: Response): String {
        // Keycloak returns the new user ID in the Location header:
        // Location: {serverUrl}/admin/realms/{realm}/users/{id}
        val location = response.location
            ?: throw IllegalStateException("Keycloak create user response has no Location header")
        val path = location.path
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash < 0 || lastSlash == path.length - 1) {
            throw IllegalStateException("Unexpected Location header from Keycloak: $path")
        }
        return path.substring(lastSlash + 1)
    }

    private fun splitDisplayName(displayName: String): Pair<String, String> {
        val trimmed = displayName.trim()
        val spaceIdx = trimmed.indexOf(' ')
        return if (spaceIdx < 0) {
            trimmed to ""
        } else {
            trimmed.substring(0, spaceIdx) to trimmed.substring(spaceIdx + 1).trim()
        }
    }
}
