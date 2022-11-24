package com.biblefoundry.praydaybot.commands

import com.biblefoundry.praydaybot.services.DatabaseService
import com.biblefoundry.praydaybot.services.UserStatus
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.contact
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.handlers.CommandHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.handlers.ContactHandlerEnvironment
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.kotlin.Logging

private const val MIN_NUM_MEMBERS = 1
private const val MAX_NUM_MEMBERS = 5

class ServeCommand : CliktCommand(), Logging {
    private val config by requireObject<Map<String, Any>>()
    private lateinit var databaseService: DatabaseService

    override fun run() {
        logger.info("Running PrayDayBot...")

        val telegramToken = config["TELEGRAM_TOKEN"] as String
        databaseService = DatabaseService(
            config["DYNAMODB_REGION"] as String,
            config["MEMBERS_TABLE_NAME"] as String,
            config["TELEGRAM_CHAT_ID_INDEX_NAME"] as String
        )

        val bot = bot {
            token = telegramToken

            dispatch {
                command("start") {
                    handleStartCommand()
                }
                command("help") {
                    handleHelpCommand()
                }
                command("settings") {
                    handleSettingsCommand()
                }

                command("enable") {
                    handleEnableCommand()
                }
                command("disable") {
                    handleDisableCommand()
                }

                command("settime") {
                    handleSetTimeCommand()
                }
                command("setnumber") {
                    handleSetNumberCommand()
                }

                contact {
                    handleContact()
                }

                callbackQuery("callbackSetTime") {
                    callbackSetTime()
                }
                callbackQuery("callbackSetNumber") {
                    callbackSetNumber()
                }
            }
        }

        bot.startPolling()
    }

    private fun ContactHandlerEnvironment.handleContact() = runBlocking {
        logger.info("Checking for user with phone number ${contact.phoneNumber}...")
        val userStatus = databaseService.checkUserStatus(contact.phoneNumber)
        val messageText = when (userStatus) {
            UserStatus.UNRECOGNIZED -> "Sorry, I don't recognize your phone number."
            UserStatus.SUBSCRIBED -> "Hello, ${contact.firstName}! It looks like you've already subscribed, welcome back!"
            UserStatus.UNSUBSCRIBED -> "Hello, ${contact.firstName}! It looks like you haven't subscribed yet, would you like to subscribe?"
        }

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id), text = messageText, replyMarkup = ReplyKeyboardRemove()
        )

        // Save chat ID -> phone number mapping
        if (userStatus != UserStatus.UNRECOGNIZED) {
            logger.info("Saving chat ID ${message.chat.id} to phone number ${contact.phoneNumber}...")
            databaseService.saveUserChatId(contact.phoneNumber, message.chat.id)
        }
    }

    private fun CommandHandlerEnvironment.handleStartCommand() {
        val keyboardMarkup = KeyboardReplyMarkup(
            keyboard = listOf(listOf(KeyboardButton("Share my phone number", requestContact = true))),
            resizeKeyboard = true
        )
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "Hello from PrayDay! Please click the \"Share my phone number\" button below to register.",
            replyMarkup = keyboardMarkup
        )
    }

    private fun handleHelpCommand() {
        TODO("Not yet implemented")
    }

    private fun handleSettingsCommand() {
        TODO("Not yet implemented")
    }

    private fun CommandHandlerEnvironment.handleEnableCommand() = runBlocking {
        logger.info("Enabling service for user with chat ID ${message.chat.id}...")

        val userMapping = databaseService.getUserByChatId(message.chat.id) ?: return@runBlocking
        databaseService.setUserSubscribed(userMapping.phoneNumber, true)

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id), text = "Hooray! You'll now start receiving daily prayer reminders."
        )
    }

    private fun CommandHandlerEnvironment.handleDisableCommand() = runBlocking {
        logger.info("Disabling service for user with chat ID ${message.chat.id}...")

        val userMapping = databaseService.getUserByChatId(message.chat.id) ?: return@runBlocking
        databaseService.setUserSubscribed(userMapping.phoneNumber, false)

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "Sad to see you go! You'll no longer be receiving daily prayer reminders."
        )
    }

    private fun generateHoursWithSuffix(suffix: String): List<String> {
        val hours = mutableListOf("12${suffix}")
        for (i in 1..11) {
            hours.add("${i}${suffix}")
        }
        return hours
    }

    private fun CommandHandlerEnvironment.handleSetTimeCommand() {
        val hours = generateHoursWithSuffix("am") + generateHoursWithSuffix("pm")

        val buttons = hours.map { hour ->
            listOf(InlineKeyboardButton.CallbackData(text = hour, callbackData = "callbackSetTime#${hour}"))
        }

        val inlineKeyboardMarkup = InlineKeyboardMarkup.create(buttons)
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "At what time each day would you like to receive the prayer reminder?",
            replyMarkup = inlineKeyboardMarkup
        )
    }

    private fun CallbackQueryHandlerEnvironment.callbackSetTime() = runBlocking {
        val chatId = callbackQuery.message?.chat?.id ?: return@runBlocking
        val reminderTime = callbackQuery.data.split("#")[1]

        logger.info("Setting reminder time for user with chat ID $chatId to $reminderTime...")

        val userMapping = databaseService.getUserByChatId(chatId) ?: return@runBlocking
        databaseService.setUserReminderTime(userMapping.phoneNumber, reminderTime)

        bot.sendMessage(
            ChatId.fromId(chatId),
            "${getRandomAffirmativeExpression()} From now on, you'll be receiving the prayer reminder at $reminderTime each day."
        )
    }

    private fun CommandHandlerEnvironment.handleSetNumberCommand() {
        val buttons = (MIN_NUM_MEMBERS until MAX_NUM_MEMBERS + 1).map { number ->
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = number.toString(), callbackData = "callbackSetNumber#${number}"
                )
            )
        }

        val inlineKeyboardMarkup = InlineKeyboardMarkup.create(buttons)
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "How many members would you like to pray for each day?",
            replyMarkup = inlineKeyboardMarkup
        )
    }

    private fun CallbackQueryHandlerEnvironment.callbackSetNumber() = runBlocking {
        val chatId = callbackQuery.message?.chat?.id ?: return@runBlocking
        val memberCount = callbackQuery.data.split("#")[1].toInt()
        val pluralEnding = if (memberCount == 1) "" else "s"

        logger.info("Setting member count for user with chat ID $chatId to $memberCount...")

        val userMapping = databaseService.getUserByChatId(chatId) ?: return@runBlocking
        databaseService.setUserMemberCount(userMapping.phoneNumber, memberCount)

        bot.sendMessage(
            ChatId.fromId(chatId),
            "${getRandomAffirmativeExpression()} From now on, you'll be receiving $memberCount member${pluralEnding} each day."
        )
    }

    private fun getRandomAffirmativeExpression(): String {
        return affirmativeExpressions.random()
    }

    companion object {
        private val affirmativeExpressions =
            listOf("Awesome!", "Sounds great!", "Wonderful!", "Right on!", "Great choice!")
    }
}
