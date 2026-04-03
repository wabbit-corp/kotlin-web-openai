package one.wabbit.web.openai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class LiveOpenAiConfig(
    val apiKey: String,
    val embeddingModel: String,
    val moderationModel: String,
)

data class LiveOpenRouterConfig(
    val apiKey: String,
    val chatModel: String,
)

data class LiveAzureConfig(
    val apiKey: String,
    val baseUrl: String,
    val chatModel: String,
    val apiVersion: String? = null,
)

data class LiveGroqConfig(
    val apiKey: String,
    val chatModel: String,
)

data class LiveXAiConfig(
    val apiKey: String,
    val responseModel: String,
)

data class LiveDeepSeekConfig(
    val apiKey: String,
    val chatModel: String,
)

data class LiveGeminiConfig(
    val apiKey: String,
    val chatModel: String,
    val embeddingModel: String,
)

data class LiveAnthropicConfig(
    val apiKey: String,
    val chatModel: String,
)

data class LiveOllamaConfig(
    val baseUrl: String,
    val chatModel: String?,
)

private fun shouldRunLiveTests(): Boolean =
    System.getenv("WABBIT_RUN_LIVE_OPENAI_TEST") == "true" ||
        System.getenv("WABBIT_RUN_LIVE_PROVIDER_TESTS") == "true"

fun loadLiveOpenAiConfigOrNull(): LiveOpenAiConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("OPENAI_API_KEY", "OPENAI_KEY") ?: return null
    if (!apiKey.startsWith("sk-")) return null
    val embeddingModel = System.getenv("WABBIT_LIVE_OPENAI_EMBEDDING_MODEL")?.trim().orEmpty().ifBlank { "text-embedding-3-small" }
    val moderationModel = System.getenv("WABBIT_LIVE_OPENAI_MODERATION_MODEL")?.trim().orEmpty().ifBlank { "omni-moderation-latest" }
    return LiveOpenAiConfig(apiKey = apiKey, embeddingModel = embeddingModel, moderationModel = moderationModel)
}

fun loadLiveOpenRouterConfigOrNull(): LiveOpenRouterConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("OPENROUTER_API_KEY", "OPENROUTER_KEY") ?: return null
    val model = System.getenv("WABBIT_LIVE_OPENROUTER_CHAT_MODEL")?.trim().orEmpty().ifBlank { "openai/gpt-4.1-nano" }
    return LiveOpenRouterConfig(apiKey = apiKey, chatModel = model)
}

fun loadLiveAzureConfigOrNull(): LiveAzureConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("AZURE_OPENAI_API_KEY", "AZURE_OPENAI_KEY") ?: return null
    val baseUrl = System.getenv("WABBIT_LIVE_AZURE_BASE_URL")?.trim().orEmpty().ifBlank { return null }
    val chatModel = System.getenv("WABBIT_LIVE_AZURE_CHAT_MODEL")?.trim().orEmpty().ifBlank { "gpt-4.1-mini" }
    val apiVersion = System.getenv("WABBIT_LIVE_AZURE_API_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
    return LiveAzureConfig(apiKey = apiKey, baseUrl = baseUrl, chatModel = chatModel, apiVersion = apiVersion)
}

fun loadLiveGroqConfigOrNull(): LiveGroqConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("GROQ_API_KEY", "GROQ_KEY") ?: return null
    val model = System.getenv("WABBIT_LIVE_GROQ_CHAT_MODEL")?.trim().orEmpty().ifBlank { "openai/gpt-oss-20b" }
    return LiveGroqConfig(apiKey = apiKey, chatModel = model)
}

fun loadLiveXAiConfigOrNull(): LiveXAiConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("XAI_API_KEY", "XAI_KEY") ?: return null
    val model = System.getenv("WABBIT_LIVE_XAI_RESPONSE_MODEL")?.trim().orEmpty().ifBlank { "grok-4-fast-non-reasoning" }
    return LiveXAiConfig(apiKey = apiKey, responseModel = model)
}

