package dev.kosha.reporting.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.reporting.service.ReportingService
import dev.kosha.retention.service.RetentionReviewScanner
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reports")
// TODO: restore @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')") after Keycloak dev setup
class ReportController(
    private val reportingService: ReportingService,
    private val scannerRef: RetentionReviewScanner,
) {

    // ── Aging Report ─────────────────────────────────────────────

    @GetMapping("/aging")
    fun agingReport(
        @RequestParam(required = false) departmentId: UUID?,
        @RequestParam(required = false) status: String?,
        @PageableDefault(size = 50) pageable: Pageable,
    ) = reportingService.agingReport(departmentId, status, pageable).let { page ->
        ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/reports/aging?page=${page.number}&size=${page.size}"),
        )
    }

    @GetMapping("/aging/summary")
    fun agingReportSummary(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = reportingService.agingReportSummary(departmentId))

    // ── Critical Items ───────────────────────────────────────────

    @GetMapping("/critical-items")
    fun criticalItems(
        @RequestParam(required = false) departmentId: UUID?,
        @RequestParam(required = false) minDaysOverdue: Int?,
        @PageableDefault(size = 50) pageable: Pageable,
    ) = reportingService.criticalItems(departmentId, minDaysOverdue, pageable).let { page ->
        ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/reports/critical-items?page=${page.number}&size=${page.size}"),
        )
    }

    @GetMapping("/critical-items/summary")
    fun criticalItemsSummary(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = reportingService.criticalItemsSummary(departmentId))

    @PostMapping("/critical-items/notify")
    fun notifySelected(
        @RequestBody body: NotifyRequest,
    ) = ApiResponse(data = mapOf("notified" to reportingService.notifySelected(body.reviewIds)))

    @PostMapping("/critical-items/notify-all")
    fun notifyAll(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = mapOf("notified" to reportingService.notifyAll(departmentId)))

    /**
     * Manually trigger a full retention scan tick (approaching + critical).
     * Useful for testing the notification pipeline without waiting for the cron.
     * Honours per-department scan intervals — departments whose interval has
     * not elapsed will be skipped.
     */
    @PostMapping("/critical-items/scan-approaching")
    fun triggerScan(): ApiResponse<Map<String, String>> {
        scannerRef.scanTick()
        return ApiResponse(data = mapOf("status" to "scan complete"))
    }

    // ── Legal Holds ──────────────────────────────────────────────

    @GetMapping("/legal-holds")
    fun legalHolds(
        @RequestParam(required = false) departmentId: UUID?,
        @PageableDefault(size = 50) pageable: Pageable,
    ) = reportingService.legalHolds(departmentId, pageable).let { page ->
        ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/reports/legal-holds?page=${page.number}&size=${page.size}"),
        )
    }

    @GetMapping("/legal-holds/summary")
    fun legalHoldSummary(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = reportingService.legalHoldSummary(departmentId))
}

data class NotifyRequest(val reviewIds: List<UUID>)
