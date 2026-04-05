package dev.kosha.identity.service

import dev.kosha.common.event.UserPasswordReset
import dev.kosha.identity.dto.PasswordResetResponse
import dev.kosha.identity.dto.ProvisionUserRequest
import dev.kosha.identity.dto.ProvisionedUserResponse
import dev.kosha.identity.dto.UserProfileResponse
import dev.kosha.identity.entity.UserProfile
import dev.kosha.identity.keycloak.KeycloakAdminClient
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.UUID

/**
 * Orchestrates end-to-end user provisioning across Keycloak and Kosha's
 * local `ident.user_profile` table.
 *
 * Flow:
 * 1. Validate department exists and role is allowed
 * 2. Check no existing user_profile or Keycloak user with that email
 * 3. Generate a temporary password if none supplied
 * 4. Create Keycloak user (with temp password + role assigned)
 * 5. Create local user_profile row (in a REQUIRES_NEW transaction so we can
 *    roll back without unwinding caller's transaction)
 * 6. If local insert fails, delete the Keycloak user
 * 7. Return the profile + temporary password
 *
 * The temporary password is returned to the caller ONCE and is never stored
 * in Kosha's database. The admin is responsible for sharing it securely.
 */
@Service
class UserCreationService(
    private val userProfileRepo: UserProfileRepository,
    private val departmentRepo: DepartmentRepository,
    private val keycloak: KeycloakAdminClient,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Roles a user can be created with. Excludes internal/synthetic roles. */
        val VALID_ROLES = setOf("GLOBAL_ADMIN", "DEPT_ADMIN", "EDITOR", "CONTRIBUTOR")

        /** Length of generated passwords (16 chars = ~95 bits of entropy). */
        private const val GENERATED_PASSWORD_LENGTH = 16

        /** Alphabet for generated passwords — avoids ambiguous chars (0/O, 1/l/I). */
        private const val PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*"
    }

    /**
     * Provision a new user. Throws on validation failure or if either of the
     * two systems rejects the operation.
     */
    fun provision(request: ProvisionUserRequest): ProvisionedUserResponse {
        validateRequest(request)

        val deptId = request.departmentId
            ?: throw IllegalArgumentException("departmentId is required")
        val department = departmentRepo.findById(deptId)
            .orElseThrow { NoSuchElementException("Department not found: $deptId") }

        // Pre-flight: check both systems for email collision before we create anything
        if (userProfileRepo.findByEmail(request.email) != null) {
            throw IllegalStateException("A Kosha user with email '${request.email}' already exists")
        }
        if (keycloak.userExists(request.email)) {
            throw IllegalStateException("A Keycloak user with email '${request.email}' already exists")
        }

        val temporaryPassword = request.temporaryPassword?.takeIf { it.isNotBlank() }
            ?: generatePassword()

        // Create in Keycloak first. If this fails, nothing has been persisted
        // locally so there's nothing to roll back.
        val keycloakId = try {
            keycloak.createUser(
                email = request.email,
                displayName = request.displayName,
                role = request.role,
                temporaryPassword = temporaryPassword,
            )
        } catch (ex: Exception) {
            log.error("Keycloak provisioning failed for '{}': {}", request.email, ex.message)
            throw ex
        }

        // Create the local user_profile row. If this fails we must delete the
        // Keycloak user to avoid a dangling account.
        val profile = try {
            createLocalProfile(request, department.id!!, keycloakId)
        } catch (ex: Exception) {
            log.error(
                "Local user_profile insert failed for '{}' after Keycloak creation — rolling back",
                request.email, ex
            )
            keycloak.deleteUser(keycloakId)
            throw IllegalStateException("Failed to create local user profile: ${ex.message}", ex)
        }

        log.info(
            "Provisioned user '{}' ({}) in department '{}' with role '{}'",
            request.email, keycloakId, department.name, request.role
        )

        return ProvisionedUserResponse(
            user = profile,
            temporaryPassword = temporaryPassword,
            mustChangePasswordOnFirstLogin = true,
        )
    }

    /**
     * Admin-initiated password reset.
     *
     * Generates a new temporary password, replaces the user's credential in
     * Keycloak, and fires [UserPasswordReset] so the notification module
     * emails the new password to the user. The password is also returned to
     * the calling admin as a safety net in case email delivery fails.
     *
     * Does not touch the local `user_profile` row — password storage is
     * entirely in Keycloak.
     */
    fun resetPassword(userId: UUID, actorId: UUID?): PasswordResetResponse {
        val user = userProfileRepo.findById(userId)
            .orElseThrow { NoSuchElementException("User not found: $userId") }

        val newPassword = generatePassword()
        keycloak.resetPassword(user.keycloakId, newPassword)

        events.publishEvent(
            UserPasswordReset(
                aggregateId = user.id!!,
                userEmail = user.email,
                userDisplayName = user.displayName,
                departmentName = user.department.name,
                temporaryPassword = newPassword,
                actorId = actorId,
            )
        )

        log.info("Reset password for user {} ({})", user.email, user.id)

        return PasswordResetResponse(
            user = UserProfileResponse(
                id = user.id!!,
                keycloakId = user.keycloakId,
                displayName = user.displayName,
                email = user.email,
                departmentId = user.department.id!!,
                departmentName = user.department.name,
                role = user.role,
                status = user.status,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt,
            ),
            temporaryPassword = newPassword,
            emailDispatched = true,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Local profile creation runs in its own transaction so that a rollback
     * here does not affect the caller's transaction. The caller is expected
     * to NOT be inside a transaction when calling provision() because
     * Keycloak operations are non-transactional.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    internal fun createLocalProfile(
        request: ProvisionUserRequest,
        departmentId: UUID,
        keycloakId: UUID,
    ): UserProfileResponse {
        val department = departmentRepo.findById(departmentId).orElseThrow()
        val profile = UserProfile(
            keycloakId = keycloakId,
            displayName = request.displayName,
            email = request.email,
            department = department,
            role = request.role,
        )
        val saved = userProfileRepo.save(profile)
        return UserProfileResponse(
            id = saved.id!!,
            keycloakId = saved.keycloakId,
            displayName = saved.displayName,
            email = saved.email,
            departmentId = department.id!!,
            departmentName = department.name,
            role = saved.role,
            status = saved.status,
            createdAt = saved.createdAt,
            updatedAt = saved.updatedAt,
        )
    }

    private fun validateRequest(request: ProvisionUserRequest) {
        require(request.displayName.isNotBlank()) { "Display name is required" }
        require(request.email.isNotBlank()) { "Email is required" }
        require(EMAIL_PATTERN.matches(request.email)) { "Invalid email address" }
        require(request.role in VALID_ROLES) {
            "Invalid role '${request.role}'. Must be one of: $VALID_ROLES"
        }
        request.temporaryPassword?.let {
            if (it.isNotBlank()) {
                require(it.length >= 8) { "Temporary password must be at least 8 characters" }
            }
        }
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        return buildString(GENERATED_PASSWORD_LENGTH) {
            repeat(GENERATED_PASSWORD_LENGTH) {
                append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)])
            }
        }
    }
}

private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
