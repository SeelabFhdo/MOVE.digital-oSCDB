package de.movedigital.oscdb.inquiry

import de.movedigital.oscdb.inquiry.internal.RetryContext
import de.movedigital.oscdb.inquiry.internal.SqlTranslator
import de.movedigital.oscdb.inquiry.internal.Translation
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Test double for the model: answers from a queue of scripted replies and records every call. */
class ScriptedTranslator : SqlTranslator {
    data class Call(val question: String, val format: OutputFormat, val retry: RetryContext?)

    override val model = "scripted"
    val calls = mutableListOf<Call>()
    private val replies = ArrayDeque<(Call) -> Translation>()

    fun reset() {
        calls.clear()
        replies.clear()
    }

    fun reply(reply: (Call) -> Translation) {
        replies.addLast(reply)
    }

    fun answer(sql: String, vararg feedIds: Int, assumptions: List<String> = emptyList()) =
        reply { Translation(true, null, sql, assumptions, feedIds.toList(), "{\"sql\":\"$sql\"}", 5) }

    override fun translate(question: String, format: OutputFormat, retry: RetryContext?): Translation {
        val call = Call(question, format, retry)
        calls += call
        val reply = replies.removeFirstOrNull() ?: error("ScriptedTranslator: no reply left for $call")
        return reply(call)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        @Primary
        fun scriptedTranslator() = ScriptedTranslator()
    }
}
