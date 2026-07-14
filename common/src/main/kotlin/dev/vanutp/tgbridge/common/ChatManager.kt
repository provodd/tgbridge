package dev.vanutp.tgbridge.common

import dev.vanutp.tgbridge.common.ConfigManager.config
import dev.vanutp.tgbridge.common.converters.MinecraftToTelegramConverter
import dev.vanutp.tgbridge.common.converters.TelegramFormattedText
import dev.vanutp.tgbridge.common.models.ChatConfig
import dev.vanutp.tgbridge.common.models.ITgbridgePlayer
import dev.vanutp.tgbridge.common.models.TgMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kyori.adventure.text.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

abstract class MessageContent {
    /**
     * Performs the necessary actions to send the message to Telegram.
     * Should not be called manually.
     * @param lastMessage The return value of the last [send] call for the same chat.
     * This value is reset when any Telegram message for the respective chat is received.
     * It can also be reset by calling [ChatManager.clearLastMessage].
     * @param bot The bot to send new messages with (may be a reserve bot during rate-limit
     * failover). Edits/deletes must target [TgbridgeTgMessage.bot] of [lastMessage]; when [bot]
     * differs from it (i.e. a reserve took over), a new message must be sent instead of merging.
     * @return The [TgbridgeTgMessage] object representing the sent message, or null. This value will be used in subsequent [send] calls
     */
    abstract suspend fun send(chat: ChatConfig, lastMessage: TgbridgeTgMessage?, bot: TelegramBot): TgbridgeTgMessage?
}

class MessageContentText(
    val text: TelegramFormattedText,
    val replyToMessageId: Int? = null,
    val disableNotification: Boolean = false,
) : MessageContent() {
    constructor(
        component: Component,
        replyToMessageId: Int? = null,
        disableNotification: Boolean = false,
    ) : this(
        MinecraftToTelegramConverter.convert(component),
        replyToMessageId,
        disableNotification,
    )

    // backwards compatibility on java
    @Deprecated("This constructor is deprecated", level = DeprecationLevel.HIDDEN)
    constructor(text: TelegramFormattedText, replyToMessageId: Int?) : this(
        text,
        replyToMessageId = replyToMessageId,
        disableNotification = false
    )
    @Deprecated("This constructor is deprecated", level = DeprecationLevel.HIDDEN)
    constructor(text: Component, replyToMessageId: Int?) : this(
        text,
        replyToMessageId = replyToMessageId,
        disableNotification = false
    )

    override suspend fun send(chat: ChatConfig, lastMessage: TgbridgeTgMessage?, bot: TelegramBot): TgbridgeTgMessage {
        val tgMessage = bot.sendMessage(
            chat.chatId,
            text.text,
            text.entities,
            replyToMessageId = replyToMessageId ?: chat.topicId,
            disableNotification = disableNotification,
        )
        return TgbridgeTgMessage(
            chat, tgMessage.messageId, Clock.systemUTC().instant(), this, bot
        )
    }
}

class MessageContentHTMLText(
    val text: String,
    val replyToMessageId: Int? = null,
    val disableNotification: Boolean = false,
) : MessageContent() {
    // backwards compatibility on java
    @Deprecated("This constructor is deprecated", level = DeprecationLevel.HIDDEN)
    constructor(text: String, replyToMessageId: Int?) : this(
        text,
        replyToMessageId = replyToMessageId,
        disableNotification = false
    )

    override suspend fun send(chat: ChatConfig, lastMessage: TgbridgeTgMessage?, bot: TelegramBot): TgbridgeTgMessage {
        val tgMessage = bot.sendMessage(
            chat.chatId,
            text,
            parseMode = "HTML",
            replyToMessageId = replyToMessageId ?: chat.topicId,
            disableNotification = disableNotification,
        )
        return TgbridgeTgMessage(
            chat, tgMessage.messageId, Clock.systemUTC().instant(), this, bot
        )
    }
}

