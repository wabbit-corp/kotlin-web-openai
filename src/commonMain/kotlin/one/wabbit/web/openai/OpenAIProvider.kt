package one.wabbit.web.openai

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders

data class ProviderCapabilities(
    val responsesApi: Boolean,
    val responseStreaming: Boolean,
    val statefulResponses: Boolean,
    val chatCompletions: Boolean,
    val embeddingsApi: Boolean,
    val modelsApi: Boolean,
    val imagesApi: Boolean,
    val moderationsApi: Boolean,
    val batchesApi: Boolean,
    val structuredOutputs: Boolean,
    val builtinTools: Boolean,
    val providerRouting: Boolean,
    val audioApi: Boolean,
    val filesApi: Boolean,
    val uploadsApi: Boolean,
    val vectorStoresApi: Boolean,
    val conversationsApi: Boolean,
    val fineTuningApi: Boolean,
    val realtimeBootstrapApi: Boolean,
    val evalsApi: Boolean,
    val videosApi: Boolean,
    val xaiBatchApi: Boolean,
    val xaiTtsApi: Boolean,
)

sealed interface OpenAIProvider {
    val id: String
    val defaultBaseUrl: String
    val capabilities: ProviderCapabilities

    fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder)

    fun applyRequest(apiKey: String?, requestBuilder: HttpRequestBuilder) {
        applyHeaders(apiKey, requestBuilder)
    }

    data object OpenAI : OpenAIProvider {
        override val id: String = "openai"
        override val defaultBaseUrl: String = "https://api.openai.com/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = true,
                responseStreaming = true,
                statefulResponses = true,
                chatCompletions = true,
                embeddingsApi = true,
                modelsApi = true,
                imagesApi = true,
                moderationsApi = true,
                batchesApi = true,
                structuredOutputs = true,
                builtinTools = true,
                providerRouting = false,
                audioApi = true,
                filesApi = true,
                uploadsApi = true,
                vectorStoresApi = true,
                conversationsApi = true,
                fineTuningApi = true,
                realtimeBootstrapApi = true,
                evalsApi = true,
                videosApi = true,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for OpenAI" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    enum class AzureAuthMode {
        API_KEY,
        BEARER,
    }

    data class Azure(
        val resourceName: String? = null,
        val apiVersion: String? = null,
        val authMode: AzureAuthMode = AzureAuthMode.API_KEY,
        val chatCompletionsDeployment: String? = null,
        val embeddingsDeployment: String? = null,
        val imageGenerationDeployment: String? = null,
    ) : OpenAIProvider {
        init {
            requireOptionalNotBlank("resourceName", resourceName)
            requireOptionalNotBlank("apiVersion", apiVersion)
            requireOptionalNotBlank("chatCompletionsDeployment", chatCompletionsDeployment)
            requireOptionalNotBlank("embeddingsDeployment", embeddingsDeployment)
            requireOptionalNotBlank("imageGenerationDeployment", imageGenerationDeployment)
        }

        override val id: String = "azure"
        override val defaultBaseUrl: String
            get() =
                resourceName
                    ?.let { "https://$it.openai.azure.com/openai/v1" }
                    ?: "https://YOUR-RESOURCE-NAME.openai.azure.com/openai/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = true,
                responseStreaming = true,
                statefulResponses = true,
                chatCompletions = true,
                embeddingsApi = true,
                modelsApi = true,
                imagesApi = true,
                moderationsApi = false,
                batchesApi = true,
                structuredOutputs = true,
                builtinTools = true,
                providerRouting = false,
                audioApi = true,
                filesApi = true,
                uploadsApi = false,
                vectorStoresApi = true,
                conversationsApi = true,
                fineTuningApi = true,
                realtimeBootstrapApi = true,
                evalsApi = true,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for Azure compatibility" }
            when (authMode) {
                AzureAuthMode.API_KEY -> requestBuilder.header("api-key", apiKey)
                AzureAuthMode.BEARER -> requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }

        override fun applyRequest(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            applyHeaders(apiKey, requestBuilder)
            apiVersion?.let { requestBuilder.parameter("api-version", it) }
        }
    }

    data class OpenRouter(
        val referer: String? = null,
        val title: String? = null,
    ) : OpenAIProvider {
        init {
            requireOptionalNotBlank("referer", referer)
            requireOptionalNotBlank("title", title)
        }

        override val id: String = "openrouter"
        override val defaultBaseUrl: String = "https://openrouter.ai/api/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = true,
                responseStreaming = true,
                statefulResponses = false,
                chatCompletions = true,
                embeddingsApi = true,
                modelsApi = true,
                imagesApi = false,
                moderationsApi = false,
                batchesApi = false,
                structuredOutputs = true,
                builtinTools = true,
                providerRouting = true,
                audioApi = false,
                filesApi = false,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for OpenRouter" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
            referer?.let { requestBuilder.header("HTTP-Referer", it) }
            title?.let { requestBuilder.header("X-Title", it) }
        }
    }

    data class Ollama(
        val bearerToken: String? = null,
    ) : OpenAIProvider {
        init {
            requireOptionalNotBlank("bearerToken", bearerToken)
        }

        override val id: String = "ollama"
        override val defaultBaseUrl: String = "http://localhost:11434/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = true,
                responseStreaming = true,
                statefulResponses = false,
                chatCompletions = true,
                embeddingsApi = true,
                modelsApi = true,
                imagesApi = true,
                moderationsApi = false,
                batchesApi = false,
                structuredOutputs = true,
                builtinTools = false,
                providerRouting = false,
                audioApi = false,
                filesApi = false,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            val token = bearerToken ?: apiKey
            if (!token.isNullOrBlank()) {
                requestBuilder.header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
    }

    data object DeepSeek : OpenAIProvider {
        override val id: String = "deepseek"
        override val defaultBaseUrl: String = "https://api.deepseek.com/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = false,
                responseStreaming = false,
                statefulResponses = false,
                chatCompletions = true,
                embeddingsApi = false,
                modelsApi = true,
                imagesApi = false,
                moderationsApi = false,
                batchesApi = false,
                structuredOutputs = true,
                builtinTools = false,
                providerRouting = false,
                audioApi = false,
                filesApi = false,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for DeepSeek" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    data object XAI : OpenAIProvider {
        override val id: String = "xai"
        override val defaultBaseUrl: String = "https://api.x.ai/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = true,
                responseStreaming = true,
                statefulResponses = true,
                chatCompletions = true,
                embeddingsApi = false,
                modelsApi = true,
                imagesApi = true,
                moderationsApi = false,
                batchesApi = false,
                structuredOutputs = true,
                builtinTools = true,
                providerRouting = false,
                audioApi = false,
                filesApi = true,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = true,
                xaiTtsApi = true,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for xAI" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    data object Groq : OpenAIProvider {
        override val id: String = "groq"
        override val defaultBaseUrl: String = "https://api.groq.com/openai/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = true,
                responseStreaming = true,
                statefulResponses = false,
                chatCompletions = true,
                embeddingsApi = false,
                modelsApi = true,
                imagesApi = false,
                moderationsApi = false,
                batchesApi = true,
                structuredOutputs = true,
                builtinTools = true,
                providerRouting = false,
                audioApi = true,
                filesApi = true,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for Groq compatibility" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    data object Gemini : OpenAIProvider {
        override val id: String = "gemini"
        override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = false,
                responseStreaming = false,
                statefulResponses = false,
                chatCompletions = true,
                embeddingsApi = true,
                modelsApi = true,
                imagesApi = true,
                moderationsApi = false,
                batchesApi = true,
                structuredOutputs = true,
                builtinTools = false,
                providerRouting = false,
                audioApi = false,
                filesApi = false,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for Gemini" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    data object Anthropic : OpenAIProvider {
        override val id: String = "anthropic"
        override val defaultBaseUrl: String = "https://api.anthropic.com/v1"
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                responsesApi = false,
                responseStreaming = false,
                statefulResponses = false,
                chatCompletions = true,
                embeddingsApi = false,
                modelsApi = false,
                imagesApi = false,
                moderationsApi = false,
                batchesApi = false,
                structuredOutputs = false,
                builtinTools = true,
                providerRouting = false,
                audioApi = false,
                filesApi = false,
                uploadsApi = false,
                vectorStoresApi = false,
                conversationsApi = false,
                fineTuningApi = false,
                realtimeBootstrapApi = false,
                evalsApi = false,
                videosApi = false,
                xaiBatchApi = false,
                xaiTtsApi = false,
            )

        override fun applyHeaders(apiKey: String?, requestBuilder: HttpRequestBuilder) {
            require(!apiKey.isNullOrBlank()) { "apiKey is required for Anthropic compatibility" }
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }
}

private fun requireOptionalNotBlank(name: String, value: String?) {
    require(value == null || value.isNotBlank()) { "$name must not be blank" }
}
