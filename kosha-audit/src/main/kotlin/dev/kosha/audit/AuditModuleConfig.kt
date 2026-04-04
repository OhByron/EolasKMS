package dev.kosha.audit

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [AuditModuleConfig::class])
class AuditModuleConfig
