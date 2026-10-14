// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License
package one.wabbit.web.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponsePhaseTest {
    @Test fun outputPhasePreservesCommentaryFinalUnknownAndAbsentValues() {
        for (phase in listOf("commentary", "final_answer", "future_phase")) {
            val json = """{"id":"resp_test","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","phase":"$phase","content":[]}]}"""
            val response = OpenAIJson.decodeFromString<ResponseObject>(json)
            assertEquals(phase, response.output.single().phase)
            assertEquals(response, OpenAIJson.decodeFromString<ResponseObject>(OpenAIJson.encodeToString(ResponseObject.serializer(), response)))
        }
        assertNull(OpenAIJson.decodeFromString<ResponseObject>("""{"id":"resp_test","output":[{"type":"message"}]}""").output.single().phase)
    }
}
