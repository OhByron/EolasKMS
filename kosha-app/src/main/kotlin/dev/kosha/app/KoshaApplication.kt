package dev.kosha.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication(scanBasePackages = ["dev.kosha"])
@EntityScan(basePackages = ["dev.kosha"])
@EnableJpaRepositories(basePackages = ["dev.kosha"])
@EnableAsync
class KoshaApplication

fun main(args: Array<String>) {
    runApplication<KoshaApplication>(*args)
}
