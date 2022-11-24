package com.biblefoundry.praydaybot.services

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import org.apache.logging.log4j.kotlin.Logging

class DatabaseService(
    private val dynamoDbRegion: String,
    private val membersTableName: String,
    private val telegramChatIdIndexName: String
) : Logging {
    suspend fun checkUserStatus(phoneNumber: String): UserStatus {
        val keyToGet = mapOf<String, AttributeValue>(
            "id" to AttributeValue.S(phoneNumber)
        )
        val request = GetItemRequest {
            tableName = membersTableName
            key = keyToGet
        }

        DynamoDbClient { region = dynamoDbRegion }.use { ddb ->
            val user = ddb.getItem(request).item ?: return UserStatus.UNRECOGNIZED
            val subscribedValue = user["subscribed"]
            val subscribed = (subscribedValue != null) && subscribedValue.asBool()
            return if (subscribed) UserStatus.SUBSCRIBED else UserStatus.UNSUBSCRIBED
        }
    }

    private suspend fun updateUserValue(phoneNumber: String, keyToUpdate: String, valueToUpdate: AttributeValue) {
        val itemKey = mapOf<String, AttributeValue>(
            "id" to AttributeValue.S(phoneNumber)
        )
        val valuesToUpdate = mapOf(keyToUpdate to AttributeValueUpdate {
            value = valueToUpdate
            action = AttributeAction.Put
        })
        val request = UpdateItemRequest {
            tableName = membersTableName
            key = itemKey
            attributeUpdates = valuesToUpdate
        }

        DynamoDbClient { region = dynamoDbRegion }.use { ddb ->
            ddb.updateItem(request)
        }
    }

    suspend fun saveUserChatId(phoneNumber: String, chatId: Long) {
        updateUserValue(phoneNumber, "telegramChatId", AttributeValue.N(chatId.toString()))
    }

    suspend fun getUserByChatId(chatId: Long): UserMapping? {
        val request = QueryRequest {
            tableName = membersTableName
            indexName = telegramChatIdIndexName
            keyConditionExpression = "telegramChatId = :id"
            expressionAttributeValues = mapOf(":id" to AttributeValue.N(chatId.toString()))
        }

        DynamoDbClient { region = dynamoDbRegion }.use { ddb ->
            val response = ddb.query(request)
            if (response.count < 1) return null
            val user = response.items!![0]
            return UserMapping(
                phoneNumber = user["id"]!!.asS(),
                telegramChatId = user["telegramChatId"]!!.asN().toLong(),
            )
        }
    }

    suspend fun setUserSubscribed(phoneNumber: String, subscribed: Boolean) {
        updateUserValue(phoneNumber, "subscribed", AttributeValue.Bool(subscribed))
    }

    suspend fun setUserReminderTime(phoneNumber: String, reminderTime: String) {
        updateUserValue(phoneNumber, "reminderTime", AttributeValue.S(reminderTime))
    }

    suspend fun setUserMemberCount(phoneNumber: String, memberCount: Int) {
        updateUserValue(phoneNumber, "memberCount", AttributeValue.N(memberCount.toString()))
    }
}

data class UserMapping(
    val phoneNumber: String, val telegramChatId: Long
)

enum class UserStatus {
    UNRECOGNIZED, SUBSCRIBED, UNSUBSCRIBED
}