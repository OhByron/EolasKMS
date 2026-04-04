package dev.kosha.notification

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [NotificationModuleConfig::class])
class NotificationModuleConfig
