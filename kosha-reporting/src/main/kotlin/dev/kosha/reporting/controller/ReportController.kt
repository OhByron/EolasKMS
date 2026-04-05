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

/**
 * Reports are GLOBAL_ADMIN globally, or DEPT_ADMIN scoped to their own
 * department. Class-level annotation keeps it simple — the dept-scope
 * check is additional on methods that accept a departmentId. When a
 * DEPT_ADMIN calls an endpoint without specifying their departmentId we
 * could either (a) inject it server-side or (b) deny. Today the service
 * layer doesn't filter by caller dept, so we take the conservative path:
 * DEPT_ADMIN must supply their own departmentId via the query param and
 * the annotation verifies it. Global admins may omit it to see all.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val reportingService: ReportingService,
    private val scannerRef: RetentionReviewScanner,
) {

    // ── Aging Report ─────────────────────────────────────────────

    @GetMapping("/aging")
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
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
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
    fun agingReportSummary(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = reportingService.agingReportSummary(departmentId))

    // ── Critical Items ───────────────────────────────────────────

    @GetMapping("/critical-items")
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
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
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
    fun criticalItemsSummary(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = reportingService.criticalItemsSummary(departmentId))

    // Notifying specific reviews is GLOBAL_ADMIN only for v1 — the endpoint
    // doesn't currently enforce that a DEPT_ADMIN's selected review IDs all
    // belong to their own department, and we don't want a misconfigured
    // dept admin spamming other departments' owners. Tighten later.
    @PostMapping("/critical-items/notify")
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun notifySelected(
        @RequestBody body: NotifyRequest,
    ) = ApiResponse(data = mapOf("notified" to reportingService.notifySelected(body.reviewIds)))

    @PostMapping("/critical-items/notify-all")
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
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
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun triggerScan(): ApiResponse<Map<String, String>> {
        scannerRef.scanTick()
        return ApiResponse(data = mapOf("status" to "scan complete"))
    }

    // ── Legal Holds ──────────────────────────────────────────────

    @GetMapping("/legal-holds")
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
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
    @PreAuthorize("@authorityService.canReadReportsFor(authentication, #departmentId)")
    fun legalHoldSummary(
        @RequestParam(required = false) departmentId: UUID?,
    ) = ApiResponse(data = reportingService.legalHoldSummary(departmentId))
}

data class NotifyRequest(val reviewIds: List<UUID>)
