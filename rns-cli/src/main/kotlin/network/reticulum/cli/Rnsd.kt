package network.reticulum.cli

/**
 * Main entry point for rnsd-kt - Reticulum Network Stack Daemon.
 *
 * This is the Kotlin equivalent of Python's rnsd utility.
 *
 * Usage:
 *   java -jar rnsd-kt.jar [OPTIONS]
 *
 * Options:
 *   --config PATH      Path to alternative Reticulum config directory
 *   -v, --verbose      Increase verbosity (can be repeated: -vvv)
 *   -q, --quiet        Decrease verbosity (can be repeated: -qqq)
 *   -s, --service      Run as service (log to file)
 *   -i, --interactive  Drop into interactive mode (not yet supported)
 *   --exampleconfig    Print example config to stdout and exit
 *   --version          Show version and exit
 *   -h, --help         Show help and exit
 */
fun main(args: Array<String>) {
    RnsdCommand().main(args)
}
