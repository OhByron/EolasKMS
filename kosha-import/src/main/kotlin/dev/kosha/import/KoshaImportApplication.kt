package dev.kosha.import

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import kotlin.system.exitProcess

/**
 * Command-line entry point for the Eòlas bulk import tool.
 *
 * This is a Spring Boot application so we get the Jackson mapper, HTTP
 * client, and `@ConfigurationProperties` plumbing for free — but it
 * doesn't start a web server or connect to a database. The CLI runs,
 * does its work, and exits. See `docs/bulk-import.md` for user-facing
 * usage documentation.
 *
 * ## Exit codes
 *
 * - **0** — all rows succeeded (or no rows remained thanks to
 *   resumability via `.import-state.json`)
 * - **1** — partial success: some rows failed but the CLI ran to
 *   completion. The state file lists which rows failed and why.
 * - **2** — dry-run reported validation errors. Nothing was written
 *   to Eòlas. Fix the CSV and re-run.
 * - **3** — fatal error: API unreachable, CSV unparseable, bad
 *   arguments. State file is untouched so a retry resumes cleanly.
 *
 * ## Why a CommandLineRunner
 *
 * Spring Boot's CLI runner fires after the context is up but before
 * the app "waits for a web request" (which we don't do anyway because
 * spring-boot-starter-web is not on the classpath). Throwing from the
 * runner causes a clean shutdown with a nonzero exit code, which is
 * exactly the semantics we want.
 */
// Exclude JPA/datasource auto-configuration — the CLI has no database
// of its own. These classes land on the classpath via transitive deps
// from kosha-common (which pulls in spring-boot-starter) and Spring
// Boot happily tries to wire a HikariDataSource unless told not to.
@SpringBootApplication(
    exclude = [
        DataSourceAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
    ],
)
class KoshaImportApplication(
    private val documentImporter: BulkImporter,
    private val userImporter: BulkUserImporter,
) : CommandLineRunner {

    override fun run(vararg args: String) {
        val opts = try {
            CliOptions.parse(args)
        } catch (ex: IllegalArgumentException) {
            System.err.println(ex.message)
            exitProcess(3)
        }

        // Dispatch by mode. Each importer runs to completion and
        // returns its own exit code which we propagate as the process
        // exit code (see exit-code map in CliOptions kdoc).
        val exitCode = when (opts.mode) {
            ImportMode.DOCUMENTS -> documentImporter.run(opts)
            ImportMode.USERS -> userImporter.run(opts)
        }
        exitProcess(exitCode)
    }
}

fun main(args: Array<String>) {
    val app = SpringApplication(KoshaImportApplication::class.java)
    // No server, no banner, no actuator prattle.
    app.setDefaultProperties(
        mapOf(
            "spring.main.web-application-type" to "none",
            "spring.main.banner-mode" to "off",
            "logging.level.root" to "WARN",
            "logging.level.dev.kosha.import" to "INFO",
            "logging.pattern.console" to "%-5level %msg%n",
        ),
    )
    val ctx: ConfigurableApplicationContext = app.run(*args)
    // If we get here without exitProcess firing, close cleanly.
    ctx.close()
}
