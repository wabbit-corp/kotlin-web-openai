package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompatibilityLiveSmokeTest {
    @Test
    fun `live openrouter smoke`() = runTest {
        val config = loadLiveOpenRouterConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.OpenRouter(title = "kotlin-web-openai smoke")
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = provider.defaultBaseUrl,
                    ),
                )
            val models = api.listModels()
            assertTrue(models.data.isNotEmpty())
            val modelId = models.data.firstOrNull { it.id == config.chatModel }?.id ?: models.data.first().id

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(modelId),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                        maxCompletionTokens = 16,
                    ),
                )

            assertTrue(chat.choices.isNotEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `live azure smoke`() = runTest {
        val config = loadLiveAzureConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.Azure(apiVersion = config.apiVersion)
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl,
                    ),
                )

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(config.chatModel),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                        maxCompletionTokens = 16,
                    ),
                )

            assertTrue(chat.choices.isNotEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `live groq smoke`() = runTest {
        val config = loadLiveGroqConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.Groq
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = provider.defaultBaseUrl,
                    ),
                )

            val models = api.listModels()
            assertTrue(models.data.isNotEmpty())
            val modelId = models.data.firstOrNull { it.id == config.chatModel }?.id ?: models.data.first().id

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(modelId),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                        maxCompletionTokens = 16,
                    ),
                )

            assertTrue(chat.choices.isNotEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `live xai smoke`() = runTest {
        val config = loadLiveXAiConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.XAI
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = provider.defaultBaseUrl,
                    ),
                )
            val models = api.listModels()
            assertTrue(models.data.isNotEmpty())
            val modelId = models.data.firstOrNull { it.id == config.responseModel }?.id ?: models.data.first().id

            val response =
                api.createResponse(
                    ResponseCreateRequest(
                        model = ModelId(modelId),
                        input = ResponseInput.Text("Reply with ok."),
                        providerOptions = XAIRequestOptions(reasoningEffort = ReasoningEffort.LOW),
                    ),
                )
            assertTrue(response.id.isNotBlank())

            val uploaded =
                api.uploadFile(
                    FileCreateRequest(
                        purpose = FilePurpose.ASSISTANTS,
                        file = BinaryUpload("smoke.txt", "hello".encodeToByteArray(), "text/plain"),
                    ),
                )
            assertTrue(uploaded.id.isNotBlank())

            val deleted = api.deleteFile(FileId(uploaded.id))
            assertTrue(deleted.deleted)
        } finally {
            client.close()
        }
    }

    @Test
    fun `live deepseek smoke`() = runTest {
        val config = loadLiveDeepSeekConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.DeepSeek
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = provider.defaultBaseUrl,
                    ),
                )

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(config.chatModel),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                        maxCompletionTokens = 16,
                    ),
                )

            assertTrue(chat.choices.isNotEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `live gemini smoke`() = runTest {
        val config = loadLiveGeminiConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.Gemini
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = provider.defaultBaseUrl,
                    ),
                )

            val models = api.listModels()
            assertTrue(models.data.isNotEmpty())
            val chatModel = models.data.firstOrNull { it.id == config.chatModel }?.id ?: models.data.first().id

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(chatModel),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                    ),
                )
            assertTrue(chat.choices.isNotEmpty())

            val embedding =
                api.createEmbedding(
                    EmbeddingCreateRequest(
                        model = ModelId(config.embeddingModel),
                        input = EmbeddingInput.Text("hello"),
                    ),
                )
            assertTrue(embedding.data.isNotEmpty())
            assertNotNull(embedding.firstFloatEmbeddingOrNull())
        } finally {
            client.close()
        }
    }

    @Test
    fun `live anthropic compatibility smoke`() = runTest {
        val config = loadLiveAnthropicConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.Anthropic
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = config.apiKey,
                        baseUrl = provider.defaultBaseUrl,
                    ),
                )

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(config.chatModel),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                    ),
                )

            assertTrue(chat.choices.isNotEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `live ollama smoke`() = runTest {
        val config = loadLiveOllamaConfigOrNull() ?: return@runTest
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val provider = OpenAIProvider.Ollama()
            val api =
                KtorOpenAIApi(
                    client,
                    OpenAIApi.Config(
                        provider = provider,
                        apiKey = provider.bearerToken,
                        baseUrl = config.baseUrl,
                    ),
                )

            val models = api.listModels()
            if (models.data.isEmpty()) return@runTest
            val model =
                config.chatModel?.let { requested -> models.data.firstOrNull { it.id == requested }?.id }
                    ?: models.data.firstOrNull { it.id == "qwen3-vl:4b" }?.id
                    ?: models.data.first().id

            val chat =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(model),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                        providerOptions = OllamaRequestOptions(raw = false),
                    ),
                )

            assertTrue(chat.choices.isNotEmpty())
        } finally {
            client.close()
        }
    }
}
