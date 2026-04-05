package dev.kosha.identity.keycloak

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the Keycloak admin client used by the backend to provision
 * users, assign roles, and manage realm membership.
 *
 * All properties come from the `kosha.keycloak.*` section of application.yml.
 */
@ConfigurationProperties(prefix = "kosha.keycloak")
data class KeycloakProperties(
    /** Base URL Keycloak is running at, e.g. http://localhost:8180 (no trailing /realms). */
    val serverUrl: String = "http://localhost:8180",

    /** The realm Kosha users live in. */
    val realm: String = "kosha",

    /** The confidential client the backend uses as a service account. */
    val adminClientId: String = "kosha-backend",

    /** Client secret for the service account. */
    val adminClientSecret: String = "kosha-backend-dev-secret",

    /**
     * Master realm admin username — used ONLY by the one-time bootstrap step
     * that grants realm-admin to the service account. Safe to omit in production
     * once the service account is set up.
     */
    val masterUsername: String = "admin",

    /** Master realm admin password — see [masterUsername]. */
    val masterPassword: String = "admin",
)
