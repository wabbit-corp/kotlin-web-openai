// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIVideoLiveSmokeTest {
    @Test
    fun `live video create get and delete smoke`() = runTest {
        val config =
            requireLiveConfig(
                loadLiveOpenAiVideoConfigOrNull(),
                "Live OpenAI video config required; set WABBIT_RUN_LIVE_OPENAI_VIDEO_TEST=true",
            )
        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }

        var createdVideoId: VideoId? = null

        try {
            val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = config.apiKey))
            val created =
                api.createVideo(
                    VideoCreateRequest(
                        model = ModelId(config.videoModel),
                        prompt = "A calm blue gradient slowly drifting for four seconds.",
                        size = VideoSize.P720X1280,
                        seconds = VideoSeconds.S4,
                    ),
                )
            createdVideoId = VideoId(created.id)

            assertTrue(created.id.isNotBlank())
            assertTrue(
                created.status == null ||
                    created.status == VideoStatus.Queued ||
                    created.status == VideoStatus.InProgress ||
                    created.status == VideoStatus.Completed,
            )

            val fetched = api.getVideo(createdVideoId)
            assertEquals(created.id, fetched.id)
            assertTrue(
                fetched.status == null ||
                    fetched.status == VideoStatus.Queued ||
                    fetched.status == VideoStatus.InProgress ||
                    fetched.status == VideoStatus.Completed,
            )
        } finally {
            try {
                createdVideoId?.let { id ->
                    val deleted = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = config.apiKey)).deleteVideo(id)
                    assertTrue(deleted.deleted)
                }
            } catch (_: Throwable) {
            }
            client.close()
        }
    }
}
