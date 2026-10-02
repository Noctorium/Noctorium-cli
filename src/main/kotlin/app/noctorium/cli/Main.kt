package app.noctorium.cli

import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    CliHome.install()
    exitProcess(Commands.run(arguments.toList()))
}
