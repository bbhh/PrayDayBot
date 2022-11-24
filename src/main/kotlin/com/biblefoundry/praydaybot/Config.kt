package com.biblefoundry.praydaybot

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.source.toml

object TelegramSpec : ConfigSpec() {
    val token by required<String>()
}

object DynamoDBSpec : ConfigSpec("dynamodb") {
    val region by required<String>()
    val membersTableName by required<String>()
    val telegramChatIdIndexName by required<String>()
}

class ConfigException(message: String) : Exception(message)

class Config(configFile: String) {
    val config = Config {
        addSpec(TelegramSpec)
        addSpec(DynamoDBSpec)
    }
        .from.toml.file(configFile)
        .from.env()
        .from.systemProperties()
}