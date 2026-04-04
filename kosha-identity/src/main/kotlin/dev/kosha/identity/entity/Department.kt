package dev.kosha.identity.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "department", schema = "ident")
class Department(
    @Column(nullable = false, length = 200)
    var name: String,

    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_user_id")
    var manager: UserProfile? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_dept_id")
    var parent: Department? = null,

    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE",
) : BaseEntity()
