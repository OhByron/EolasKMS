package dev.kosha.identity.keycloak

import jakarta.annotation.PostConstruct
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.ClientRepresentation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * One-time bootstrap that ensures the `kosha-backend` service account has
 * the `realm-admin` role on the Kosha realm. Without this role, the backend
 * cannot create users via the admin REST API.
 *
 * Flow:
 * 1. Authenticate as master admin (credentials from config).
 * 2. Locate the kosha-backend client's service-account user in the Kosha realm.
 * 3. Check whether the service-account user already has `realm-admin` from the
 *    `realm-management` client.
 * 4. If not, assign it.
 *
 * This runs on every app startup and is idempotent — once the role is assigned
 * it becomes a no-op. The master credentials are only needed for the initial
 * grant; after that the service account operates on its own.
 *
 * Failure here does not prevent the app from starting. If the bootstrap fails
 * (Keycloak not ready, wrong password, etc.) we log loudly and continue, so
 * that the rest of the app remains usable and the operator can retry.
 */
@Component
class KeycloakBootstrap(
    private val props: KeycloakProperties,
    @Qualifier("keycloakMasterClient") private val masterClient: Keycloak,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun ensureServiceAccountHasRealmAdmin() {
        try {
            val realmResource = masterClient.realm(props.realm)

            // 1. Find the service-account user for kosha-backend client.
            //    If the client doesn't exist (e.g. realm JSON was imported before
            //    we added it), create it.
            var clients = realmResource.clients().findByClientId(props.adminClientId)
            if (clients.isEmpty()) {
                log.info(
                    "Keycloak client '{}' not found in realm '{}' — creating it now",
                    props.adminClientId, props.realm
                )
                val newClient = ClientRepresentation().apply {
                    clientId = props.adminClientId
                    name = "Kosha Backend Service Account"
                    description = "Used by the Kosha API to provision and manage users via the admin REST API"
                    isEnabled = true
                    clientAuthenticatorType = "client-secret"
                    secret = props.adminClientSecret
                    isServiceAccountsEnabled = true
                    isStandardFlowEnabled = false
                    isDirectAccessGrantsEnabled = false
                    isPublicClient = false
                    protocol = "openid-connect"
                }
                realmResource.clients().create(newClient).close()
                clients = realmResource.clients().findByClientId(props.adminClientId)
                if (clients.isEmpty()) {
                    log.error("Failed to create Keycloak client '{}' — giving up", props.adminClientId)
                    return
                }
                log.info("Created Keycloak client '{}'", props.adminClientId)
            }
            val clientRepr = clients.first()
            val clientResource = realmResource.clients().get(clientRepr.id)
            val serviceAccountUser = clientResource.serviceAccountUser
                ?: run {
                    log.warn(
                        "Client '{}' has no service account user. Is serviceAccountsEnabled " +
                        "set on the client?", props.adminClientId
                    )
                    return
                }

            // 2. Locate the realm-management client (internal Keycloak client that
            //    holds the realm-admin role)
            val realmMgmtClients = realmResource.clients().findByClientId("realm-management")
            if (realmMgmtClients.isEmpty()) {
                log.error("'realm-management' client not found — this should never happen")
                return
            }
            val realmMgmt = realmMgmtClients.first()
            val realmAdminRole = realmResource.clients().get(realmMgmt.id)
                .roles().get("realm-admin").toRepresentation()

            // 3. Check if the service account already has realm-admin
            val saUser = realmResource.users().get(serviceAccountUser.id)
            val existingClientRoles = saUser.roles().clientLevel(realmMgmt.id).listEffective()
            val alreadyHasRealmAdmin = existingClientRoles.any { it.name == "realm-admin" }

            if (alreadyHasRealmAdmin) {
                log.info("Keycloak bootstrap: service account '{}' already has realm-admin", props.adminClientId)
            } else {
                // 4. Grant realm-admin
                saUser.roles().clientLevel(realmMgmt.id).add(listOf(realmAdminRole))
                log.info(
                    "Keycloak bootstrap: granted realm-admin to service account '{}'",
                    props.adminClientId
                )
            }

            // 5. Ensure the client has the default scopes needed for access
            //    tokens to carry the resource_access claim (which is how
            //    client-level roles like realm-admin reach the token).
            //    Clients created via the admin API don't inherit realm default
            //    scopes automatically — we have to assign them explicitly.
            ensureDefaultClientScopes(realmResource, clientRepr.id)
        } catch (ex: Exception) {
            log.warn(
                "Keycloak bootstrap failed — user provisioning may not work until this is " +
                "resolved. Error: {}. The app will continue to start.",
                ex.message
            )
            log.debug("Keycloak bootstrap stack trace", ex)
        }
    }

    /**
     * Assign the standard default client scopes (roles, web-origins, profile,
     * email, acr) to the kosha-backend client so its access tokens carry the
     * resource_access claim. Idempotent — skips scopes already assigned.
     */
    private fun ensureDefaultClientScopes(
        realmResource: org.keycloak.admin.client.resource.RealmResource,
        clientUuid: String,
    ) {
        val clientResource = realmResource.clients().get(clientUuid)
        val alreadyAssigned = clientResource.defaultClientScopes.map { it.name }.toSet()

        val wanted = listOf("roles", "web-origins", "profile", "email", "acr", "basic")
        val allRealmScopes = realmResource.clientScopes().findAll().associateBy { it.name }

        for (scopeName in wanted) {
            if (scopeName in alreadyAssigned) continue
            val scope = allRealmScopes[scopeName] ?: continue
            try {
                clientResource.addDefaultClientScope(scope.id)
                log.info("Assigned default client scope '{}' to '{}'", scopeName, props.adminClientId)
            } catch (ex: Exception) {
                log.warn("Failed to assign client scope '{}': {}", scopeName, ex.message)
            }
        }
    }
}