fun loadLiveDeepSeekConfigOrNull(): LiveDeepSeekConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("DEEPSEEK_API_KEY", "DEEPSEEK_KEY") ?: return null
    val model = System.getenv("WABBIT_LIVE_DEEPSEEK_CHAT_MODEL")?.trim().orEmpty().ifBlank { "deepseek-chat" }
    return LiveDeepSeekConfig(apiKey = apiKey, chatModel = model)
}

fun loadLiveGeminiConfigOrNull(): LiveGeminiConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("GEMINI_API_KEY", "GEMINI_KEY") ?: return null
    val chatModel = System.getenv("WABBIT_LIVE_GEMINI_CHAT_MODEL")?.trim().orEmpty().ifBlank { "gemini-2.5-flash" }
    val embeddingModel = System.getenv("WABBIT_LIVE_GEMINI_EMBEDDING_MODEL")?.trim().orEmpty().ifBlank { "gemini-embedding-001" }
    return LiveGeminiConfig(apiKey = apiKey, chatModel = chatModel, embeddingModel = embeddingModel)
}

fun loadLiveAnthropicConfigOrNull(): LiveAnthropicConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("ANTHROPIC_API_KEY", "ANTHROPIC_KEY") ?: return null
    val chatModel = System.getenv("WABBIT_LIVE_ANTHROPIC_CHAT_MODEL")?.trim().orEmpty().ifBlank { "claude-opus-4-1-20250805" }
    return LiveAnthropicConfig(apiKey = apiKey, chatModel = chatModel)
}

fun loadLiveOllamaConfigOrNull(): LiveOllamaConfig? {
    if (!shouldRunLiveTests()) return null
    val baseUrl = System.getenv("WABBIT_LIVE_OLLAMA_BASE_URL")?.trim().orEmpty().ifBlank { OpenAIProvider.Ollama().defaultBaseUrl }
    val chatModel = System.getenv("WABBIT_LIVE_OLLAMA_CHAT_MODEL")?.trim()?.takeIf { it.isNotEmpty() }
    return LiveOllamaConfig(baseUrl = baseUrl, chatModel = chatModel)
}

private fun loadSecret(vararg envKeys: String): String? {
    envKeys.forEach { key ->
        val direct = System.getenv(key)?.trim().orEmpty()
        if (direct.isNotEmpty()) return direct
    }

    val keysEnv = loadKeysEnv()
    envKeys.forEach { key ->
        val value = keysEnv[key]?.trim().orEmpty()
        if (value.isNotEmpty()) return value
    }

    if ("OPENAI_API_KEY" in envKeys || "OPENAI_KEY" in envKeys) {
        return loadOpenAiKeyFromRootPrivate()
    }
    return null
}

private fun loadKeysEnv(): Map<String, String> {
    val keysPath = findKeysEnvPath() ?: return emptyMap()
    val text = runCatching { Files.readString(keysPath) }.getOrNull() ?: return emptyMap()
    return text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
}

private fun loadOpenAiKeyFromRootPrivate(): String? {
    val rootPrivate = findRootPrivatePath() ?: return null
    val text = runCatching { Files.readString(rootPrivate) }.getOrNull() ?: return null
    val match = Regex("""\(openai-key\s+"([^"]+)"\)""").find(text) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun findRootPrivatePath(): Path? {
    val cwd = Paths.get("").toAbsolutePath().normalize()
    val direct = cwd.parent?.resolve("root.private.clj")
    if (direct != null && Files.exists(direct)) return direct
    return generateSequence(cwd) { current -> current.parent }
        .map { it.resolve("root.private.clj") }
        .firstOrNull { Files.exists(it) }
}

private fun findKeysEnvPath(): Path? {
    val cwd = Paths.get("").toAbsolutePath().normalize()
    val direct = cwd.parent?.resolve("keys.env")
    if (direct != null && Files.exists(direct)) return direct
    return generateSequence(cwd) { current -> current.parent }
        .map { it.resolve("keys.env") }
        .firstOrNull { Files.exists(it) }
}
