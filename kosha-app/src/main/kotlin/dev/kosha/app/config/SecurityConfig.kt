package dev.kosha.app.config

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
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    // TODO: remove this chain once Keycloak dev tokens are working — these
    //       endpoints should use the main chain with @PreAuthorize role checks.
    @Bean
    @Order(0)
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
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { it.jwtAuthenticationConverter(KeycloakJwtConverter()) }
            }

        return http.build()
    }
}

class KeycloakJwtConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val roles = jwt.getClaimAsStringList("roles").orEmpty()
        val authorities = roles.map { SimpleGrantedAuthority("ROLE_$it") }
        return JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"))
    }
}