class MessageContentMergeableText(
    val text: TelegramFormattedText,
    val disableNotification: Boolean = false,
) : MessageContent() {
    constructor(component: Component, disableNotification: Boolean = false) : this(
        MinecraftToTelegramConverter.convert(component),
        disableNotification,
    )

    // backwards compatibility on java
    @Deprecated("This constructor is deprecated", level = DeprecationLevel.HIDDEN)
    constructor(text: TelegramFormattedText) : this(text, disableNotification = false)
    @Deprecated("This constructor is deprecated", level = DeprecationLevel.HIDDEN)
    constructor(text: Component) : this(text, disableNotification = false)

    private suspend fun sendNewMessage(chat: ChatConfig, bot: TelegramBot): TgbridgeTgMessage {
        val tgMessage = bot.sendMessage(
            chat.chatId,
            text.text,
            text.entities,
            replyToMessageId = chat.topicId,
            disableNotification = disableNotification,
        )
        return TgbridgeTgMessage(
            chat, tgMessage.messageId, Clock.systemUTC().instant(), this, bot
        )
    }

    override suspend fun send(chat: ChatConfig, lastMessage: TgbridgeTgMessage?, bot: TelegramBot): TgbridgeTgMessage {
        val prevContent = (lastMessage?.content as? MessageContentMergeableText) ?: return sendNewMessage(chat, bot)
        val currDate = Clock.systemUTC().instant()
        if (
            // can only edit a message the current bot sent -- on failover to a reserve bot, start fresh
            lastMessage.bot !== bot
            || (prevContent.text + "\n" + text).text.length > 4000
            || currDate.minus((config.messages.mergeWindow).toLong(), ChronoUnit.SECONDS) > lastMessage.date
        ) {
            return sendNewMessage(chat, bot)
        }

        val newText = prevContent.text + "\n" + text
        bot.editMessageText(
            chat.chatId,
            lastMessage.id,
            newText.text,
            newText.entities,
        )
        return TgbridgeTgMessage(
            chat,
            lastMessage.id,
            currDate,
            MessageContentMergeableText(newText),
            bot,
        )
    }
}

class MessageContentLeave(
    val player: ITgbridgePlayer,
    val text: String,
) : MessageContent() {
    override suspend fun send(chat: ChatConfig, lastMessage: TgbridgeTgMessage?, bot: TelegramBot): TgbridgeTgMessage {
        val tgMessage = bot.sendMessage(
            chat.chatId,
            text,
            parseMode = "HTML",
            replyToMessageId = chat.topicId,
            disableNotification = config.messages.silentEvents.contains(TgMessageType.LEAVE),
        )
        return TgbridgeTgMessage(
            chat, tgMessage.messageId, Clock.systemUTC().instant(), this, bot
        )
    }
}

class MessageContentJoin(
    val player: ITgbridgePlayer,
    val text: String,
) : MessageContent() {
    private suspend fun sendNewMessage(chat: ChatConfig, bot: TelegramBot): TgbridgeTgMessage {
        val tgMessage = bot.sendMessage(
            chat.chatId,
            text,
            parseMode = "HTML",
            replyToMessageId = chat.topicId,
            disableNotification = config.messages.silentEvents.contains(TgMessageType.JOIN),
        )
        return TgbridgeTgMessage(
            chat, tgMessage.messageId, Clock.systemUTC().instant(), this, bot
        )
    }

    override suspend fun send(chat: ChatConfig, lastMessage: TgbridgeTgMessage?, bot: TelegramBot): TgbridgeTgMessage? {
        val prevContent = (lastMessage?.content as? MessageContentLeave) ?: return sendNewMessage(chat, bot)
        val currDate = Clock.systemUTC().instant()
        if (
            prevContent.player.uuid != player.uuid
            // the leave message can only be deleted by the bot that sent it
            || lastMessage.bot !== bot
            || currDate.minus((config.events.leaveJoinMergeWindow).toLong(), ChronoUnit.SECONDS) >= lastMessage.date
        ) {
            return sendNewMessage(chat, bot)
        }
        bot.deleteMessage(chat.chatId, lastMessage.id)
        return null
    }
}

class TgbridgeTgMessage(
    val chat: ChatConfig,
    val id: Int,
    val date: Instant,
    val content: MessageContent,
    /** The bot that sent this message; edits/deletes of it must use the same bot. */
    val bot: TelegramBot,
)

class ChatManager(private val scope: CoroutineScope) {
    private val lastMessages = mutableMapOf<String, TgbridgeTgMessage?>()

    // TODO: do we even need this? maybe a queue would be better?
    private val lock = Mutex()

    internal fun unlock() {
        // TODO: this doesn't seem safe
        if (lock.isLocked) {
            lock.unlock()
        }
    }

    fun clearLastMessage(chat: ChatConfig) {
        lastMessages.remove(chat.name)
    }

    suspend fun sendMessage(chat: ChatConfig, content: MessageContent) {
        lock.withLock {
            val lastMessage = lastMessages[chat.name]
            // Try the main bot, then any reserve bots, honoring Telegram's retry_after only
            // once every bot is rate limited. Throws (dropping the message) if all give up.
            lastMessages[chat.name] = withBotFailover(TelegramBridge.INSTANCE.sendBots, TelegramBridge.INSTANCE.logger) { bot ->
                content.send(chat, lastMessage, bot)
            }
        }
    }

    fun sendMessageAsync(chat: ChatConfig, content: MessageContent) = scope.future {
        sendMessage(chat, content)
    }
}
