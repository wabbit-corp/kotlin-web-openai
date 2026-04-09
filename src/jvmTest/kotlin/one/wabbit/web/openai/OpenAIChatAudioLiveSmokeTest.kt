// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIChatAudioLiveSmokeTest {
    @Test
    fun `live chat audio smoke`() = runTest {
        val config = requireLiveConfig(loadLiveOpenAiConfigOrNull(), "Live OpenAI config required for chat audio smoke")
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        try {
            val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = config.apiKey))
            val response =
                api.createChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId(config.chatAudioModel),
                        messages =
                            listOf(
                                ChatMessage(
                                    role = ChatRole.USER,
                                    content = ChatMessageContent.Text("Reply with exactly the single word ok."),
                                ),
                            ),
                        modalities = listOf(ChatCompletionModality.TEXT, ChatCompletionModality.AUDIO),
                        audio =
                            ChatCompletionAudioConfig(
                                format = ChatCompletionAudioFormat.MP3,
                                voice = SpeechVoice.BuiltIn(config.chatAudioVoice),
                            ),
                        maxCompletionTokens = 32,
                    ),
                )

            assertTrue(response.choices.isNotEmpty())
            assertEquals(1, response.choices.size)
            // Live note:
            // the service reliably returned audio data plus a transcript here, while the plain text
            // assistant content may be empty even when requesting both text and audio modalities.
            val outputText = response.choiceTexts().singleOrNull().orEmpty()
            val audio = assertNotNull(response.audioOutputs().singleOrNull())
            assertTrue(audio.id.orEmpty().isNotBlank())
            assertTrue(audio.data.orEmpty().isNotBlank())
            assertTrue(audio.transcript.orEmpty().isNotBlank())
            assertTrue(outputText.isNotBlank() || audio.transcript.orEmpty().isNotBlank())
        } finally {
            client.close()
        }
    }
}
