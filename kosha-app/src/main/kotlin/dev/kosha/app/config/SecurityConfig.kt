package dev.kosha.app.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.slf4j.LoggerFactory
import jakarta.annotation.PostConstruct

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Opt-in bypass chain that skips JWT parsing for a specific set of
     * paths and permits them all unconditionally.
     *
     * ## Why this exists
     *
     * Before Pass 4.3 this bean was always active and caught a long list
     * of mutating endpoints that hadn't been annotated with `@PreAuthorize`
     * yet. Pass 4.3 added annotations to every one of those endpoints,
     * which means in any normally-deployed environment this chain is no
     * longer needed at all. The endpoints are protected by method-level
     * security on the main chain (Order 10) from now on.
     *
     * ## Why it's still here
     *
     * Local development without a running Keycloak instance is painful
     * without a bypass — every request has to carry a real bearer token.
     * Developers who know what they are doing can still flip this on via
     *
     *   kosha.security.dev-bypass.enabled=true
     *
     * in `application-dev.yml` or a local env var, to get the old
     * permissive behaviour for iteration. The default is `false` so
     * production deployments never ship with it open by accident.
     *
     * ## Safety net
     *
     * A `@PostConstruct` logs a loud WARNING at startup whenever the
     * bypass is enabled, so even if someone flips the flag and forgets,
     * it shows up in every deployment log. Never remove that warning.
     */
    @Bean
    @Order(0)
    @ConditionalOnProperty(
        name = ["kosha.security.dev-bypass.enabled"],
        havingValue = "true",
    )
    fun devBypassSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(
                "/api/v1/reports", "/api/v1/reports/*", "/api/v1/reports/**",
                "/api/v1/admin/mail-gateway", "/api/v1/admin/mail-gateway/*", "/api/v1/admin/mail-gateway/**",
                "/api/v1/admin/notification-settings", "/api/v1/admin/notification-settings/*",
                "/api/v1/admin/legal-review-settings", "/api/v1/admin/legal-review-settings/*",
                "/api/v1/admin/workflow-escalation-settings", "/api/v1/admin/workflow-escalation-settings/*",
                "/api/v1/departments/*/scan-settings",
                "/api/v1/departments/*/workflow", "/api/v1/departments/*/workflow/*",
                "/api/v1/users/provision",
                "/api/v1/users/*",
                "/api/v1/users/*/reset-password",
                "/api/v1/departments/*/users",
                "/api/v1/legal-reviewers",
                "/api/v1/document-categories", "/api/v1/document-categories/*",
            )
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        // No oauth2ResourceServer — JWT filter won't run for these requests
        return http.build()
    }

    @Bean
    @Order(10)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/prometheus").permitAll()
                    // Public share link resolution — no auth, the token IS the credential.
                    .requestMatchers(HttpMethod.POST, "/api/v1/share/*").permitAll()
                    // Public licence check and apply — shown before login.
                    .requestMatchers("/api/v1/public/licence").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { it.jwtAuthenticationConverter(KeycloakJwtConverter()) }
            }

        return http.build()
    }
}

/**
 * Logs a loud warning whenever the dev bypass chain is active. This lives
 * as a separate bean so it is only instantiated when the bypass itself
 * is — it piggybacks on the same conditional. Future-you will thank you
 * for this when a production log surprises you with "DEV BYPASS ACTIVE".
 */
@Configuration
@ConditionalOnProperty(
    name = ["kosha.security.dev-bypass.enabled"],
    havingValue = "true",
)
class DevBypassWarning {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun warn() {
        log.warn("╔══════════════════════════════════════════════════════════════════╗")
        log.warn("║ kosha.security.dev-bypass.enabled=true                           ║")
        log.warn("║ A set of admin endpoints bypass JWT auth on this instance.       ║")
        log.warn("║ This must NEVER be true in production.                           ║")
        log.warn("║ Remove or flip kosha.security.dev-bypass.enabled to false in     ║")
        log.warn("║ application.yml or the deployment environment to disable.       ║")
        log.warn("╚══════════════════════════════════════════════════════════════════╝")
    }
}

class KeycloakJwtConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        // Read roles from the flat `roles` claim (configured via a "User Realm
        // Role" mapper on the kosha-backend client) — falling back to
        // `realm_access.roles`, which is Keycloak's default location. This
        // keeps role-based authorisation working on a fresh Keycloak install
        // without requiring the operator to configure the optional flat
        // mapper themselves.
        val flatRoles = jwt.getClaimAsStringList("roles")
        val realmAccessRoles = jwt.getClaim<Map<String, Any>?>("realm_access")
            ?.let { it["roles"] as? List<*> }
            ?.filterIsInstance<String>()
        val roles = (flatRoles ?: realmAccessRoles).orEmpty()
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_$it") }
        return JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"))
    }
}
