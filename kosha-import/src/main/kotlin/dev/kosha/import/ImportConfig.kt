package dev.kosha.import

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Infrastructure beans for the bulk importer.
 *
 * This lives in its own @Configuration class (rather than on
 * KoshaImportApplication) to avoid a bean creation cycle: the main
 * application implements CommandLineRunner and depends on
 * BulkImporter → KoshaApiClient → ObjectMapper. If the ObjectMapper
 * @Bean method lived on the application class, Spring would see the
 * application depending on a bean it itself defines and refuse to
 * instantiate the cycle. Splitting the config out resolves it.
 */
@Configuration
class ImportConfig {
    /**
     * Jackson mapper. Normally auto-configured by
     * `spring-boot-starter-json` or `starter-web`, but the CLI pulls
     * in neither to keep the fat jar small. A single explicit @Bean
     * is cheaper than adding a full starter dependency.
     */
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
}
