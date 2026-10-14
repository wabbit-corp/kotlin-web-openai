// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License
package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAIResponseOriginalJsonTest {
    @Test fun allNonStreamingResponseReadsRetainExactWireWithoutLoggingOrSerializingIt() = runTest {
        val body = """ {"id":"resp_raw","status":"completed","output":[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"PRIVATE-CIPHERTEXT","future_field":{"nested":true}}]} """
        HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) { install(HttpTimeout) }.use { http ->
            val api = KtorOpenAIApi(http, OpenAIApi.Config(apiKey = "synthetic", retryPolicy = null))
            val responses = listOf(
                api.createResponse(ResponseCreateRequest(ModelId("gpt-6.1-sol"), ResponseInput.Text("Synthetic"), store = false)),
                api.getResponse(ResponseId("resp_raw")),
                api.cancelResponse(ResponseId("resp_raw")),
                api.compactResponse(ResponseCompactRequest(ModelId("gpt-6.1-sol"), ResponseInput.Text("Synthetic"))),
            )
            for (response in responses) {
                assertEquals(body, response.originalJson)
                assertEquals("PRIVATE-CIPHERTEXT", response.output.single().encryptedContent)
                assertFalse(response.toString().contains("PRIVATE"))
                val serialized = OpenAIJson.encodeToString(ResponseObject.serializer(), response)
                assertFalse(serialized.contains("originalJson"))
                assertFalse(serialized.contains("future_field"))
            }
        }
    }
}
