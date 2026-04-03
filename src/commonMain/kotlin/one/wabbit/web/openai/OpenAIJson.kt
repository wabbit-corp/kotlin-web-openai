package one.wabbit.web.openai

import kotlinx.serialization.json.Json

@PublishedApi
internal val OpenAIJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    prettyPrint = false
}
