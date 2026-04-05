package dev.kosha.notification.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.notification.dto.ProviderPreset
import dev.kosha.notification.dto.TestGatewayRequest
import dev.kosha.notification.dto.UpdateMailGatewayRequest
import dev.kosha.notification.preset.ProviderPresets
import dev.kosha.notification.service.MailGatewayConfigService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/mail-gateway")
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
class MailGatewayController(
    private val configService: MailGatewayConfigService,
) {

    @GetMapping
    fun getConfig() = ApiResponse(data = configService.getConfig())

    @PutMapping
    fun updateConfig(@RequestBody request: UpdateMailGatewayRequest) =
        ApiResponse(data = configService.updateConfig(request))

    @PostMapping("/test-connection")
    fun testConnection(@RequestBody request: TestGatewayRequest) =
        ApiResponse(data = configService.testConnection(request))

    @PostMapping("/test-send")
    fun testSend(@RequestBody request: TestGatewayRequest) =
        ApiResponse(data = configService.testSend(request))

    @GetMapping("/presets")
    fun listPresets(): ApiResponse<List<ProviderPreset>> =
        ApiResponse(data = ProviderPresets.ALL)
}
