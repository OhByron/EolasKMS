package dev.kosha.identity.keycloak

import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires up the two [Keycloak] admin clients used by Kosha:
 *
 * - **Service account client** — authenticated as the `kosha-backend` confidential
 *   client via client_credentials grant. Scoped to the Kosha realm with
 *   realm-admin privileges. This is the one used for all routine user
 *   management operations.
 *
 * - **Master client** — authenticated as the master realm admin via password
 *   grant. Used ONLY by [KeycloakBootstrap] to assign realm-admin to the
 *   service account on first run.
 *
 * Bean names are explicit so they don't collide with the `KeycloakAdminClient`
 * component class (whose default bean name would be `keycloakAdminClient`).
 */
@Configuration
@EnableConfigurationProperties(KeycloakProperties::class)
class KeycloakConfig(private val props: KeycloakProperties) {

    @Bean("keycloakServiceAccountClient")
    fun keycloakServiceAccountClient(): Keycloak = KeycloakBuilder.builder()
        .serverUrl(props.serverUrl)
        .realm(props.realm)
        .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
        .clientId(props.adminClientId)
        .clientSecret(props.adminClientSecret)
        .build()

    @Bean("keycloakMasterClient")
    fun keycloakMasterClient(): Keycloak = KeycloakBuilder.builder()
        .serverUrl(props.serverUrl)
        .realm("master")
        .grantType(OAuth2Constants.PASSWORD)
        .clientId("admin-cli")
        .username(props.masterUsername)
        .password(props.masterPassword)
        .build()
}
