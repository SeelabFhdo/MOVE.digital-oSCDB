package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.OutputFormat

/** Turns a natural-language question into SQL over the `inquiry_api` views. */
interface SqlTranslator {
    val model: String

    /**
     * [retry] carries the previous answer and why it failed, for one repair attempt.
     * @throws ModelUnavailableException if the model cannot be reached or answers with an HTTP error
     * @throws InvalidModelOutputException if the answer is not the expected JSON object
     */
    fun translate(question: String, format: OutputFormat, retry: RetryContext?): Translation
}

data class RetryContext(val previousAnswer: String, val error: String)

data class Translation(
    val answerable: Boolean,
    val reason: String?,
    val sql: String?,
    val assumptions: List<String>,
    val feedIds: List<Int>,
    /** The model's raw answer, sent back on a repair attempt. */
    val raw: String,
    val millis: Long,
)

class ModelUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InvalidModelOutputException(val raw: String, message: String) : RuntimeException(message)

/** The system prompt for a requested format. */
fun interface SystemPromptSource {
    fun systemPrompt(format: OutputFormat): String
}
