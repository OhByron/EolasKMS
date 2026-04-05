package dev.kosha.app.config

import dev.kosha.identity.entity.UserProfile
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import java.util.UUID

@Service
class UserProvisioningService(
    private val userProfileRepo: UserProfileRepository,
    private val departmentRepo: DepartmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val defaultDeptId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Transactional
    fun ensureProvisioned() {
        val auth = SecurityContextHolder.getContext().authentication ?: return
        if (auth !is JwtAuthenticationToken) return

        val jwt = auth.token
        val keycloakId = try {
            UUID.fromString(jwt.subject)
        } catch (_: Exception) {
            return
        }

        if (userProfileRepo.findByKeycloakId(keycloakId) != null) return

        val roles = jwt.getClaimAsStringList("roles").orEmpty()
        val role = when {
            "GLOBAL_ADMIN" in roles -> "GLOBAL_ADMIN"
            "DEPT_ADMIN" in roles -> "DEPT_ADMIN"
            "EDITOR" in roles -> "EDITOR"
            else -> "CONTRIBUTOR"
        }

        val name = jwt.getClaimAsString("preferred_username")
            ?: jwt.getClaimAsString("name")
            ?: jwt.subject

        val email = jwt.getClaimAsString("email") ?: "$name@eolaskms.local"

        val dept = departmentRepo.findById(defaultDeptId).orElse(null) ?: run {
            log.warn("Default department not found for auto-provisioning")
            return
        }

        val user = UserProfile(
            keycloakId = keycloakId,
            displayName = name,
            email = email,
            department = dept,
            role = role,
        )
        userProfileRepo.save(user)
        log.info("Auto-provisioned user {} ({}) with role {}", name, keycloakId, role)
    }
}

@ControllerAdvice
class UserProvisioningAdvice(
    private val provisioningService: UserProvisioningService,
) {
    @ModelAttribute
    fun ensureUserProvisioned() {
        try {
            provisioningService.ensureProvisioned()
        } catch (_: Exception) {
            // Don't block the request if provisioning fails
        }
    }
}
