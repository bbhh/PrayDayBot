package com.biblefoundry.praydaybot.commands

import com.biblefoundry.praydaybot.Config
import com.biblefoundry.praydaybot.ConfigException
import com.biblefoundry.praydaybot.DynamoDBSpec
import com.biblefoundry.praydaybot.TelegramSpec
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.apache.logging.log4j.kotlin.Logging
import kotlin.system.exitProcess

class AppCommand : CliktCommand(), Logging {
    private val configFile: String by option("-c", "--config", help = "Configuration file").default("config.toml")
    private val globalConfig by findOrSetObject { mutableMapOf<String, Any>() }

    override fun run() {
        // load configuration
        val config = try {
            Config(configFile)
        } catch (e: ConfigException) {
            logger.error(e)
            exitProcess(1)
        }

        // set values in context
        globalConfig["TELEGRAM_TOKEN"] = config.config[TelegramSpec.token]
        globalConfig["DYNAMODB_REGION"] = config.config[DynamoDBSpec.region]
        globalConfig["MEMBERS_TABLE_NAME"] = config.config[DynamoDBSpec.membersTableName]
        globalConfig["TELEGRAM_CHAT_ID_INDEX_NAME"] = config.config[DynamoDBSpec.telegramChatIdIndexName]
    }
}