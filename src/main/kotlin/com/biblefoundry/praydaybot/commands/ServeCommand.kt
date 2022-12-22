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
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.kotlin.Logging

private const val MIN_MEMBER_COUNT = 1
private const val MAX_MEMBER_COUNT = 5
private const val DEFAULT_REMINDER_TIME = "8am"
private const val DEFAULT_MEMBER_COUNT = 3

class ServeCommand : CliktCommand(), Logging {
    private val config by requireObject<Map<String, Any>>()
    private lateinit var databaseService: DatabaseService

    override fun run() {
        logger.info("Running PrayDayBot...")

        val telegramToken = config["TELEGRAM_TOKEN"] as String
        databaseService = DatabaseService(
            config["DYNAMODB_REGION"] as String,
            config["MEMBERS_TABLE_NAME"] as String,
            config["FAMILIES_TABLE_NAME"] as String,
            config["TELEGRAM_CHAT_ID_INDEX_NAME"] as String,
            config["REMINDER_TIME_INDEX_NAME"] as String,
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

                command("subscribe") {
                    handleSubscribeCommand()
                }
                command("unsubscribe") {
                    handleUnsubscribeCommand()
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
        val phoneNumber = formatPhoneNumber(contact.phoneNumber)
        logger.info("Checking for user with phone number ${phoneNumber}...")

        val userStatus = databaseService.checkUserStatus(phoneNumber)
        val messageText = when (userStatus) {
            UserStatus.UNRECOGNIZED -> "Sorry, I don't recognize your phone number."
            UserStatus.SUBSCRIBED -> "Hi ${contact.firstName}! It looks like you've already subscribed, welcome back! To see your current settings, use the /settings command. To unsubscribe, use the /unsubscribe command."
            UserStatus.UNSUBSCRIBED -> "Hi ${contact.firstName}! It looks like you haven't subscribed yet. To subscribe, use the /subscribe command."
        }

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id), text = messageText, replyMarkup = ReplyKeyboardRemove()
        )

        // Save chat ID -> phone number mapping
        if (userStatus != UserStatus.UNRECOGNIZED) {
            logger.info("Saving chat ID ${message.chat.id} to phone number ${phoneNumber}...")
            databaseService.saveUserChatId(phoneNumber, message.chat.id)
        }
    }

    private fun CommandHandlerEnvironment.handleStartCommand() {
        val keyboardMarkup = KeyboardReplyMarkup(
            keyboard = listOf(listOf(KeyboardButton("Share my phone number", requestContact = true))),
            resizeKeyboard = true
        )
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "Hello from PrayDay! Please click the <b>Share my phone number</b> button below to register.",
            parseMode = ParseMode.HTML,
            replyMarkup = keyboardMarkup
        )
    }

    private fun handleHelpCommand() {
        TODO("Not yet implemented")
    }

    private fun CommandHandlerEnvironment.handleSettingsCommand() = runBlocking {
        logger.info("Retrieving settings for user with chat ID ${message.chat.id}...")

        val userMapping = databaseService.getUserByChatId(message.chat.id) ?: return@runBlocking
        val user = databaseService.getUser(userMapping.phoneNumber) ?: return@runBlocking

        // If not subscribed, send quick message
        if (!user.subscribed) {
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = "You're currently not subscribed to receive daily prayer reminders. To subscribe, use the /subscribe command."
            )
            return@runBlocking
        }

        // Set default values, if not set before
        val memberCount: Int
        if (user.memberCount == null) {
            memberCount = DEFAULT_MEMBER_COUNT
            databaseService.setUserMemberCount(userMapping.phoneNumber, memberCount)
        } else {
            memberCount = user.memberCount
        }
        val reminderTime: String
        if (user.reminderTime == null) {
            reminderTime = DEFAULT_REMINDER_TIME
            databaseService.setUserReminderTime(userMapping.phoneNumber, reminderTime)
        } else {
            reminderTime = user.reminderTime
        }

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "You're currently subscribed to receive daily prayer reminders for $memberCount members at $reminderTime each day. To change these settings, use the /setnumber or /settime commands."
        )
    }

    private fun CommandHandlerEnvironment.handleSubscribeCommand() = runBlocking {
        logger.info("Subscribing user with chat ID ${message.chat.id}...")

        val userMapping = databaseService.getUserByChatId(message.chat.id) ?: return@runBlocking
        databaseService.setUserSubscribed(userMapping.phoneNumber, true)

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "${getRandomAffirmativeExpression()} You'll now start receiving daily prayer reminders. To see your current settings, use the /settings command. To unsubscribe, use the /unsubscribe command."
        )
    }

    private fun CommandHandlerEnvironment.handleUnsubscribeCommand() = runBlocking {
        logger.info("Unsubscribing user with chat ID ${message.chat.id}...")

        val userMapping = databaseService.getUserByChatId(message.chat.id) ?: return@runBlocking
        databaseService.setUserSubscribed(userMapping.phoneNumber, false)

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "Sad to see you go! You'll no longer be receiving daily prayer reminders. To re-subscribe, use the /subscribe command."
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
        val buttons = (MIN_MEMBER_COUNT until MAX_MEMBER_COUNT + 1).map { number ->
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

    private fun formatPhoneNumber(phoneNumber: String): String {
        return "+" + phoneNumber.replace("\\D+".toRegex(), "")
    }

    companion object {
        private val affirmativeExpressions =
            listOf("Awesome!", "Sounds great!", "Wonderful!", "Right on!", "Great choice!")
    }
}
