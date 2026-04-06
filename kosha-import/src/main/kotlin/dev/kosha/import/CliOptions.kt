package dev.kosha.import

/**
 * Parsed command-line options for the bulk importer. Kept as a simple
 * data class because we don't want a full CLI framework (picocli etc.)
 * for five flags. The parser throws [IllegalArgumentException] on
 * unknown or malformed args, which the main runner catches and turns
 * into exit code 3.
 *
 * ## Recognised flags
 *
 * | Flag          | Required | Description                                                        |
 * |---------------|:--------:|--------------------------------------------------------------------|
 * | `--csv`       |    Yes   | Path to the metadata CSV manifest                                  |
 * | `--root`      |    Yes   | Directory containing the files referenced by `file_path` columns   |
 * | `--api-url`   |    Yes   | Base URL of the Eòlas API, e.g. `http://localhost:8081`            |
 * | `--token`     |    Yes   | Bearer token for a global admin                                    |
 * | `--dry-run`   |    No    | Validate only; no writes                                           |
 * | `--state`     |    No    | State file path (default: `.import-state.json` in cwd)             |
 * | `--help`      |    No    | Print usage and exit                                               |
 */
/**
 * Two import modes:
 *   - DOCUMENTS (default): requires --root, creates documents + versions
 *     + uploads bytes for every row
 *   - USERS: no --root needed, provisions users via the Keycloak-sync
 *     provision endpoint for every row
 *
 * The same CLI binary handles both; `--mode users` switches the top-
 * level behaviour at startup. Each mode has its own CSV schema and
 * its own validation endpoint on the backend.
 */
enum class ImportMode { DOCUMENTS, USERS }

data class CliOptions(
    val mode: ImportMode,
    val csvPath: String,
    val rootDir: String?,
    val apiUrl: String,
    val token: String,
    val dryRun: Boolean,
    val statePath: String,
    /**
     * 4.2.2 flag. When set with --mode documents, rows referencing an
     * unknown owner_email are accepted, the missing user is created
     * just-in-time with a generated temp password before the document
     * is created, and the temp password is written to stdout/log for
     * the admin to communicate. Has no effect with --mode users.
     */
    val autoProvisionOwners: Boolean,
) {
    companion object {
        private const val USAGE = """
Eòlas bulk import

Usage:
  kosha-import --csv <path> [--mode documents|users] --api-url <url> --token <jwt> [options]

Options:
  --mode      documents (default) | users
  --csv       Path to the metadata CSV manifest (required)
  --root      Directory containing the files referenced by file_path
              (required for --mode documents, ignored for --mode users)
  --api-url   Base URL of the Eòlas API, e.g. http://localhost:8081 (required)
  --token     Bearer token for a global admin (required)
  --dry-run           Validate only; no writes
  --auto-provision    With --mode documents: create missing owners on the fly
                      instead of failing the row. Temp passwords are logged and
                      stored in the state file.
  --state             State file path for resume support
                      (default: .import-state.json for documents,
                       .user-import-state.json for users)
  --help              Print this message
        """

        fun parse(args: Array<out String>): CliOptions {
            val map = mutableMapOf<String, String>()
            var dryRun = false
            var autoProvision = false
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when (a) {
                    "--help", "-h" -> {
                        println(USAGE.trimIndent())
                        kotlin.system.exitProcess(0)
                    }
                    "--dry-run" -> dryRun = true
                    "--auto-provision" -> autoProvision = true
                    "--mode", "--csv", "--root", "--api-url", "--token", "--state" -> {
                        require(i + 1 < args.size) { "missing value for $a" }
                        map[a.removePrefix("--")] = args[i + 1]
                        i++
                    }
                    else -> throw IllegalArgumentException("unknown argument: $a")
                }
                i++
            }

            val mode = when (val m = map["mode"]?.lowercase() ?: "documents") {
                "documents" -> ImportMode.DOCUMENTS
                "users" -> ImportMode.USERS
                else -> throw IllegalArgumentException("--mode must be 'documents' or 'users', got: $m")
            }

            fun req(key: String): String = map[key]
                ?: throw IllegalArgumentException("--$key is required\n${USAGE.trimIndent()}")

            val rootDir = if (mode == ImportMode.DOCUMENTS) req("root") else map["root"]
            val defaultState = when (mode) {
                ImportMode.DOCUMENTS -> ".import-state.json"
                ImportMode.USERS -> ".user-import-state.json"
            }

            return CliOptions(
                mode = mode,
                csvPath = req("csv"),
                rootDir = rootDir,
                apiUrl = req("api-url").trimEnd('/'),
                token = req("token"),
                dryRun = dryRun,
                statePath = map["state"] ?: defaultState,
                autoProvisionOwners = autoProvision,
            )
        }
    }
}
