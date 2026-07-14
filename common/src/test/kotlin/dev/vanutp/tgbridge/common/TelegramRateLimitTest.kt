package dev.vanutp.tgbridge.common

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class NoopLogger : ILogger {
    override fun info(message: Any) {}
    override fun warn(message: Any) {}
    override fun error(message: Any) {}
    override fun error(message: Any, exc: Exception) {}
}

private fun rateLimitException(retryAfter: Int) = TelegramException(
    errorCode = 429,
    parameters = TgResponseParameters(retryAfter = retryAfter),
)

class TelegramRateLimitTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesRetryAfterFrom429Body() {
        // Exact payload returned by the Telegram Bot API on a rate limit.
        val body =
            """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 9","parameters":{"retry_after":9}}"""

        val response = json.decodeFromString<TgResponse<JsonElement>>(body)

        assertEquals(false, response.ok)
        assertEquals(429, response.errorCode)
        assertEquals(9, response.parameters?.retryAfter)
        assertNull(response.result)
    }

    @Test
    fun telegramExceptionExposesRetryAfter() {
        val body =
            """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 9","parameters":{"retry_after":9}}"""
        val response = json.decodeFromString<TgResponse<JsonElement>>(body)

        val exception = TelegramException(
            errorCode = response.errorCode,
            description = response.description,
            parameters = response.parameters,
            responseBody = body,
        )

        assertEquals(429, exception.errorCode)
        assertEquals(9, exception.retryAfter)
    }

    @Test
    fun singleBotTransient429IsRetriedThenSucceeds() = runBlocking {
        var calls = 0
        val result = withBotFailover(listOf("main"), NoopLogger()) { _ ->
            calls++
            // retry_after = 0 keeps the test fast (only the ~1s margin is waited)
            if (calls < 3) throw rateLimitException(retryAfter = 0)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun singleBotPersistent429IsCappedThenGivesUp() = runBlocking {
        var calls = 0
        val thrown = assertFailsWith<TelegramException> {
            withBotFailover(listOf("main"), NoopLogger()) { _ ->
                calls++
                throw rateLimitException(retryAfter = 0)
            }
        }
        assertEquals(429, thrown.errorCode)
        // 1 initial round + MAX_RATE_LIMIT_RETRIES retry rounds, then it gives up
        assertEquals(1 + MAX_RATE_LIMIT_RETRIES, calls)
    }

    @Test
    fun failsOverToReserveBotWithoutWaiting() = runBlocking {
        val used = mutableListOf<String>()
        val result = withBotFailover(listOf("main", "reserve"), NoopLogger()) { bot ->
            used += bot
            // A big retry_after would make the test hang for 30s if failover wrongly
            // waited on the main bot instead of trying the reserve immediately.
            if (bot == "main") throw rateLimitException(retryAfter = 30)
            "sent via $bot"
        }
        assertEquals("sent via reserve", result)
        assertEquals(listOf("main", "reserve"), used)
    }

    @Test
    fun allBotsRateLimitedGivesUp() = runBlocking {
        var calls = 0
        val thrown = assertFailsWith<TelegramException> {
            withBotFailover(listOf("main", "reserve"), NoopLogger()) { _ ->
                calls++
                throw rateLimitException(retryAfter = 0)
            }
        }
        assertEquals(429, thrown.errorCode)
        // every round tries both bots before waiting; (1 + cap) rounds
        assertEquals((1 + MAX_RATE_LIMIT_RETRIES) * 2, calls)
    }

    @Test
    fun nonRateLimitErrorPropagatesImmediately() = runBlocking {
        var calls = 0
        val thrown = assertFailsWith<TelegramException> {
            withBotFailover(listOf("main", "reserve"), NoopLogger()) { _ ->
                calls++
                throw TelegramException(errorCode = 400, description = "Bad Request")
            }
        }
        assertEquals(400, thrown.errorCode)
        // not a rate limit: no failover, no retry
        assertEquals(1, calls)
    }

    @Test
    fun successResponseHasNoError() {
        val body = """{"ok":true,"result":{"id":1,"first_name":"bot"}}"""

        val response = json.decodeFromString<TgResponse<TgUser>>(body)

        assertEquals(true, response.ok)
        assertNull(response.errorCode)
        assertNull(response.parameters)
        assertEquals(1, response.result?.id)
    }
}
