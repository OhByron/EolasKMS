package dev.kosha.retention.entity

import dev.kosha.common.domain.BaseEntity
import dev.kosha.identity.entity.Department
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "retention_policy", schema = "ret")
class RetentionPolicy(
    @Column(nullable = false, length = 200)
    var name: String,

    var description: String? = null,

    @Column(name = "retention_period", nullable = false, length = 50)
    var retentionPeriod: String,

    @Column(name = "review_interval", length = 50)
    var reviewInterval: String? = null,

    @Column(name = "action_on_expiry", nullable = false, length = 30)
    var actionOnExpiry: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    var department: Department? = null,

    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE",
) : BaseEntity()
