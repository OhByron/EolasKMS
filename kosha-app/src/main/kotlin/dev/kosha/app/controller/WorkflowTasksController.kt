package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.PageMeta
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/my")
class WorkflowTasksController {

    @GetMapping("/workflow-tasks")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR')")
    fun myTasks(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<Any>> {
        // Workflow task assignment will be implemented when the workflow engine is built.
        // For now, return an empty list.
        return ApiResponse(
            data = emptyList(),
            meta = PageMeta(page = page, size = size, total = 0),
        )
    }
}
