package dev.qixils.demowocwacy

import com.typesafe.config.ConfigFactory
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onButton
import dev.minn.jda.ktx.events.onStringSelect
import dev.minn.jda.ktx.interactions.components.*
import dev.minn.jda.ktx.jdabuilder.intents
import dev.minn.jda.ktx.jdabuilder.light
import dev.minn.jda.ktx.messages.*
import dev.minn.jda.ktx.util.SLF4J
import dev.qixils.demowocwacy.decrees.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import kotlinx.serialization.hocon.encodeToConfig
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.dv8tion.jda.internal.utils.PermissionUtil
import org.slf4j.Logger
import java.io.File
import java.time.Month
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalSerializationApi::class)
object Bot {

    val hocon = Hocon {
        useConfigNamingConvention = true
    }
    val cbor = Cbor {  }

    private val configFile = File("bot.conf")
    private val stateFile = File("state.cbor")
    private val signupButton = primary("signup", "Announce Candidacy")
    private val voteSorter = compareByDescending<Pair<Long, Int>> { it.second }.then(compareBy { it.first })

    val jda: JDA
    val logger: Logger by SLF4J

    /**
     * The user-defined configuration.
     */
    val config: BotConfig

    /**
     * All decrees that can be selected.
     */
    val allDecrees: List<Decree>

    /**
     * Gets decrees that have been selected by an elected leader.
     */
    val selectedDecrees: List<Decree>
        get() = allDecrees.filter { it.name in state.selectedDecrees }

    /**
     * Gets decrees that have been ignored by an elected leader.
     */
    val ignoredDecrees: List<Decree>
        get() = allDecrees.filter { it.name in state.ignoredDecrees }

    /**
     * Gets pending decrees that are waiting to be selected or ignored.
     */
    val pendingDecrees: List<Decree>
        get() = allDecrees.filter { it.name in state.election.decrees }

    /**
     * All decrees that have not yet been drawn from the random hat.
     */
    val remainingDecrees: List<Decree>
        get() = allDecrees.filter { it.name !in state.selectedDecrees && it.name !in state.ignoredDecrees && it.name !in state.election.decrees }

    /**
     * Channel in which elections are held.
     */
    val channel: TextChannel
        get() = jda.getTextChannelById(config.channel)!!

    /**
     * Guild in which the bot is running.
     */
    val guild: Guild
        get() = jda.getGuildById(config.guild)!!

    /**
     * Role for Familiar users.
     */
    val familiar: Role
        get() = jda.getRoleById(config.roles.familiar)!!

    /**
     * Unserious channel.
     */
    val unserious: TextChannel
        get() = jda.getTextChannelById(config.decrees.unserious.channel)!!

    /**
     * The state of the bot.
     * This is loaded from [stateFile] and saved to it when changed.
     */
    var state: BotState = if (stateFile.exists()) cbor.decodeFromByteArray(stateFile.readBytes()) else BotState()
        set(value) {
            field = value
            saveState(field)
        }

