package com.biblefoundry.praydaybot

import com.biblefoundry.praydaybot.commands.AppCommand
import com.biblefoundry.praydaybot.commands.ServeCommand
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = AppCommand().subcommands(
        ServeCommand(),
    ).main(args)