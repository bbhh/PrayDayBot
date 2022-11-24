package com.biblefoundry.praydaybot.commands

import com.biblefoundry.praydaybot.services.DatabaseService
import com.biblefoundry.praydaybot.services.UserRegistration
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.logging.log4j.kotlin.Logging
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class MessageCommand : CliktCommand(), Logging {
    private val config by requireObject<Map<String, Any>>()

    private val httpClient = OkHttpClient()
    private lateinit var databaseService: DatabaseService

    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE M/d")
    private val hourDateFormatter = DateTimeFormatter.ofPattern("ha")

    override fun run() {
        val telegramToken = config["TELEGRAM_TOKEN"] as String
        val timeZone = config["TIME_ZONE"] as String
        databaseService = DatabaseService(
            config["DYNAMODB_REGION"] as String,
            config["MEMBERS_TABLE_NAME"] as String,
            config["FAMILIES_TABLE_NAME"] as String,
            config["TELEGRAM_CHAT_ID_INDEX_NAME"] as String,
            config["REMINDER_TIME_INDEX_NAME"] as String
        )

        val users = listRegisteredUsers(timeZone)
        if (users.isEmpty()) {
            logger.info("-> None found, skipping")
            return
        }
        sendMessages(telegramToken, users)
    }

    private fun listRegisteredUsers(timeZone: String): List<UserRegistration> = runBlocking {
        val zonedNow = ZonedDateTime.now(ZoneId.of(timeZone))
        val currentHour = zonedNow.format(hourDateFormatter).lowercase(Locale.getDefault())

        logger.info("Looking up users registered for time of $currentHour (time zone of $timeZone)...")
        return@runBlocking databaseService.listUsersRegisteredAtTime(currentHour)
    }

    private fun sendMessages(telegramToken: String, users: List<UserRegistration>) = runBlocking {
        logger.info("Sending prayer reminders to ${users.size} user(s)...")

        val families = databaseService.listFamilies()
        if (families == null) {
            logger.error("-> No families found")
            return@runBlocking
        }

        val today = LocalDate.now()
        val todayFormatted = today.format(dateFormatter)
        users.forEach { user ->
            // Generate batch of families
            val batch = families.shuffled().take(user.memberCount).sorted()
                .mapIndexed { index, family -> "${index + 1}. $family" }.joinToString("\n")

            // Construct message and send to user
            logger.info("-> ${user.firstName} ${user.lastName} (chat ID: ${user.telegramChatId})")
            val message = """Hi ${user.firstName}, today is $todayFormatted. Please pray for:
                |$batch""".trimMargin()
            logger.info(message)
            sendMessage(telegramToken, user.telegramChatId, message)
        }
    }

    private fun sendMessage(telegramToken: String, chatId: Long, message: String) {
        // Construct Telegram sendMessage URL
        val encodedMessage = URLEncoder.encode(message, "utf-8")
        val url = "https://api.telegram.org/bot$telegramToken/sendMessage?chat_id=$chatId&text=$encodedMessage"
        val request = Request.Builder().url(url).build()

        // Send message
        httpClient.newCall(request).execute()
    }
}