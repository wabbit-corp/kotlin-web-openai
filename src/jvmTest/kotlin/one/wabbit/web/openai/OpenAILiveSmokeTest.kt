// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAILiveSmokeTest {
    @Test
    fun `live embeddings models and moderations smoke`() = runTest {
        val config = requireLiveConfig(loadLiveOpenAiConfigOrNull(), "Live OpenAI config required")
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = config.apiKey))
            val models = api.listModels()
            assertTrue(models.data.isNotEmpty())

            val embeddings =
                api.createEmbedding(
                    EmbeddingCreateRequest(
                        model = ModelId(config.embeddingModel),
                        input = EmbeddingInput.Text("hello"),
                    ),
                )
            assertTrue(embeddings.data.isNotEmpty())
            assertNotNull(embeddings.firstFloatEmbeddingOrNull())

            val moderation =
                api.createModeration(
                    ModerationCreateRequest(
                        model = ModelId(config.moderationModel),
                        input = ModerationInput.Text("hello world"),
                    ),
                )
            assertTrue(moderation.results.isNotEmpty())
        } finally {
            client.close()
        }
    }
}
