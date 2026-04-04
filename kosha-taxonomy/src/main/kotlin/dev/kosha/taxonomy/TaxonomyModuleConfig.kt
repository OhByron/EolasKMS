package dev.kosha.taxonomy

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [TaxonomyModuleConfig::class])
class TaxonomyModuleConfig
