package dev.qixils.demowocwacy

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.emoji.Emoji

abstract class Decree(
    val name: String,
    val emoji: Emoji,
    val description: String,
    /**
     * Whether this decree is persistent, meaning its execute function should be called on every startup.
     */
    val persistent: Boolean,
) {
    constructor(name: String, emoji: String, description: String, persistent: Boolean) : this(name, Emoji.fromFormatted(emoji), description, persistent)

    val displayName: String
        get() = "${emoji.formatted} $name"

    open val priority: Int
        get() = 0

    /**
     * Executes this decree.
     *
     * @param init Whether this is the first execution of the decree.
     */
    abstract suspend fun execute(init: Boolean)

    open suspend fun cleanup() {
    }

    open suspend fun onStartTask(task: Task) {
    }

    companion object {
        fun isApplicableTo(channel: Channel): Boolean {
            if (channel !is GuildChannel) return false
            if (!Bot.isInGuild(channel)) return false
            if (channel.idLong in Bot.config.protectedChannels) return false
            if (channel is ICategorizableChannel && channel.parentCategory?.let { isApplicableTo(it) } == false) return false
            if (channel is ThreadChannel /*&& !isApplicableTo(channel.parentChannel)*/) return false
            return true
        }

        fun isApplicableTo(channel: Channel, author: User): Boolean {
            if (!isApplicableTo(channel)) return false
            if (author.idLong in Bot.config.protectedUsers) return false
            return true
        }

        fun isApplicableTo(message: Message): Boolean {
            if (message.isWebhookMessage) return false
            if (!isApplicableTo(message.channel, message.author)) return false
            return true
        }
    }
}