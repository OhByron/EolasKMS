package dev.kosha.identity.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "user_profile", schema = "ident")
class UserProfile(
    @Column(name = "keycloak_id", nullable = false, unique = true)
    var keycloakId: UUID,

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String,

    @Column(nullable = false, length = 255)
    var email: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    var department: Department,

    @Column(nullable = false, length = 30)
    var role: String, // GLOBAL_ADMIN, DEPT_ADMIN, EDITOR, CONTRIBUTOR

    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE",

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_group_membership",
        schema = "ident",
        joinColumns = [JoinColumn(name = "user_profile_id")],
        inverseJoinColumns = [JoinColumn(name = "access_group_id")],
    )
    var groups: MutableSet<AccessGroup> = mutableSetOf(),
) : BaseEntity()
