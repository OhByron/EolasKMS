package dev.kosha.identity.security

import dev.kosha.identity.entity.UserProfile
import dev.kosha.identity.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Authority checks callable from `@PreAuthorize` SpEL expressions.
 *
 * Role-only checks (e.g. `hasRole('GLOBAL_ADMIN')`) can stay as plain SpEL
 * on the controller method. This service exists for the cases where the
 * role check isn't enough on its own — specifically anywhere we need to
 * ask "is the caller the admin of THIS department" or "can the caller
 * edit THIS document". Those questions need a `UserProfile` lookup keyed
 * by the Keycloak JWT subject.
 *
 * ## Usage from a controller
 *
 * ```kotlin
 * @PutMapping
 * @PreAuthorize("@authorityService.canEditDepartment(authentication, #departmentId)")
 * fun updateDepartmentWorkflow(@PathVariable departmentId: UUID, ...)
 * ```
 *
 * The bean lookup syntax (`@authorityService`) is the standard Spring
 * Security SpEL idiom. Method names are kept verb-first so they read as
 * questions in the annotation.
 *
 * ## Design constraints
 *
 * - **Fail closed.** Every method returns `false` when the authentication
 *   is missing, the JWT is malformed, or the caller's profile cannot be
 *   resolved. A null-returning `orElseGet` is never acceptable here —
 *   a missing profile is a denied request, not a permitted one.
 *
 * - **Read-only transactions.** Every method runs in a read-only JPA
 *   transaction. Method-security annotations are evaluated outside of the
 *   caller's transaction by default, and the `UserProfile.department`
 *   relation is lazy — without a transaction, accessing `.department.id`
 *   throws `LazyInitializationException`.
 *
 * - **Service-layer checks stay in service layer.** This class only
 *   answers questions cheap enough to decide at annotation time (does
 *   the JWT claim a role, does the caller's department match a path
 *   parameter). Entity-level ownership questions (am I the document
 *   owner, am I the step assignee) stay where they always have — in the
 *   service method — and throw `AccessDeniedException`.
 */
@Service
class AuthorityService(
    private val userProfileRepo: UserProfileRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * True if the caller has the GLOBAL_ADMIN role. Convenience for SpEL
     * expressions that want to short-circuit dept-scoped checks for a
     * global admin who should just be allowed through.
     */
    fun isGlobalAdmin(authentication: Authentication?): Boolean {
        return hasRole(authentication, "GLOBAL_ADMIN")
    }

    /**
     * True if the caller has DEPT_ADMIN role AND their home department
     * matches the department in question. Also true for GLOBAL_ADMIN
     * regardless of department — global admins can edit any department.
     *
     * Used to gate department workflow editing, per-department scan
     * settings, and dept-scoped user management.
     */
    @Transactional(readOnly = true)
    fun canEditDepartment(authentication: Authentication?, departmentId: UUID): Boolean {
        if (isGlobalAdmin(authentication)) return true
        if (!hasRole(authentication, "DEPT_ADMIN")) return false

        val profile = resolveCaller(authentication) ?: return false
        val isTheirDept = profile.department.id == departmentId
        if (!isTheirDept) {
            log.debug(
                "DEPT_ADMIN {} denied access to department {} (home is {})",
                profile.email, departmentId, profile.department.id,
            )
        }
        return isTheirDept
    }

    /**
     * True if the caller can manage the target user. GLOBAL_ADMIN can
     * manage anyone; DEPT_ADMIN can manage users currently in their own
     * department. Users cannot manage themselves via this path — self
     * service goes through `/api/v1/me/...` endpoints that don't use
     * this check.
     *
     * Note: "currently in" means at the moment of the check. A DEPT_ADMIN
     * who transfers a user out loses the ability to edit them further —
     * exactly the desired behaviour.
     */
    @Transactional(readOnly = true)
    fun canManageUser(authentication: Authentication?, targetUserId: UUID): Boolean {
        if (isGlobalAdmin(authentication)) return true
        if (!hasRole(authentication, "DEPT_ADMIN")) return false

        val caller = resolveCaller(authentication) ?: return false
        val target = userProfileRepo.findById(targetUserId).orElse(null) ?: return false

        // Self-management is intentionally not allowed through this path
        // to keep "admin managing a user" distinct from "user managing
        // their own profile" in audit trails. The /me/ endpoints are the
        // self-service surface.
        if (caller.id == target.id) {
            log.debug("DEPT_ADMIN {} denied self-management via admin path", caller.email)
            return false
        }

        val sameDept = caller.department.id == target.department.id
        if (!sameDept) {
            log.debug(
                "DEPT_ADMIN {} denied managing user {} (not in their dept {})",
                caller.email, target.email, caller.department.id,
            )
        }
        return sameDept
    }

    /**
     * True if the caller may read reports scoped to the given department.
     * Reports are a common case of "GLOBAL_ADMIN sees all, DEPT_ADMIN
     * sees only theirs." The nullable `departmentId` parameter handles
     * the "all departments" query: only GLOBAL_ADMIN may pass null.
     */
    @Transactional(readOnly = true)
    fun canReadReportsFor(authentication: Authentication?, departmentId: UUID?): Boolean {
        if (isGlobalAdmin(authentication)) return true
        if (!hasRole(authentication, "DEPT_ADMIN")) return false
        // DEPT_ADMIN must specify their own department — cannot request
        // the unscoped "all departments" view.
        if (departmentId == null) return false

        val caller = resolveCaller(authentication) ?: return false
        return caller.department.id == departmentId
    }

    /**
     * True if the caller may submit / modify documents on behalf of the
     * given department. GLOBAL_ADMIN can file anywhere; everyone else
     * must be a member of the target department.
     *
     * "Member of" here means their home department equals the target.
     * A future cross-department ACL (see roadmap) would extend this to
     * allow multi-department users, and the check becomes
     * `target ∈ caller.memberships` — but the annotation call site
     * doesn't change.
     */
    @Transactional(readOnly = true)
    fun canFileIntoDepartment(authentication: Authentication?, departmentId: UUID): Boolean {
        if (isGlobalAdmin(authentication)) return true
        val caller = resolveCaller(authentication) ?: return false
        if (caller.status != "ACTIVE") return false
        return caller.department.id == departmentId
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Read a single role from the Keycloak JWT's `roles` claim. Returns
     * false (denied) if authentication is anonymous, if the principal
     * isn't a JWT, or if the claim is missing. Never throws.
     *
     * We prefer reading the claim directly over Spring's
     * `hasAuthority('ROLE_X')` because the claim shape is stable and
     * the `ROLE_` prefix boilerplate stays out of this file.
     */
    private fun hasRole(authentication: Authentication?, role: String): Boolean {
        val auth = authentication ?: return false
        if (!auth.isAuthenticated) return false
        val jwt = auth.principal as? Jwt ?: return false
        val roles = jwt.getClaimAsStringList("roles") ?: return false
        return role in roles
    }

    /**
     * Load the caller's `UserProfile` by their Keycloak subject. Returns
     * null if authentication is missing or the profile has never been
     * provisioned — both cases fail the authority check closed.
     *
     * This method is the reason every consumer must be @Transactional —
     * it touches the `department` relation via the returned entity.
     */
    private fun resolveCaller(authentication: Authentication?): UserProfile? {
        val auth = authentication ?: return null
        val jwt = auth.principal as? Jwt ?: return null
        val keycloakId = try {
            UUID.fromString(jwt.subject)
        } catch (ex: Exception) {
            log.warn("JWT subject is not a UUID: {}", jwt.subject)
            return null
        }
        return userProfileRepo.findByKeycloakId(keycloakId)
    }
}
