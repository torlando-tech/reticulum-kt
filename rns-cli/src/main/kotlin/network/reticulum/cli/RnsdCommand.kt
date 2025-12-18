package network.reticulum.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import network.reticulum.cli.daemon.DaemonRunner

/**
 * rnsd-kt command matching Python rnsd CLI exactly.
 *
 * Usage:
 *   rnsd-kt [OPTIONS]
 *
 * Options:
 *   --config PATH      Path to alternative Reticulum config directory
 *   -v, --verbose      Increase verbosity (can be repeated: -vvv)
 *   -q, --quiet        Decrease verbosity (can be repeated: -qqq)
 *   -s, --service      Run as service (log to file)
 *   -i, --interactive  Drop into interactive mode after init (not supported)
 *   --exampleconfig    Print example config to stdout and exit
 *   --version          Show version and exit
 *   -h, --help         Show help and exit
 */
class RnsdCommand : CliktCommand(
    name = "rnsd-kt",
    help = "Reticulum Network Stack Daemon"
) {
    init {
        versionOption(VERSION, names = setOf("--version"))
    }

    private val config: String? by option(
        "--config",
        help = "path to alternative Reticulum config directory"
    )

    private val verbose: Int by option("-v", "--verbose")
        .counted()

    private val quiet: Int by option("-q", "--quiet")
        .counted()

    private val service: Boolean by option("-s", "--service")
        .flag(default = false)

    private val interactive: Boolean by option("-i", "--interactive")
        .flag(default = false)

    private val exampleConfig: Boolean by option("--exampleconfig")
        .flag(default = false)

    override fun run() {
        // Handle --exampleconfig
        if (exampleConfig) {
            echo(EXAMPLE_RNS_CONFIG)
            throw ProgramResult(0)
        }

        // Create and run the daemon
        val runner = DaemonRunner(
            configDir = config,
            verbosity = verbose,
            quietness = quiet,
            service = service,
            interactive = interactive
        )

        runner.run()
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
