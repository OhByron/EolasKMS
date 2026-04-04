package dev.kosha.identity.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "access_group", schema = "ident")
class AccessGroup(
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(name = "external_ref", length = 500)
    var externalRef: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    var department: Department? = null,
) : BaseEntity()
