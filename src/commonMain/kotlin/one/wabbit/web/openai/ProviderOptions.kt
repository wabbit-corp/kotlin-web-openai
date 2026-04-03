package one.wabbit.web.openai

import kotlinx.serialization.json.JsonObjectBuilder

sealed interface RequestProviderOptions {
    fun applyTo(builder: JsonObjectBuilder)

    fun requireCompatibleWith(provider: OpenAIProvider)
}

sealed interface ResponseProviderOptions : RequestProviderOptions

sealed interface ChatProviderOptions : RequestProviderOptions

sealed interface EmbeddingProviderOptions : RequestProviderOptions