    init {
        // create default config if it doesn't exist
        if (!configFile.exists()) {
            val configNode = hocon.encodeToConfig(BotConfig())
            configFile.writeText(configNode.resolve().root().render())
        }
        // load config
        val configNode = ConfigFactory.parseFileAnySyntax(configFile)
        config = hocon.decodeFromConfig(configNode)
        // build JDA
        jda = light(config.token, enableCoroutines=true) {
            // I don't think I need member updates/joins/leaves but if I do the intent is GUILD_MEMBERS
            intents += GatewayIntent.MESSAGE_CONTENT
        }
        // disable @everyone pings
        MessageRequest.setDefaultMentions(emptySet())
        // init decrees
        allDecrees = listOf(
            TWOWDecree(),
            UnseriousDecree(),
            DemoteLexiDecree(),
            UndeleteDecree(),
            HTCDecree(),
            PeanutDecree(),
            Literally1984Decree(),
            BlindnessEpidemic(),
            EmbraceChristianityDecree(),
            ReverseDecree(),
            InvertDecree(),
            TuringTestDecree(),
            SpeechlessDecree(),
            ChatGPTDecree(),
            NoMathDecree(),
            DeSTEMification(),
            DyslexiaDecree(),
            JanitorDecree(),
            DadDecree(),
        )
        // init signup form
        jda.onButton(signupButton.id!!) { event ->
           // signing up for office
            if (!isRegisteredVoter(event.user)) {
                event.reply_("You must be a registered voter to run for office!", ephemeral = true).queue()
                return@onButton
            }
            if (event.user.idLong in state.election.candidates) {
                event.reply_("You are already a candidate!", ephemeral = true).queue()
                return@onButton
            }
            // open form
            event.replyModal(id = "form:signup", title = "Announce Candidacy") {
                paragraph(
                    id = "form:signup:platform",
                    label = "Describe what you would do as prime minister",
                    required = true,
                    placeholder = "As prime minister of ${event.guild!!.name}, I would enact the decree ___. I would also..."
                )
            }
        }
        jda.listener<ModalInteractionEvent> { event ->
            if (event.modalId == "form:signup") {
                // double check that the user still isn't a candidate (and is a registered voter)
                if (!isRegisteredVoter(event.user)) {
                    event.reply_("You must be a registered voter to run for office!", ephemeral = true).queue()
                    return@listener
                }
                if (event.user.idLong in state.election.candidates) {
                    event.reply_("You are already a candidate!", ephemeral = true).queue()
                    return@listener
                }
                // save candidate to state
                state.election.candidates.add(event.user.idLong)
                saveState()
                // create thread for candidate/platform
                val platform = event.getValue("form:signup:platform")?.asString ?: return@listener
                val message = channel.send(
                    """
                        ${event.member!!.asMention} has announced their candidacy!
                        >>> $platform
                    """.trimIndent(),
                    mentions = Mentions.of(MentionConfig.users(listOf(event.member!!.idLong)))
                ).await()
                message.createThreadChannel("Candidate: ${event.member!!.effectiveName}").queue()
            }
        }
        // init ballots
        jda.onStringSelect("vote:candidate") {event ->
            state.election.candidateVotes[event.user.idLong] = event.selectedOptions.map{ it.value.toLong() }.filter { state.election.candidates.contains(it) }
            saveState()
            event.reply_(content = "Your approved candidates have been recorded.", ephemeral = true).queue()
        }
        jda.onStringSelect("vote:decree") { event ->
            val decree = event.selectedOptions.first().value
            if (decree !in state.election.decrees) {
                event.reply_(content = "That decree is not currently available to vote on.", ephemeral = true).queue()
                return@onStringSelect
            }
            state.election.decreeVotes[event.user.idLong] = decree
            saveState()
            event.reply_(content = "Your desired decree has been recorded.", ephemeral = true).queue()
        }
        jda.onStringSelect("vote:tiebreak") { event ->
            // candidate tie break
            val candidate = event.selectedOptions.first().value.toLong()
            if (candidate !in state.election.tieBreakCandidates) {
                event.reply_(content = "That candidate is not currently available to vote on.", ephemeral = true).queue()
                return@onStringSelect
            }
            state.election.tieBreakVotes[event.user.idLong] = candidate
            saveState()
            event.reply_(content = "Your desired candidate has been recorded.", ephemeral = true).queue()
        }
    }

    private fun saveState(state: BotState) {
        stateFile.writeBytes(cbor.encodeToByteArray(state))
    }

    fun saveState() {
        saveState(state)
    }

    fun isGuild(guild: Long): Boolean {
        return guild == config.guild
    }

    fun isGuild(guild: Guild): Boolean {
        return isGuild(guild.idLong)
    }

    fun isInGuild(channel: GuildChannel): Boolean {
        return isGuild(channel.guild)
    }

