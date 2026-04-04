package dev.kosha.search

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [SearchModuleConfig::class])
class SearchModuleConfig
