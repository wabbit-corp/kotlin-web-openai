// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIEvalsLiveSmokeTest {
    @Test
    fun `live evals smoke`() = runTest {
        val liveConfig = requireLiveConfig(loadLiveOpenAiConfigOrNull(), "Live OpenAI config required for eval smoke")

        val client =
            HttpClient(CIO) {
                install(HttpTimeout)
            }
        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = liveConfig.apiKey))

        var evalId: EvalId? = null
        var runId: EvalRunId? = null

        try {
            val suffix = System.currentTimeMillis().toString()

            val createdEval =
                api.createEval(
                    EvalCreateRequest(
                        name = "smoke-eval-$suffix",
                        dataSourceConfig =
                            buildJsonObject {
                                // Live verification note:
                                // the current eval create docs still show stored_completions, but the live run-create
                                // surface rejects that pairing for this smoke shape. A custom schema + completions
                                // run source is the verified working path here.
                                put("type", "custom")
                                put("include_sample_schema", true)
                                put(
                                    "item_schema",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "input",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                    },
                                                )
                                                put(
                                                    "ground_truth",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                    },
                                                )
                                            },
                                        )
                                        put(
                                            "required",
                                            JsonArray(
                                                listOf(
                                                    JsonPrimitive("input"),
                                                    JsonPrimitive("ground_truth"),
                                                ),
                                            ),
                                        )
                                    },
                                )
                            },
                        testingCriteria =
                            listOf(
                                buildJsonObject {
                                    put("type", "label_model")
                                    put("model", liveConfig.evalGraderModel)
                                    put(
                                        "input",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("role", "developer")
                                                    put("content", "Classify the sentiment as positive or negative.")
                                                },
                                                buildJsonObject {
                                                    put("role", "user")
                                                    put("content", "{{item.input}}")
                                                },
                                            ),
                                        ),
                                    )
                                    put("labels", JsonArray(listOf(JsonPrimitive("positive"), JsonPrimitive("negative"))))
                                    put("passing_labels", JsonArray(listOf(JsonPrimitive("positive"))))
                                    put("name", "sentiment-smoke")
                                },
                            ),
                        metadata = mapOf("suite" to "live-smoke"),
                    ),
                )
            val createdEvalId = EvalId(createdEval.id)
            evalId = createdEvalId

            val createdRun =
                api.createEvalRun(
                    createdEvalId,
                    EvalRunCreateRequest(
                        name = "smoke-run-$suffix",
                        dataSource =
                            buildJsonObject {
                                put("type", "completions")
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", "file_content")
                                        put(
                                            "content",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put(
                                                            "item",
                                                            buildJsonObject {
                                                                put("input", "The product launch received glowing reviews.")
                                                                put("ground_truth", "positive")
                                                            },
                                                        )
                                                    },
                                                ),
                                            ),
                                        )
                                    },
                                )
                                put(
                                    "input_messages",
                                    buildJsonObject {
                                        put("type", "template")
                                        put(
                                            "template",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put("role", "developer")
                                                        put("content", "Classify the sentiment as positive or negative.")
                                                    },
                                                    buildJsonObject {
                                                        put("role", "user")
                                                        put("content", "{{item.input}}")
                                                    },
                                                ),
                                            ),
                                        )
                                    },
                                )
                                put("model", liveConfig.evalModel)
                            },
                        metadata = mapOf("suite" to "live-smoke"),
                    ),
                )
            val createdRunId = EvalRunId(createdRun.id)
            runId = createdRunId

            val cancelled = api.cancelEvalRun(createdEvalId, createdRunId)
            assertEquals(createdRun.id, cancelled.id)
            assertTrue(
                cancelled.status == EvalRunStatus.Queued ||
                    cancelled.status == EvalRunStatus.InProgress ||
                    cancelled.status == EvalRunStatus.Canceled ||
                    cancelled.status == EvalRunStatus.Completed ||
                    cancelled.status == EvalRunStatus.Failed,
            )

            val fetchedRun = api.getEvalRun(createdEvalId, createdRunId)
            assertEquals(createdRun.id, fetchedRun.id)

            val outputItems = api.listEvalRunOutputItems(createdEvalId, createdRunId, EvalRunOutputItemListQuery(limit = 5))
            assertNotNull(outputItems.data)
        } finally {
            try {
                if (evalId != null && runId != null) {
                    api.deleteEvalRun(evalId, runId)
                }
            } catch (_: Throwable) {
            }
            try {
                if (evalId != null) {
                    api.deleteEval(evalId)
                }
            } catch (_: Throwable) {
            }
            client.close()
        }
    }
}
