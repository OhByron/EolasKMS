package dev.kosha.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["dev.kosha"])
@EntityScan(basePackages = ["dev.kosha"])
@EnableJpaRepositories(basePackages = ["dev.kosha"])
class KoshaApplication

fun main(args: Array<String>) {
    runApplication<KoshaApplication>(*args)
}
