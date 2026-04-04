package dev.kosha.identity

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [IdentityModuleConfig::class])
class IdentityModuleConfig
