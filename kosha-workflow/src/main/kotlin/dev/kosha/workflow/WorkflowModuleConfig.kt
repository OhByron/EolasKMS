package dev.kosha.workflow

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [WorkflowModuleConfig::class])
class WorkflowModuleConfig
