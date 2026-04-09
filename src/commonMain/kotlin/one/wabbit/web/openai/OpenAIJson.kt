// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.json.Json

@PublishedApi
internal val OpenAIJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    prettyPrint = false
}
