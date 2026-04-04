package dev.kosha.retention

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [RetentionModuleConfig::class])
class RetentionModuleConfig