    fun isRegisteredVoter(user: User): Boolean {
        // check for MESSAGE_SEND_IN_THREADS permission in the elections channel
        // this allows normal members to spectate while only registered voters can participate
        val channel = this.channel
        val member = channel.guild.getMember(user) ?: return false
        return PermissionUtil.checkPermission(channel, member, Permission.MESSAGE_SEND_IN_THREADS)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            jda.awaitReady()
            // init active decrees
            selectedDecrees.filter(Decree::persistent).forEach { it.execute() }
            // loop
            while (true) {
                // abort after April 1st
                val zdt = ZonedDateTime.now(ZoneOffset.UTC)
                if (zdt.month == Month.APRIL && zdt.dayOfMonth > 1)
                    break
                // begin election handling
                handleElection()
            }
        }
    }

    private suspend fun handleElection() {
        // TODO: state management (resume from prior session)
        handleElectionRegistrationPhase()
        handleElectionVotingPhase()
    }

    private suspend fun handleElectionRegistrationPhase() {
        // wait for start of election cycle (top of the hour)
        var now = System.currentTimeMillis()
        val nextHour = now + 3600000 - now % 3600000
        delay(nextHour - now)
        // put signup form in elections channel
        val messageData = MessageCreate {
            content = "The time has come to elect a new leader to bring our nation to glorious greatness! " +
                    "Over the next half hour, the fearless and noble of you citizens will have the opportunity to run for office. " +
                    "Attached to this message is a button which will open the form to announce your candidacy.\n\n" +
                    "For those of you not yet ready to take the reins of power, we still want to hear your voice. " +
                    "A thread will be created for each candidate to discuss their platform and answer questions from you, the people.\n\n" +
                    "Vox populi, vox dei."
            components += row(signupButton)
        }
        val message = channel.sendMessage(messageData).await()
        // sleep until XX:30
        now = System.currentTimeMillis()
        val nextHalfHour = now + 1800000 - now % 1800000
        delay(nextHalfHour - now)
        // close signup form
        message.editMessageComponents(message.components.map { it.asDisabled() }).queue()
    }

    private suspend fun handleElectionVotingPhase() {
        val electionOptions = state.election.candidates.map { cand: Long -> SelectOption.of(guild.retrieveMemberById(cand).await().effectiveName, cand.toString()) }
        // announce ballot
        val messageData = MessageCreate {
            content = buildString {
                append("The election has begun! Attached to this message, you will find the two sections of the ballot.\n")
                append("The first section is for the election of the next leader of our nation. ")
                append("As this nation follows the principles of approval voting, you may select all candidates whose platform you")
                if (electionOptions.isNotEmpty()) {
                    append(" support.\n")
                } else {
                    append("-- _wait, what's that? uhuh. yep. ok got it, i'll let them know. ok, bye._\n")
                    append("It seems that none of you were brave enough to run for office. What a shame. ")
                    append("Not to fear, our elections are protected by contingencies upon contingencies. ")
                    append("The fearless PRIME_MINISTER_9000 will be stepping in to fulfill the duties of the office until the next election cycle.\n")
                }
                append("The second section is for choosing the decree you wish to see enacted by the new leader. ")
                append("Only the top three most popular decrees will be considered for enactment, so choose wisely.")
            }
            components += listOf(
                row(StringSelectMenu("vote:candidate") {
                    if (electionOptions.isNotEmpty()) {
                        addOptions(electionOptions)
                    } else {
                        option("PRIME_MINISTER_9000", "PRIME_MINISTER_9000", "I WILL MAKE HTSTEM GREAT AGAIN", Emoji.fromUnicode("\uD83E\uDD16"), true)
                        isDisabled = true
                    }
                    setRequiredRange(1, SelectMenu.OPTIONS_MAX_AMOUNT)
                }),
                row(StringSelectMenu("vote:decree") {
                    for (decree in pendingDecrees) {
                        option(decree.name, decree.name, emoji = decree.emoji)
                    }
                })
            )
        }
        val message = channel.sendMessage(messageData).await()
        // sleep until XX:40
        val now = System.currentTimeMillis()
        val nextTenMins = now + 600000 - now % 600000
        delay(nextTenMins - now)
        // close ballot and threads
        message.editMessageComponents(message.components.map { it.asDisabled() }).queue()
        channel.threadChannels.forEach { it.manager.setLocked(true).queue() }
        // tally votes
        val votes = mutableMapOf<Long, Int>()
        for (candidate in state.election.candidates)
            votes[candidate] = 0
        if (votes.isEmpty()) {
            // TODO: PRIME_MINISTER_9000
        } else {
            for ((voter, vote) in state.election.candidateVotes.entries) {
                for (candidate in vote) {
                    if (candidate !in votes) {
                        logger.warn("User $voter voted for unknown candidate $candidate")
                        continue
                    }
                    votes[candidate] = votes[candidate]!! + 1
                }
            }
            val sortedVotes = votes.toList().sortedWith(voteSorter)
            val winners = sortedVotes.takeWhile { it.second == sortedVotes.first().second }.map { it.first }
            val winner: Long
            if (winners.size > 1) {
                // resort to 5min FPTP tie breaker
                state.election.tieBreakCandidates.addAll(winners)
                channel.sendMessage(MessageCreate {
                    content = buildString {
                        append("Ah, an indecisive bunch, are we? ")
                        append("Alright, I'll give you all five minutes to try to sort this tie before I step in and pick randomly. ")
                        append("Please select your favorite of the candidates below.")
                    }
                    components += row(StringSelectMenu("vote:tiebreak") {
                        for (candidate in winners) {
                            option(guild.retrieveMemberById(candidate).await().effectiveName, candidate.toString())
                        }
                    })
                }).await()
                delay(300000)
                votes.clear()
                for ((voter, candidate) in state.election.tieBreakVotes) {
                    if (candidate !in winners) {
                        logger.warn("User $voter voted for unknown candidate $candidate")
                        continue
                    }
                    votes[candidate] = votes[candidate]!! + 1
                }
                val newSortedVotes = votes.toList().sortedWith(voteSorter)
                winner = newSortedVotes.takeWhile { it.second == newSortedVotes.first().second }.map { it.first }.random()
            } else {
                winner = winners.first()
            }
            // announce winner
            // TODO
            // DM decree form to winner (actually maybe don't DM because users can disable them; use a private channel instead?)
            // TODO
        }
    }
}
