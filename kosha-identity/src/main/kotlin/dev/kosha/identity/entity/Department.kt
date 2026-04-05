package dev.kosha.identity.entity

import dev.kosha.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.OffsetDateTime

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

    /**
     * Override for scan cadence in hours. NULL = inherit global default from
     * notif.notification_settings. Valid values enforced at the service layer.
     */
    @Column(name = "scan_interval_hours")
    var scanIntervalHours: Int? = null,

    /**
     * Timestamp of the most recent successful retention scan for this department.
     * Updated by [dev.kosha.retention.service.RetentionReviewScanner].
     */
    @Column(name = "last_scan_at")
    var lastScanAt: OffsetDateTime? = null,

    /**
     * When true, members of this department appear in the "Legal reviewer"
     * dropdown on the document upload form. Set by GLOBAL_ADMIN only —
     * DEPT_ADMIN sees this as read-only. Multiple departments can be flagged.
     */
    @Column(name = "handles_legal_review", nullable = false)
    var handlesLegalReview: Boolean = false,
) : BaseEntity()
