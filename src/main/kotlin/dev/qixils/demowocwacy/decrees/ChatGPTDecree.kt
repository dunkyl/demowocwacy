package dev.qixils.demowocwacy.decrees

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.messages.reply_
import dev.qixils.demowocwacy.Bot
import dev.qixils.demowocwacy.Decree
import kotlinx.serialization.Serializable
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.util.*
import kotlin.time.Duration.Companion.seconds

class ChatGPTDecree : Decree(
    "Web 4.0",
    "\uD83C\uDF10",
    "Sustain conversation with advanced A.I. technologies",
    true
) {
    private val random = Random()
    private val context = 25
    private val odds = 20 // as in, 1 in X
    private val model = ModelId("gpt-4-0125-preview")
    private val prompt = ChatMessage(
        role = ChatRole.System,
        content = "You are a user chatting in a science, technology, engineering, mathematics, and programming Discord server. " +
                "You are silly, casual, liberal, queer, furry, and about 21 years old. " +
                "You primarily crytype and use lowercase letters."
    )
    private val promptList = listOf(prompt)

    private val openai = OpenAI(
        token = Bot.config.decrees.openai.token,
        timeout = Timeout(socket = 60.seconds),
    )

    private val messages = mutableMapOf<Long, MutableList<ChatMessage>>()

    override suspend fun execute(init: Boolean) {
        Bot.jda.listener<MessageReceivedEvent> { event ->
            if (!isApplicableTo(event.message)) return@listener
            if (event.message.type.isSystem) return@listener
            if (event.author.isBot) return@listener
            if (event.author.idLong == Bot.jda.selfUser.idLong) return@listener
            if (event.message.contentRaw.isEmpty()) return@listener

            val msgList = messages.computeIfAbsent(event.channel.idLong) { mutableListOf() }
            msgList.add(ChatMessage(
                role = ChatRole.User,
                content = event.message.contentRaw,
                name = event.author.effectiveName,
            ))
            while (msgList.size > context)
                msgList.removeAt(0)

            if (random.nextInt(odds) != 0) return@listener

            event.channel.sendTyping().queue()
            val completion = try {
                openai.chatCompletion(ChatCompletionRequest(
                    model = model,
                    messages = promptList + msgList,
                ))
            } catch (e: Exception) {
                Bot.logger.error("Failed to fetch chat completion", e)
                return@listener
            }

            val message = completion.choices.firstOrNull()?.message ?: run {
                Bot.logger.warn("No message from OpenAI")
                return@listener
            }
            val content = message.content
            if (content.isNullOrEmpty()) {
                Bot.logger.warn("Empty message from OpenAI")
                return@listener
            }

            msgList.add(message)
            event.message.reply_(content).await()
        }
    }
}

@Serializable
data class OpenAIConfig (
    val token: String = "INSERT_TOKEN",
)