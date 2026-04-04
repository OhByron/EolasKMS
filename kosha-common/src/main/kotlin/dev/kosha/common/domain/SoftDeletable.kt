package dev.kosha.common.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.OffsetDateTime

@MappedSuperclass
abstract class SoftDeletable : BaseEntity() {
    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null

    val isDeleted: Boolean get() = deletedAt != null

    fun softDelete() {
        deletedAt = OffsetDateTime.now()
    }
}
