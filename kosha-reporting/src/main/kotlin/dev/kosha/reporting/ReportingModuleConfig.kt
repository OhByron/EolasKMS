package dev.kosha.reporting

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [ReportingModuleConfig::class])
class ReportingModuleConfig
