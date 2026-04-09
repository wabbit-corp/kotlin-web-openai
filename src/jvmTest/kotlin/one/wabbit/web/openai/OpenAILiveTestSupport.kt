// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue

data class LiveOpenAiConfig(
    val apiKey: String,
    val embeddingModel: String,
    val moderationModel: String,
    val evalModel: String,
    val evalGraderModel: String,
    val chatAudioModel: String,
    val chatAudioVoice: String,
)

data class LiveOpenAiVideoConfig(
    val apiKey: String,
    val videoModel: String,
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

private const val LiveKeysEnvPathEnv = "WABBIT_LIVE_KEYS_ENV_PATH"
private const val LiveRootPrivatePathEnv = "WABBIT_LIVE_ROOT_PRIVATE_PATH"
private const val LiveVideoToggleEnv = "WABBIT_RUN_LIVE_OPENAI_VIDEO_TEST"

private fun shouldRunLiveTests(): Boolean =
    System.getenv("WABBIT_RUN_LIVE_OPENAI_TEST") == "true" ||
        System.getenv("WABBIT_RUN_LIVE_PROVIDER_TESTS") == "true"

internal fun <T : Any> requireLiveConfig(
    config: T?,
    reason: String,
): T {
    assumeTrue(reason, config != null)
    return checkNotNull(config)
}

fun loadLiveOpenAiConfigOrNull(): LiveOpenAiConfig? {
    if (!shouldRunLiveTests()) return null
    val apiKey = loadSecret("OPENAI_API_KEY", "OPENAI_KEY") ?: return null
    if (!apiKey.startsWith("sk-")) return null
    val embeddingModel = System.getenv("WABBIT_LIVE_OPENAI_EMBEDDING_MODEL")?.trim().orEmpty().ifBlank { "text-embedding-3-small" }
    val moderationModel = System.getenv("WABBIT_LIVE_OPENAI_MODERATION_MODEL")?.trim().orEmpty().ifBlank { "omni-moderation-latest" }
    val evalModel = System.getenv("WABBIT_LIVE_OPENAI_EVAL_MODEL")?.trim().orEmpty().ifBlank { "gpt-4o-mini" }
    val evalGraderModel = System.getenv("WABBIT_LIVE_OPENAI_EVAL_GRADER_MODEL")?.trim().orEmpty().ifBlank { evalModel }
    val chatAudioModel = System.getenv("WABBIT_LIVE_OPENAI_CHAT_AUDIO_MODEL")?.trim().orEmpty().ifBlank { "gpt-audio-mini" }
    val chatAudioVoice = System.getenv("WABBIT_LIVE_OPENAI_CHAT_AUDIO_VOICE")?.trim().orEmpty().ifBlank { "alloy" }
    return LiveOpenAiConfig(
        apiKey = apiKey,
        embeddingModel = embeddingModel,
        moderationModel = moderationModel,
        evalModel = evalModel,
        evalGraderModel = evalGraderModel,
        chatAudioModel = chatAudioModel,
        chatAudioVoice = chatAudioVoice,
    )
}

fun loadLiveOpenAiVideoConfigOrNull(): LiveOpenAiVideoConfig? {
    if (System.getenv(LiveVideoToggleEnv) != "true") return null
    val apiKey = loadSecret("OPENAI_API_KEY", "OPENAI_KEY") ?: return null
    if (!apiKey.startsWith("sk-")) return null
    val videoModel = System.getenv("WABBIT_LIVE_OPENAI_VIDEO_MODEL")?.trim().orEmpty().ifBlank { "sora-2" }
    return LiveOpenAiVideoConfig(
        apiKey = apiKey,
        videoModel = videoModel,
    )
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

// Live test secret fallback is deliberately narrow:
// 1. direct env vars
// 2. explicitly configured secret files via WABBIT_LIVE_KEYS_ENV_PATH / WABBIT_LIVE_ROOT_PRIVATE_PATH
// 3. repo-root keys.env / root.private.clj
// It does not walk parent directories looking for secrets.
private fun loadKeysEnv(): Map<String, String> {
    val keysPath = resolveLiveKeysEnvPath() ?: return emptyMap()
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
    val rootPrivate = resolveLiveRootPrivatePath() ?: return null
    val text = runCatching { Files.readString(rootPrivate) }.getOrNull() ?: return null
    val match = Regex("""\(openai-key\s+"([^"]+)"\)""").find(text) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun resolveLiveKeysEnvPath(
    env: Map<String, String> = System.getenv(),
    cwd: Path = Paths.get("").toAbsolutePath().normalize(),
    exists: (Path) -> Boolean = Files::exists,
): Path? = resolveLiveSecretPath(env[LiveKeysEnvPathEnv], "keys.env", cwd, exists)

internal fun resolveLiveRootPrivatePath(
    env: Map<String, String> = System.getenv(),
    cwd: Path = Paths.get("").toAbsolutePath().normalize(),
    exists: (Path) -> Boolean = Files::exists,
): Path? = resolveLiveSecretPath(env[LiveRootPrivatePathEnv], "root.private.clj", cwd, exists)

internal fun resolveLiveSecretPath(
    configuredPath: String?,
    defaultFileName: String,
    cwd: Path = Paths.get("").toAbsolutePath().normalize(),
    exists: (Path) -> Boolean = Files::exists,
): Path? {
    configuredPath
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { rawPath ->
            val explicitPath = Paths.get(rawPath).toAbsolutePath().normalize()
            return explicitPath.takeIf(exists)
        }

    val repoRoot = detectLiveTestRepoRoot(cwd, exists)
    val defaultPath = repoRoot.resolve(defaultFileName).normalize()
    return defaultPath.takeIf(exists)
}

internal fun detectLiveTestRepoRoot(
    cwd: Path = Paths.get("").toAbsolutePath().normalize(),
    exists: (Path) -> Boolean = Files::exists,
): Path =
    generateSequence(cwd) { current -> current.parent }
        .firstOrNull { candidate ->
            exists(candidate.resolve(".git")) ||
                exists(candidate.resolve("settings.gradle.kts")) ||
                exists(candidate.resolve("build.gradle.kts"))
        }
        ?: cwd
