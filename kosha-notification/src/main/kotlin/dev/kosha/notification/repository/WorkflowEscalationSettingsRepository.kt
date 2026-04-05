package dev.kosha.notification.repository

import dev.kosha.notification.entity.WorkflowEscalationSettings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WorkflowEscalationSettingsRepository : JpaRepository<WorkflowEscalationSettings, String>
