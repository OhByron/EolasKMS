package dev.kosha.notification.repository

import dev.kosha.notification.entity.LegalReviewSettings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LegalReviewSettingsRepository : JpaRepository<LegalReviewSettings, String>
