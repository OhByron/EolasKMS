package dev.kosha.document

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [DocumentModuleConfig::class])
class DocumentModuleConfig
