package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor

sealed interface ResponseInput {
    fun toJson(): JsonElement

    data class Text(
        val text: String,
    ) : ResponseInput {
        init {
            require(text.isNotBlank()) { "text input must not be blank" }
        }

        override fun toJson(): JsonElement = JsonPrimitive(text)
    }

    data class Items(
        val items: List<ResponseInputItem>,
    ) : ResponseInput {
        init {
            require(items.isNotEmpty()) { "items input must not be empty" }
        }

        override fun toJson(): JsonElement = buildJsonArray {
            items.forEach { add(it.toJson()) }
        }
    }
}

enum class ResponseRole(val wireName: String) {
    SYSTEM("system"),
    DEVELOPER("developer"),
    USER("user"),
    ASSISTANT("assistant"),
}

enum class ResponseMessagePhase(val wireName: String) {
    COMMENTARY("commentary"),
    FINAL_ANSWER("final_answer"),
}

sealed interface ResponseInputItem {
    fun toJson(): JsonObject

    data class Message(
        val role: ResponseRole,
        val phase: ResponseMessagePhase? = null,
        val content: List<ResponseInputContent>,
    ) : ResponseInputItem {
        init {
            require(content.isNotEmpty()) { "message content must not be empty" }
            require(phase == null || role == ResponseRole.ASSISTANT) {
                "message phase is only supported for assistant messages"
            }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "message")
                put("role", role.wireName)
                phase?.let { put("phase", it.wireName) }
                putJsonArray("content") {
                    content.forEach { add(it.toJson()) }
                }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : ResponseInputItem {
        override fun toJson(): JsonObject = json
    }

    data class FunctionCallOutput(
        val callId: ToolCallId,
        val outputText: String? = null,
        val outputContent: List<ResponseInputContent>? = null,
    ) : ResponseInputItem {
        init {
            require(outputText != null || outputContent != null) {
                "function call output must provide outputText or outputContent"
            }
            require(outputText == null || outputText.isNotBlank()) {
                "function call outputText must not be blank when set"
            }
            require(outputContent == null || outputContent.isNotEmpty()) {
                "function call outputContent must not be empty when set"
            }
        }

        constructor(callId: ToolCallId, output: String) : this(callId = callId, outputText = output)

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", callId.value)
                outputText?.let { put("output", it) }
                outputContent?.let { content ->
                    putJsonArray("output") {
                        content.forEach { add(it.toJson()) }
                    }
                }
            }
    }

    data class ItemReference(
        val id: String,
    ) : ResponseInputItem {
        init {
            require(id.isNotBlank()) { "item reference id must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "item_reference")
                put("id", id)
            }
    }

    data class McpApprovalResponse(
        val approvalRequestId: McpApprovalRequestId,
        val approve: Boolean,
        val reason: String? = null,
    ) : ResponseInputItem {
        init {
            require(reason == null || reason.isNotBlank()) { "mcp approval response reason must not be blank when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "mcp_approval_response")
                put("approval_request_id", approvalRequestId.value)
                put("approve", approve)
                reason?.let { put("reason", it) }
            }
    }
}

sealed interface ResponseInputContent {
    fun toJson(): JsonObject

    data class InputText(
        val text: String,
    ) : ResponseInputContent {
        init {
            require(text.isNotBlank()) { "input text must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "input_text")
                put("text", text)
            }
    }

    data class InputImage(
        val imageUrl: String? = null,
        val fileId: FileId? = null,
        val detail: InputImageDetail? = null,
    ) : ResponseInputContent {
        init {
            require(imageUrl != null || fileId != null) { "input image must provide imageUrl or fileId" }
            require(!(imageUrl != null && fileId != null)) { "input image cannot provide both imageUrl and fileId" }
            require(imageUrl == null || imageUrl.isNotBlank()) { "imageUrl must not be blank when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "input_image")
                imageUrl?.let { put("image_url", it) }
                fileId?.let { put("file_id", it.value) }
                detail?.let { put("detail", it.wireName) }
            }
    }

    data class InputFile(
        val fileId: FileId? = null,
        val fileData: String? = null,
        val fileUrl: String? = null,
        val filename: String? = null,
    ) : ResponseInputContent {
        init {
            require(fileId != null || fileData != null || fileUrl != null) {
                "input file must provide fileId, fileData, or fileUrl"
            }
            require(listOfNotNull(fileId, fileData, fileUrl).size == 1) {
                "input file must provide exactly one of fileId, fileData, or fileUrl"
            }
            require(fileData == null || fileData.isNotBlank()) { "fileData must not be blank when set" }
            require(fileUrl == null || fileUrl.isNotBlank()) { "fileUrl must not be blank when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "input_file")
                fileId?.let { put("file_id", it.value) }
                fileData?.let { put("file_data", it) }
                fileUrl?.let { put("file_url", it) }
                filename?.let { put("filename", it) }
            }
    }

    data class InputAudio(
        val data: String,
        val format: InputAudioFormat,
    ) : ResponseInputContent {
        init {
            require(data.isNotBlank()) { "input audio data must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "input_audio")
                putJsonObject("input_audio") {
                    put("data", data)
                    put("format", format.wireName)
                }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : ResponseInputContent {
        override fun toJson(): JsonObject = json
    }
}

sealed interface ResponseTool {
    fun toJson(): JsonObject

    enum class WebSearchType(val wireName: String) {
        WEB_SEARCH("web_search"),
        WEB_SEARCH_PREVIEW("web_search_preview"),
        WEB_SEARCH_2025_08_26("web_search_2025_08_26"),
        WEB_SEARCH_PREVIEW_2025_03_11("web_search_preview_2025_03_11"),
    }

    enum class ComputerUseType(val wireName: String) {
        COMPUTER_USE("computer_use"),
        COMPUTER_USE_PREVIEW("computer_use_preview"),
    }

    enum class CustomGrammarSyntax(val wireName: String) {
        LARK("lark"),
        REGEX("regex"),
    }

    sealed interface McpAllowedTools {
        fun toJson(): JsonElement

        data class Filter(
            val readOnly: Boolean? = null,
            val toolNames: List<String> = emptyList(),
        ) : McpAllowedTools {
            init {
                require(readOnly != null || toolNames.isNotEmpty()) {
                    "mcp allowed tools filter must provide readOnly or toolNames"
                }
                require(toolNames.all { it.isNotBlank() }) {
                    "mcp allowed tools filter toolNames must not contain blank values"
                }
            }

            override fun toJson(): JsonElement =
                buildJsonObject {
                    readOnly?.let { put("read_only", it) }
                    if (toolNames.isNotEmpty()) {
                        putJsonArray("tool_names") {
                            toolNames.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
        }
    }

    sealed interface McpRequireApproval {
        fun toJson(): JsonElement

        data object Always : McpRequireApproval {
            override fun toJson(): JsonElement = JsonPrimitive("always")
        }

        data object Never : McpRequireApproval {
            override fun toJson(): JsonElement = JsonPrimitive("never")
        }

        data class Filter(
            val policy: Policy,
            val readOnly: Boolean? = null,
            val toolNames: List<String> = emptyList(),
        ) : McpRequireApproval {
            init {
                require(readOnly != null || toolNames.isNotEmpty()) {
                    "mcp approval filter must provide readOnly or toolNames"
                }
                require(toolNames.all { it.isNotBlank() }) {
                    "mcp approval filter toolNames must not contain blank values"
                }
            }

            override fun toJson(): JsonElement =
                buildJsonObject {
                    putJsonObject(policy.wireName) {
                        readOnly?.let { put("read_only", it) }
                        if (toolNames.isNotEmpty()) {
                            putJsonArray("tool_names") {
                                toolNames.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                }
        }

        enum class Policy(val wireName: String) {
            ALWAYS("always"),
            NEVER("never"),
        }
    }

    sealed interface CustomInputFormat {
        fun toJson(): JsonObject

        data class Grammar(
            val definition: String,
            val syntax: CustomGrammarSyntax = CustomGrammarSyntax.LARK,
        ) : CustomInputFormat {
            init {
                require(definition.isNotBlank()) { "custom tool grammar definition must not be blank" }
            }

            override fun toJson(): JsonObject =
                buildJsonObject {
                    put("type", "grammar")
                    put("definition", definition)
                    put("syntax", syntax.wireName)
                }
        }

        data class Raw(
            val json: JsonObject,
        ) : CustomInputFormat {
            override fun toJson(): JsonObject = json
        }
    }

    data class LocalSkill(
        val name: String,
        val path: String,
        val description: String? = null,
    ) {
        init {
            require(name.isNotBlank()) { "local skill name must not be blank" }
            require(path.isNotBlank()) { "local skill path must not be blank" }
            require(description == null || description.isNotBlank()) {
                "local skill description must not be blank when set"
            }
        }

        fun toJson(): JsonObject =
            buildJsonObject {
                put("name", name)
                put("path", path)
                description?.let { put("description", it) }
            }
    }

    sealed interface ShellEnvironment {
        fun toJson(): JsonObject

        data class Local(
            val skills: List<LocalSkill> = emptyList(),
            val extraOptions: JsonExtras? = null,
        ) : ShellEnvironment {
            override fun toJson(): JsonObject =
                buildJsonObject {
                    put("type", "local")
                    if (skills.isNotEmpty()) {
                        putJsonArray("skills") {
                            skills.forEach { add(it.toJson()) }
                        }
                    }
                    putJsonExtras(extraOptions)
                }
        }

        data class Raw(
            val json: JsonObject,
        ) : ShellEnvironment {
            override fun toJson(): JsonObject = json
        }
    }

    data class Function(
        val name: String,
        val description: String? = null,
        val parameters: JsonObject? = null,
        val strict: Boolean? = null,
        val deferLoading: Boolean? = null,
    ) : ResponseTool {
        init {
            require(name.isNotBlank()) { "function tool name must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "function")
                put("name", name)
                description?.let { put("description", it) }
                parameters?.let { put("parameters", it) }
                strict?.let { put("strict", it) }
                deferLoading?.let { put("defer_loading", it) }
            }
    }

    data class Custom(
        val name: String,
        val description: String? = null,
        val format: CustomInputFormat? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(name.isNotBlank()) { "custom tool name must not be blank" }
            require(description == null || description.isNotBlank()) {
                "custom tool description must not be blank when set"
            }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "custom")
                put("name", name)
                description?.let { put("description", it) }
                format?.let { put("format", it.toJson()) }
                putJsonExtras(extraOptions)
            }
    }

    data class WebSearch(
        val toolType: WebSearchType = WebSearchType.WEB_SEARCH,
        val userLocation: JsonObject? = null,
        val searchContextSize: SearchContextSize? = null,
        val filters: JsonObject? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", toolType.wireName)
                userLocation?.let { put("user_location", it) }
                searchContextSize?.let { put("search_context_size", it.wireName) }
                filters?.let { put("filters", it) }
                putJsonExtras(extraOptions)
            }
    }

    data class FileSearch(
        val vectorStoreIds: List<VectorStoreId>,
        val maxNumResults: Int? = null,
        val filters: JsonObject? = null,
        val rankingOptions: JsonObject? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(vectorStoreIds.isNotEmpty()) { "file search must contain at least one vector store id" }
            require(maxNumResults == null || maxNumResults > 0) { "maxNumResults must be positive when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "file_search")
                putJsonArray("vector_store_ids") {
                    vectorStoreIds.forEach { add(JsonPrimitive(it.value)) }
                }
                maxNumResults?.let { put("max_num_results", it) }
                filters?.let { put("filters", it) }
                rankingOptions?.let { put("ranking_options", it) }
                putJsonExtras(extraOptions)
            }
    }

    data class ImageGeneration(
        val background: ImageBackground? = null,
        val moderation: ImageModeration? = null,
        val outputCompression: Int? = null,
        val outputFormat: ImageOutputFormat? = null,
        val partialImages: Int? = null,
        val quality: ImageQuality? = null,
        val size: ImageSize? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(outputCompression == null || outputCompression in 0..100) {
                "image generation outputCompression must be between 0 and 100 when set"
            }
            require(partialImages == null || partialImages in 0..3) {
                "image generation partialImages must be between 0 and 3 when set"
            }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "image_generation")
                background?.let { put("background", it.wireName) }
                moderation?.let { put("moderation", it.wireName) }
                outputCompression?.let { put("output_compression", it) }
                outputFormat?.let { put("output_format", it.wireName) }
                partialImages?.let { put("partial_images", it) }
                quality?.let { put("quality", it.wireName) }
                size?.let { put("size", it.wireName) }
                putJsonExtras(extraOptions)
            }
    }

    data class CodeInterpreter(
        val container: JsonObject? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "code_interpreter")
                container?.let { put("container", it) }
                putJsonExtras(extraOptions)
            }
    }

    data object LocalShell : ResponseTool {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "local_shell") }
    }

    data class Shell(
        val environment: ShellEnvironment? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "shell")
                environment?.let { put("environment", it.toJson()) }
                putJsonExtras(extraOptions)
            }
    }

    data object ApplyPatch : ResponseTool {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "apply_patch") }
    }

    data class ComputerUse(
        val toolType: ComputerUseType = ComputerUseType.COMPUTER_USE,
        val environment: String? = null,
        val displayWidth: Int? = null,
        val displayHeight: Int? = null,
        val displayNumber: Int? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(environment == null || environment.isNotBlank()) { "computer use environment must not be blank when set" }
            require(displayWidth == null || displayWidth > 0) { "computer use display width must be positive when set" }
            require(displayHeight == null || displayHeight > 0) { "computer use display height must be positive when set" }
            require(displayNumber == null || displayNumber >= 0) { "computer use display number must be non-negative when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", toolType.wireName)
                environment?.let { put("environment", it) }
                displayWidth?.let { put("display_width", it) }
                displayHeight?.let { put("display_height", it) }
                displayNumber?.let { put("display_number", it) }
                putJsonExtras(extraOptions)
            }
    }

    data class XSearch(
        val returnCitations: Boolean? = null,
        val maxResults: Int? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(maxResults == null || maxResults > 0) { "x_search maxResults must be positive when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "x_search")
                returnCitations?.let { put("return_citations", it) }
                maxResults?.let { put("max_results", it) }
                putJsonExtras(extraOptions)
            }
    }

    data class Hosted(
        val type: String,
        val options: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(type.isNotBlank()) { "hosted tool type must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", type)
                putJsonExtras(options)
            }
    }

    data class Mcp(
        val serverLabel: String,
        val serverUrl: String? = null,
        val connectorId: String? = null,
        val serverDescription: String? = null,
        val headers: JsonObject? = null,
        val allowedTools: List<String> = emptyList(),
        val allowedToolFilter: McpAllowedTools.Filter? = null,
        val requireApproval: McpRequireApproval? = null,
        val deferLoading: Boolean? = null,
        val extraOptions: JsonExtras? = null,
    ) : ResponseTool {
        init {
            require(serverLabel.isNotBlank()) { "mcp serverLabel must not be blank" }
            require(serverUrl != null || connectorId != null) {
                "mcp tool must provide serverUrl or connectorId"
            }
            require(serverUrl == null || serverUrl.isNotBlank()) { "mcp serverUrl must not be blank when set" }
            require(connectorId == null || connectorId.isNotBlank()) { "mcp connectorId must not be blank when set" }
            require(serverDescription == null || serverDescription.isNotBlank()) {
                "mcp serverDescription must not be blank when set"
            }
            require(allowedTools.all { it.isNotBlank() }) { "mcp allowedTools must not contain blank values" }
            require(!(allowedTools.isNotEmpty() && allowedToolFilter != null)) {
                "mcp tool cannot provide allowedTools and allowedToolFilter together"
            }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "mcp")
                put("server_label", serverLabel)
                serverUrl?.let { put("server_url", it) }
                connectorId?.let { put("connector_id", it) }
                serverDescription?.let { put("server_description", it) }
                headers?.let { put("headers", it) }
                if (allowedTools.isNotEmpty()) {
                    putJsonArray("allowed_tools") {
                        allowedTools.forEach { add(JsonPrimitive(it)) }
                    }
                }
                allowedToolFilter?.let { put("allowed_tools", it.toJson()) }
                requireApproval?.let { put("require_approval", it.toJson()) }
                deferLoading?.let { put("defer_loading", it) }
                putJsonExtras(extraOptions)
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : ResponseTool {
        override fun toJson(): JsonObject = json
    }
}

sealed interface ResponseToolChoice {
    fun toJson(): JsonElement

    data object Auto : ResponseToolChoice {
        override fun toJson(): JsonElement = JsonPrimitive("auto")
    }

    data object Required : ResponseToolChoice {
        override fun toJson(): JsonElement = JsonPrimitive("required")
    }

    data object None : ResponseToolChoice {
        override fun toJson(): JsonElement = JsonPrimitive("none")
    }

    data object Shell : ResponseToolChoice {
        override fun toJson(): JsonElement =
            buildJsonObject {
                put("type", "shell")
            }
    }

    data object ApplyPatch : ResponseToolChoice {
        override fun toJson(): JsonElement =
            buildJsonObject {
                put("type", "apply_patch")
            }
    }

    data class NamedFunction(
        val name: String,
    ) : ResponseToolChoice {
        init {
            require(name.isNotBlank()) { "named tool choice must not be blank" }
        }

        override fun toJson(): JsonElement =
            buildJsonObject {
                put("type", "function")
                put("name", name)
            }
    }

    data class Custom(
        val name: String,
    ) : ResponseToolChoice {
        init {
            require(name.isNotBlank()) { "custom tool choice name must not be blank" }
        }

        override fun toJson(): JsonElement =
            buildJsonObject {
                put("type", "custom")
                put("name", name)
            }
    }

    data class Mcp(
        val serverLabel: String,
        val name: String? = null,
    ) : ResponseToolChoice {
        init {
            require(serverLabel.isNotBlank()) { "mcp tool choice serverLabel must not be blank" }
            require(name == null || name.isNotBlank()) { "mcp tool choice name must not be blank when set" }
        }

        override fun toJson(): JsonElement =
            buildJsonObject {
                put("type", "mcp")
                put("server_label", serverLabel)
                name?.let { put("name", it) }
            }
    }

    data class Hosted(
        val type: String,
    ) : ResponseToolChoice {
        init {
            require(type.isNotBlank()) { "hosted tool choice type must not be blank" }
        }

        override fun toJson(): JsonElement =
            buildJsonObject {
                put("type", type)
            }
    }

    data class Raw(
        val json: JsonElement,
    ) : ResponseToolChoice {
        override fun toJson(): JsonElement = json
    }
}

data class ResponseReasoningConfig(
    val effort: ReasoningEffort? = null,
    val summary: ResponseReasoningSummary? = null,
    val extra: JsonExtras? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            effort?.let { put("effort", it.wireName) }
            summary?.let { put("summary", it.wireName) }
            putJsonExtras(extra)
        }
}

data class ResponseTextConfig(
    val format: ResponseTextFormat? = null,
    val verbosity: ResponseTextVerbosity? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            format?.let { put("format", it.toJson()) }
            verbosity?.let { put("verbosity", it.wireName) }
        }
}

sealed interface ResponseTextFormat {
    fun toJson(): JsonObject

    data object PlainText : ResponseTextFormat {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "text") }
    }

    data object JsonObjectFormat : ResponseTextFormat {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "json_object") }
    }

    data class JsonSchema(
        val name: String,
        val schema: kotlinx.serialization.json.JsonObject,
        val description: String? = null,
        val strict: Boolean? = null,
    ) : ResponseTextFormat {
        init {
            require(name.isNotBlank()) { "json schema format name must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "json_schema")
                put("name", name)
                put("schema", schema)
                description?.let { put("description", it) }
                strict?.let { put("strict", it) }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : ResponseTextFormat {
        override fun toJson(): JsonObject = json
    }
}

@Serializable
data class ResponsePromptReference(
    val id: String,
    val version: String? = null,
    val variables: JsonObject? = null,
) {
    init {
        require(id.isNotBlank()) { "response prompt id must not be blank" }
        require(version == null || version.isNotBlank()) { "response prompt version must not be blank when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            version?.let { put("version", it) }
            variables?.let { put("variables", it) }
        }
}

data class OpenRouterPlugin(
    val id: String,
    val config: JsonObject? = null,
) {
    init {
        require(id.isNotBlank()) { "OpenRouter plugin id must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            config?.forEach { (key, value) -> put(key, value) }
        }
}

enum class OpenRouterModality(val wireName: String) {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
}

data class OpenRouterCacheControl(
    val type: OpenRouterCacheControlType,
    val ttlSeconds: Int? = null,
) {
    init {
        require(ttlSeconds == null || ttlSeconds > 0) { "OpenRouter cache control ttlSeconds must be positive when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", type.wireName)
            ttlSeconds?.let { put("ttl", it) }
        }
}

data class OpenRouterProviderRouting(
    val order: List<String> = emptyList(),
    val allowFallbacks: Boolean? = null,
    val requireParameters: Boolean? = null,
    val dataCollection: OpenRouterDataCollection? = null,
    val only: List<String> = emptyList(),
    val ignore: List<String> = emptyList(),
    val quantizations: List<String> = emptyList(),
    val sort: OpenRouterProviderSort? = null,
    val maxPrice: JsonObject? = null,
    val extra: JsonExtras? = null,
) {
    init {
        require(order.all { it.isNotBlank() }) { "OpenRouter provider order must not contain blank values" }
        require(only.all { it.isNotBlank() }) { "OpenRouter provider only must not contain blank values" }
        require(ignore.all { it.isNotBlank() }) { "OpenRouter provider ignore must not contain blank values" }
        require(quantizations.all { it.isNotBlank() }) { "OpenRouter quantizations must not contain blank values" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            if (order.isNotEmpty()) {
                putJsonArray("order") {
                    order.forEach { add(JsonPrimitive(it)) }
                }
            }
            allowFallbacks?.let { put("allow_fallbacks", it) }
            requireParameters?.let { put("require_parameters", it) }
            dataCollection?.let { put("data_collection", it.wireName) }
            if (only.isNotEmpty()) {
                putJsonArray("only") {
                    only.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (ignore.isNotEmpty()) {
                putJsonArray("ignore") {
                    ignore.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (quantizations.isNotEmpty()) {
                putJsonArray("quantizations") {
                    quantizations.forEach { add(JsonPrimitive(it)) }
                }
            }
            sort?.let { put("sort", it.wireName) }
            maxPrice?.let { put("max_price", it) }
            putJsonExtras(extra)
        }
}

data class OpenRouterRequestOptions(
    val models: List<String> = emptyList(),
    val route: OpenRouterRoute? = null,
    val provider: JsonObject? = null,
    val providerRouting: OpenRouterProviderRouting? = null,
    val plugins: List<OpenRouterPlugin> = emptyList(),
    val transforms: List<String> = emptyList(),
    val modalities: List<OpenRouterModality> = emptyList(),
    val imageConfig: JsonObject? = null,
    val cacheControl: OpenRouterCacheControl? = null,
    val extraBody: JsonExtras? = null,
) : ResponseProviderOptions, ChatProviderOptions, EmbeddingProviderOptions {
    init {
        require(models.all { it.isNotBlank() }) { "OpenRouter models must not contain blank values" }
        require(transforms.all { it.isNotBlank() }) { "OpenRouter transforms must not contain blank values" }
    }

    override fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        if (models.isNotEmpty()) {
            builder.putJsonArray("models") {
                models.forEach { add(JsonPrimitive(it)) }
            }
        }
        route?.let { builder.put("route", it.wireName) }
        providerRouting?.let { builder.put("provider", it.toJson()) }
        provider?.let { builder.put("provider", it) }
        if (plugins.isNotEmpty()) {
            builder.putJsonArray("plugins") {
                plugins.forEach { add(it.toJson()) }
            }
        }
        if (transforms.isNotEmpty()) {
            builder.putJsonArray("transforms") {
                transforms.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (modalities.isNotEmpty()) {
            builder.putJsonArray("modalities") {
                modalities.forEach { add(JsonPrimitive(it.wireName)) }
            }
        }
        imageConfig?.let { builder.put("image_config", it) }
        cacheControl?.let { builder.put("cache_control", it.toJson()) }
        builder.putJsonExtras(extraBody)
    }

    override fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider is OpenAIProvider.OpenRouter) {
            "OpenRouter request options require OpenRouter provider"
        }
    }
}

data class OllamaRequestOptions(
    val options: JsonObject? = null,
    val keepAlive: String? = null,
    val raw: Boolean? = null,
    val extraBody: JsonExtras? = null,
) : ResponseProviderOptions, ChatProviderOptions, EmbeddingProviderOptions {
    init {
        require(keepAlive == null || keepAlive.isNotBlank()) { "Ollama keepAlive must not be blank when set" }
    }

    override fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        options?.let { builder.put("options", it) }
        keepAlive?.let { builder.put("keep_alive", it) }
        raw?.let { builder.put("raw", it) }
        builder.putJsonExtras(extraBody)
    }

    override fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider is OpenAIProvider.Ollama) {
            "Ollama request options require Ollama provider"
        }
    }
}

data class XAIRequestOptions(
    val reasoningEffort: ReasoningEffort? = null,
    val searchParameters: JsonObject? = null,
    val webSearch: JsonObject? = null,
    val imageOptions: JsonObject? = null,
    val extraBody: JsonExtras? = null,
) : ResponseProviderOptions, ChatProviderOptions {
    override fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        reasoningEffort?.let { builder.put("reasoning_effort", it.wireName) }
        searchParameters?.let { builder.put("search_parameters", it) }
        webSearch?.let { builder.put("web_search", it) }
        imageOptions?.let { builder.put("image_options", it) }
        builder.putJsonExtras(extraBody)
    }

    override fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider is OpenAIProvider.XAI) {
            "xAI request options require xAI provider"
        }
    }
}

data class ResponseCreateRequest(
    val model: ModelId,
    val input: ResponseInput,
    val instructions: String? = null,
    val previousResponseId: ResponseId? = null,
    val background: Boolean? = null,
    val conversation: JsonObject? = null,
    val include: List<ResponseInclude> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val store: Boolean? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val maxToolCalls: Int? = null,
    val parallelToolCalls: Boolean? = null,
    val tools: List<ResponseTool> = emptyList(),
    val toolChoice: ResponseToolChoice? = null,
    val prompt: ResponsePromptReference? = null,
    val serviceTier: ResponseServiceTier? = null,
    val text: ResponseTextConfig? = null,
    val reasoning: ResponseReasoningConfig? = null,
    val truncation: ResponseTruncation? = null,
    val promptCacheKey: String? = null,
    val promptCacheRetention: ResponsePromptCacheRetention? = null,
    val safetyIdentifier: String? = null,
    val user: String? = null,
    val providerOptions: ResponseProviderOptions? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be positive when set" }
        require(maxToolCalls == null || maxToolCalls > 0) { "maxToolCalls must be positive when set" }
        require(instructions == null || instructions.isNotBlank()) { "instructions must not be blank when set" }
        require(metadata.keys.all { it.isNotBlank() }) { "metadata keys must not be blank" }
        require(promptCacheKey == null || promptCacheKey.isNotBlank()) { "promptCacheKey must not be blank when set" }
        require(safetyIdentifier == null || safetyIdentifier.isNotBlank()) { "safetyIdentifier must not be blank when set" }
        require(safetyIdentifier == null || safetyIdentifier.length <= 64) {
            "safetyIdentifier must be at most 64 characters when set"
        }
        require(user == null || user.isNotBlank()) { "user must not be blank when set" }
    }

    fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider.capabilities.responsesApi) {
            "${provider.id} does not expose the Responses API in this client"
        }
        providerOptions?.requireCompatibleWith(provider)
        provider.requireStatelessResponses(
            previousResponseId = previousResponseId,
            store = store,
        )
        when (provider) {
            is OpenAIProvider.XAI -> {
                require(instructions == null) { "xAI compatibility does not support responses instructions in this client" }
            }
            else -> Unit
        }
    }

    fun toJson(stream: Boolean = false): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("input", input.toJson())
            instructions?.let { put("instructions", it) }
            previousResponseId?.let { put("previous_response_id", it.value) }
            background?.let { put("background", it) }
            conversation?.let { put("conversation", it) }
            if (include.isNotEmpty()) {
                putJsonArray("include") {
                    include.forEach { add(JsonPrimitive(it.wireName)) }
                }
            }
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            store?.let { put("store", it) }
            temperature?.let { put("temperature", it) }
            topP?.let { put("top_p", it) }
            maxOutputTokens?.let { put("max_output_tokens", it) }
            maxToolCalls?.let { put("max_tool_calls", it) }
            parallelToolCalls?.let { put("parallel_tool_calls", it) }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { add(it.toJson()) }
                }
            }
            toolChoice?.let { put("tool_choice", it.toJson()) }
            prompt?.let { put("prompt", it.toJson()) }
            serviceTier?.let { put("service_tier", it.wireName) }
            text?.let { put("text", it.toJson()) }
            reasoning?.let { put("reasoning", it.toJson()) }
            truncation?.let { put("truncation", it.wireName) }
            promptCacheKey?.let { put("prompt_cache_key", it) }
            promptCacheRetention?.let { put("prompt_cache_retention", it.wireName) }
            safetyIdentifier?.let { put("safety_identifier", it) }
            user?.let { put("user", it) }
            if (stream) put("stream", true)
            providerOptions?.applyTo(this)
            putJsonExtras(extraBody)
        }
}

data class ResponseCompactRequest(
    val model: ModelId,
    val input: ResponseInput? = null,
    val instructions: String? = null,
    val previousResponseId: ResponseId? = null,
    val promptCacheKey: String? = null,
    val providerOptions: ResponseProviderOptions? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(instructions == null || instructions.isNotBlank()) { "instructions must not be blank when set" }
        require(promptCacheKey == null || promptCacheKey.isNotBlank()) { "promptCacheKey must not be blank when set" }
    }

    fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider.capabilities.responsesApi) {
            "${provider.id} does not expose the Responses API in this client"
        }
        provider.requireStatelessResponses(
            previousResponseId = previousResponseId,
            store = null,
        )
        providerOptions?.requireCompatibleWith(provider)
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            input?.let { put("input", it.toJson()) }
            instructions?.let { put("instructions", it) }
            previousResponseId?.let { put("previous_response_id", it.value) }
            promptCacheKey?.let { put("prompt_cache_key", it) }
            providerOptions?.applyTo(this)
            putJsonExtras(extraBody)
        }
}

@Serializable
data class ResponseApiError(
    val message: String? = null,
    val type: String? = null,
    val param: String? = null,
    @Serializable(with = ResponseErrorCodeSerializer::class)
    val code: ResponseErrorCode? = null,
    val details: JsonObject? = null,
)

sealed interface ResponseErrorCode {
    val wireName: String

    data object ServerError : ResponseErrorCode { override val wireName = "server_error" }
    data object RateLimitExceeded : ResponseErrorCode { override val wireName = "rate_limit_exceeded" }
    data object InvalidPrompt : ResponseErrorCode { override val wireName = "invalid_prompt" }
    data object VectorStoreTimeout : ResponseErrorCode { override val wireName = "vector_store_timeout" }
    data object InvalidImage : ResponseErrorCode { override val wireName = "invalid_image" }
    data object InvalidImageFormat : ResponseErrorCode { override val wireName = "invalid_image_format" }
    data object InvalidBase64Image : ResponseErrorCode { override val wireName = "invalid_base64_image" }
    data object InvalidImageUrl : ResponseErrorCode { override val wireName = "invalid_image_url" }
    data object ImageTooLarge : ResponseErrorCode { override val wireName = "image_too_large" }
    data object ImageTooSmall : ResponseErrorCode { override val wireName = "image_too_small" }
    data object ImageParseError : ResponseErrorCode { override val wireName = "image_parse_error" }
    data object ImageContentPolicyViolation : ResponseErrorCode { override val wireName = "image_content_policy_violation" }
    data object InvalidImageMode : ResponseErrorCode { override val wireName = "invalid_image_mode" }
    data object ImageFileTooLarge : ResponseErrorCode { override val wireName = "image_file_too_large" }
    data object UnsupportedImageMediaType : ResponseErrorCode { override val wireName = "unsupported_image_media_type" }
    data object EmptyImageFile : ResponseErrorCode { override val wireName = "empty_image_file" }
    data object FailedToDownloadImage : ResponseErrorCode { override val wireName = "failed_to_download_image" }
    data object ImageFileNotFound : ResponseErrorCode { override val wireName = "image_file_not_found" }
    data class Unknown(override val wireName: String) : ResponseErrorCode
}

@OptIn(ExperimentalSerializationApi::class)
internal object ResponseErrorCodeSerializer : kotlinx.serialization.KSerializer<ResponseErrorCode?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("one.wabbit.web.openai.ResponseErrorCode?", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ResponseErrorCode?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.wireName)
        }
    }

    override fun deserialize(decoder: Decoder): ResponseErrorCode? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val raw = (element as? JsonPrimitive)?.contentOrNull
            return decode(raw)
        }
        return decode(decoder.decodeString())
    }

    private fun decode(raw: String?): ResponseErrorCode? =
        raw?.let {
            when (it) {
                ResponseErrorCode.ServerError.wireName -> ResponseErrorCode.ServerError
                ResponseErrorCode.RateLimitExceeded.wireName -> ResponseErrorCode.RateLimitExceeded
                ResponseErrorCode.InvalidPrompt.wireName -> ResponseErrorCode.InvalidPrompt
                ResponseErrorCode.VectorStoreTimeout.wireName -> ResponseErrorCode.VectorStoreTimeout
                ResponseErrorCode.InvalidImage.wireName -> ResponseErrorCode.InvalidImage
                ResponseErrorCode.InvalidImageFormat.wireName -> ResponseErrorCode.InvalidImageFormat
                ResponseErrorCode.InvalidBase64Image.wireName -> ResponseErrorCode.InvalidBase64Image
                ResponseErrorCode.InvalidImageUrl.wireName -> ResponseErrorCode.InvalidImageUrl
                ResponseErrorCode.ImageTooLarge.wireName -> ResponseErrorCode.ImageTooLarge
                ResponseErrorCode.ImageTooSmall.wireName -> ResponseErrorCode.ImageTooSmall
                ResponseErrorCode.ImageParseError.wireName -> ResponseErrorCode.ImageParseError
                ResponseErrorCode.ImageContentPolicyViolation.wireName -> ResponseErrorCode.ImageContentPolicyViolation
                ResponseErrorCode.InvalidImageMode.wireName -> ResponseErrorCode.InvalidImageMode
                ResponseErrorCode.ImageFileTooLarge.wireName -> ResponseErrorCode.ImageFileTooLarge
                ResponseErrorCode.UnsupportedImageMediaType.wireName -> ResponseErrorCode.UnsupportedImageMediaType
                ResponseErrorCode.EmptyImageFile.wireName -> ResponseErrorCode.EmptyImageFile
                ResponseErrorCode.FailedToDownloadImage.wireName -> ResponseErrorCode.FailedToDownloadImage
                ResponseErrorCode.ImageFileNotFound.wireName -> ResponseErrorCode.ImageFileNotFound
                else -> ResponseErrorCode.Unknown(it)
            }
        }
}

sealed interface ResponseIncompleteReason {
    val wireName: String

    data object MaxOutputTokens : ResponseIncompleteReason {
        override val wireName: String = "max_output_tokens"
    }

    data object ContentFilter : ResponseIncompleteReason {
        override val wireName: String = "content_filter"
    }

    data class Unknown(
        override val wireName: String,
    ) : ResponseIncompleteReason
}

internal object ResponseIncompleteReasonSerializer :
    PreservingWireValueSerializer<ResponseIncompleteReason>(
        serialName = "one.wabbit.web.openai.ResponseIncompleteReason?",
        knownValues =
            listOf(
                ResponseIncompleteReason.MaxOutputTokens,
                ResponseIncompleteReason.ContentFilter,
            ),
        wireName = ResponseIncompleteReason::wireName,
        unknown = ResponseIncompleteReason::Unknown,
    )

@Serializable
data class ResponseIncompleteDetails(
    @Serializable(with = ResponseIncompleteReasonSerializer::class)
    val reason: ResponseIncompleteReason? = null,
    val details: JsonObject? = null,
)

@Serializable
data class ResponseUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("input_token_details") val inputTokenDetails: JsonObject? = null,
    @SerialName("output_token_details") val outputTokenDetails: JsonObject? = null,
)

enum class ResponseInclude(val wireName: String) {
    WEB_SEARCH_CALL_ACTION_SOURCES("web_search_call.action.sources"),
    WEB_SEARCH_CALL_RESULTS("web_search_call.results"),
    CODE_INTERPRETER_CALL_OUTPUTS("code_interpreter_call.outputs"),
    COMPUTER_CALL_OUTPUT_IMAGE_URL("computer_call_output.output.image_url"),
    FILE_SEARCH_CALL_RESULTS("file_search_call.results"),
    MESSAGE_INPUT_IMAGE_URL("message.input_image.image_url"),
    MESSAGE_OUTPUT_TEXT_LOGPROBS("message.output_text.logprobs"),
    REASONING_ENCRYPTED_CONTENT("reasoning.encrypted_content"),
}

data class ResponseRetrieveQuery(
    val include: List<ResponseInclude> = emptyList(),
    val includeObfuscation: Boolean? = null,
    val startingAfter: Long? = null,
    val stream: Boolean? = null,
) {
    init {
        require(startingAfter == null || startingAfter >= 0) { "startingAfter must be non-negative when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            include.forEach { add("include" to it.wireName) }
            includeObfuscation?.let { add("include_obfuscation" to it.toString()) }
            startingAfter?.let { add("starting_after" to it.toString()) }
            stream?.let { add("stream" to it.toString()) }
        }
}

@Serializable
data class ResponseContentPart(
    val type: String,
    val text: String? = null,
    val annotations: JsonArray? = null,
    val refusal: String? = null,
    val arguments: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val summary: JsonArray? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class ResponseFunctionCall(
    val id: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String,
    val arguments: String? = null,
    val status: ResponseStatus? = null,
)

sealed interface ResponseItemType {
    val wireName: String

    data object Message : ResponseItemType {
        override val wireName: String = "message"
    }

    data object Reasoning : ResponseItemType {
        override val wireName: String = "reasoning"
    }

    data object FunctionCall : ResponseItemType {
        override val wireName: String = "function_call"
    }

    data object FunctionCallOutput : ResponseItemType {
        override val wireName: String = "function_call_output"
    }

    data object WebSearchCall : ResponseItemType {
        override val wireName: String = "web_search_call"
    }

    data object FileSearchCall : ResponseItemType {
        override val wireName: String = "file_search_call"
    }

    data object CodeInterpreterCall : ResponseItemType {
        override val wireName: String = "code_interpreter_call"
    }

    data object ComputerCall : ResponseItemType {
        override val wireName: String = "computer_call"
    }

    data object ComputerCallOutput : ResponseItemType {
        override val wireName: String = "computer_call_output"
    }

    data object CustomToolCall : ResponseItemType {
        override val wireName: String = "custom_tool_call"
    }

    data object CustomToolCallOutput : ResponseItemType {
        override val wireName: String = "custom_tool_call_output"
    }

    data object ShellCall : ResponseItemType {
        override val wireName: String = "shell_call"
    }

    data object ShellCallOutput : ResponseItemType {
        override val wireName: String = "shell_call_output"
    }

    data object LocalShellCall : ResponseItemType {
        override val wireName: String = "local_shell_call"
    }

    data object LocalShellCallOutput : ResponseItemType {
        override val wireName: String = "local_shell_call_output"
    }

    data object ImageGenerationCall : ResponseItemType {
        override val wireName: String = "image_generation_call"
    }

    data object McpCall : ResponseItemType {
        override val wireName: String = "mcp_call"
    }

    data object McpApprovalResponse : ResponseItemType {
        override val wireName: String = "mcp_approval_response"
    }

    data object XSearchCall : ResponseItemType {
        override val wireName: String = "x_search_call"
    }

    data object ItemReference : ResponseItemType {
        override val wireName: String = "item_reference"
    }

    data object Compaction : ResponseItemType {
        override val wireName: String = "compaction"
    }

    data object ApplyPatchCallOutput : ResponseItemType {
        override val wireName: String = "apply_patch_call_output"
    }

    data object ApplyPatchCall : ResponseItemType {
        override val wireName: String = "apply_patch_call"
    }

    data class Unknown(
        override val wireName: String,
    ) : ResponseItemType
}

internal object ResponseItemTypeSerializer :
    PreservingRequiredWireValueSerializer<ResponseItemType>(
        serialName = "one.wabbit.web.openai.ResponseItemType",
        knownValues =
            listOf(
                ResponseItemType.Message,
                ResponseItemType.Reasoning,
                ResponseItemType.FunctionCall,
                ResponseItemType.FunctionCallOutput,
                ResponseItemType.WebSearchCall,
                ResponseItemType.FileSearchCall,
                ResponseItemType.CodeInterpreterCall,
                ResponseItemType.ComputerCall,
                ResponseItemType.ComputerCallOutput,
                ResponseItemType.CustomToolCall,
                ResponseItemType.CustomToolCallOutput,
                ResponseItemType.ShellCall,
                ResponseItemType.ShellCallOutput,
                ResponseItemType.LocalShellCall,
                ResponseItemType.LocalShellCallOutput,
                ResponseItemType.ImageGenerationCall,
                ResponseItemType.McpCall,
                ResponseItemType.McpApprovalResponse,
                ResponseItemType.XSearchCall,
                ResponseItemType.ItemReference,
                ResponseItemType.Compaction,
                ResponseItemType.ApplyPatchCall,
                ResponseItemType.ApplyPatchCallOutput,
            ),
        wireName = ResponseItemType::wireName,
        unknown = ResponseItemType::Unknown,
    )

internal object NullableResponseItemTypeSerializer :
    PreservingWireValueSerializer<ResponseItemType>(
        serialName = "one.wabbit.web.openai.ResponseItemType?",
        knownValues =
            listOf(
                ResponseItemType.Message,
                ResponseItemType.Reasoning,
                ResponseItemType.FunctionCall,
                ResponseItemType.FunctionCallOutput,
                ResponseItemType.WebSearchCall,
                ResponseItemType.FileSearchCall,
                ResponseItemType.CodeInterpreterCall,
                ResponseItemType.ComputerCall,
                ResponseItemType.ComputerCallOutput,
                ResponseItemType.CustomToolCall,
                ResponseItemType.CustomToolCallOutput,
                ResponseItemType.ShellCall,
                ResponseItemType.ShellCallOutput,
                ResponseItemType.LocalShellCall,
                ResponseItemType.LocalShellCallOutput,
                ResponseItemType.ImageGenerationCall,
                ResponseItemType.McpCall,
                ResponseItemType.McpApprovalResponse,
                ResponseItemType.XSearchCall,
                ResponseItemType.ItemReference,
                ResponseItemType.Compaction,
                ResponseItemType.ApplyPatchCall,
                ResponseItemType.ApplyPatchCallOutput,
            ),
        wireName = ResponseItemType::wireName,
        unknown = ResponseItemType::Unknown,
    )

sealed interface ResponseStatus {
    val wireName: String

    data object Queued : ResponseStatus {
        override val wireName: String = "queued"
    }

    data object InProgress : ResponseStatus {
        override val wireName: String = "in_progress"
    }

    data object Completed : ResponseStatus {
        override val wireName: String = "completed"
    }

    data object Incomplete : ResponseStatus {
        override val wireName: String = "incomplete"
    }

    data object Failed : ResponseStatus {
        override val wireName: String = "failed"
    }

    data object Cancelled : ResponseStatus {
        override val wireName: String = "cancelled"
    }

    data object Searching : ResponseStatus {
        override val wireName: String = "searching"
    }

    data object Interpreting : ResponseStatus {
        override val wireName: String = "interpreting"
    }

    data object Calling : ResponseStatus {
        override val wireName: String = "calling"
    }

    data class Unknown(
        override val wireName: String,
    ) : ResponseStatus
}

internal object ResponseStatusSerializer :
    PreservingWireValueSerializer<ResponseStatus>(
        serialName = "one.wabbit.web.openai.ResponseStatus?",
        knownValues =
            listOf(
                ResponseStatus.Queued,
                ResponseStatus.InProgress,
                ResponseStatus.Completed,
                ResponseStatus.Incomplete,
                ResponseStatus.Failed,
                ResponseStatus.Cancelled,
                ResponseStatus.Searching,
                ResponseStatus.Interpreting,
                ResponseStatus.Calling,
            ),
        wireName = ResponseStatus::wireName,
        unknown = ResponseStatus::Unknown,
    )

@Serializable
data class ResponseOutputItem(
    val id: String? = null,
    @Serializable(with = ResponseItemTypeSerializer::class)
    val type: ResponseItemType,
    @Serializable(with = ResponseStatusSerializer::class)
    val status: ResponseStatus? = null,
    val role: String? = null,
    val content: List<ResponseContentPart> = emptyList(),
    val name: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val arguments: String? = null,
    val summary: JsonArray? = null,
    val result: String? = null,
    @SerialName("revised_prompt") val revisedPrompt: String? = null,
    val results: JsonArray? = null,
    val outputs: JsonArray? = null,
    val output: JsonElement? = null,
    @SerialName("container_id") val containerId: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("server_label") val serverLabel: String? = null,
    @SerialName("server_url") val serverUrl: String? = null,
    val action: JsonObject? = null,
    val environment: JsonObject? = null,
    val operation: JsonObject? = null,
    val input: String? = null,
    @SerialName("approval_request_id") val approvalRequestId: String? = null,
    @SerialName("encrypted_content") val encryptedContent: String? = null,
    val queries: List<String> = emptyList(),
    val code: String? = null,
    @SerialName("pending_safety_checks") val pendingSafetyChecks: JsonArray? = null,
    @SerialName("acknowledged_safety_checks") val acknowledgedSafetyChecks: JsonArray? = null,
    @SerialName("max_output_length") val maxOutputLength: Int? = null,
    @SerialName("created_by") val createdBy: String? = null,
) {
    fun asFunctionCallOrNull(): ResponseFunctionCall? =
        if (type == ResponseItemType.FunctionCall && !name.isNullOrBlank()) {
            ResponseFunctionCall(
                id = id,
                callId = callId,
                name = name,
                arguments = arguments,
                status = status,
            )
        } else {
            null
        }

    fun textContent(): String =
        content
            .mapNotNull { part ->
                when (part.type) {
                    "output_text", "text" -> part.text
                    else -> null
                }
            }
            .joinToString(separator = "")

    fun asHostedToolCallOrNull(): ResponseHostedToolCall? =
        when (type) {
            ResponseItemType.WebSearchCall,
            ResponseItemType.FileSearchCall,
            ResponseItemType.CodeInterpreterCall,
            ResponseItemType.ComputerCall,
            ResponseItemType.XSearchCall,
            ResponseItemType.ImageGenerationCall,
            -> ResponseHostedToolCall(id = id, type = type, status = status, results = results, outputs = outputs)
            else -> null
        }

    fun asImageGenerationCallOrNull(): ResponseImageGenerationCall? =
        if (type == ResponseItemType.ImageGenerationCall) {
            ResponseImageGenerationCall(
                id = id,
                status = status,
                result = result,
                revisedPrompt = revisedPrompt,
            )
        } else {
            null
        }

    fun asMcpCallOrNull(): ResponseMcpCall? =
        if (type == ResponseItemType.McpCall) {
            ResponseMcpCall(
                id = id,
                status = status,
                name = name,
                arguments = arguments,
                output = result,
                serverLabel = serverLabel,
                serverUrl = serverUrl,
                approvalRequestId = approvalRequestId,
            )
        } else {
            null
        }

    fun asWebSearchCallOrNull(): ResponseWebSearchCall? =
        if (type == ResponseItemType.WebSearchCall) {
            ResponseWebSearchCall(
                id = id,
                status = status,
                action =
                    action?.let {
                        OpenAIJson.decodeFromJsonElement<ResponseWebSearchAction>(it)
                    },
            )
        } else {
            null
        }

    fun asFileSearchCallOrNull(): ResponseFileSearchCall? =
        if (type == ResponseItemType.FileSearchCall) {
            ResponseFileSearchCall(
                id = id,
                status = status,
                queries = queries,
                results =
                    results
                        ?.map { OpenAIJson.decodeFromJsonElement<ResponseFileSearchResult>(it) }
                        .orEmpty(),
            )
        } else {
            null
        }

    fun asCodeInterpreterCallOrNull(): ResponseCodeInterpreterCall? =
        if (type == ResponseItemType.CodeInterpreterCall) {
            ResponseCodeInterpreterCall(
                id = id,
                status = status,
                code = code,
                containerId = containerId,
                outputs =
                    outputs
                        ?.map { OpenAIJson.decodeFromJsonElement<ResponseCodeInterpreterOutput>(it) }
                        .orEmpty(),
            )
        } else {
            null
        }

    fun asComputerCallOrNull(): ResponseComputerCall? =
        if (type == ResponseItemType.ComputerCall) {
            ResponseComputerCall(
                id = id,
                status = status,
                action = action,
                callId = callId,
                pendingSafetyChecks =
                    pendingSafetyChecks
                        ?.map { OpenAIJson.decodeFromJsonElement<ResponseSafetyCheck>(it) }
                        .orEmpty(),
            )
        } else {
            null
        }

    fun asComputerCallOutputOrNull(): ResponseComputerCallOutput? =
        if (type == ResponseItemType.ComputerCallOutput) {
            ResponseComputerCallOutput(
                id = id,
                status = status,
                callId = callId,
                output =
                    output
                        ?.let { OpenAIJson.decodeFromJsonElement<ResponseComputerCallOutputPayload>(it) },
                acknowledgedSafetyChecks =
                    acknowledgedSafetyChecks
                        ?.map { OpenAIJson.decodeFromJsonElement<ResponseSafetyCheck>(it) }
                        .orEmpty(),
            )
        } else {
            null
        }

    fun asCustomToolCallOrNull(): ResponseCustomToolCall? =
        if (type == ResponseItemType.CustomToolCall) {
            ResponseCustomToolCall(
                id = id,
                callId = callId,
                name = name,
                input = input,
            )
        } else {
            null
        }

    fun asCustomToolCallOutputOrNull(): ResponseCustomToolCallOutput? =
        if (type == ResponseItemType.CustomToolCallOutput) {
            ResponseCustomToolCallOutput(
                id = id,
                callId = callId,
                outputText = (output as? JsonPrimitive)?.contentOrNull,
                outputContent =
                    (output as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        .orEmpty(),
            )
        } else {
            null
        }

    fun asShellCallOrNull(): ResponseShellCall? =
        if (type == ResponseItemType.ShellCall) {
            ResponseShellCall(
                id = id,
                status = status,
                callId = callId,
                action = action,
                environment = environment,
            )
        } else {
            null
        }

    fun asShellCallOutputOrNull(): ResponseShellCallOutput? =
        if (type == ResponseItemType.ShellCallOutput) {
            ResponseShellCallOutput(
                id = id,
                status = status,
                callId = callId,
                maxOutputLength = maxOutputLength,
                createdBy = createdBy,
                output =
                    (output as? JsonArray)
                        ?.map { OpenAIJson.decodeFromJsonElement<ResponseShellCallChunk>(it) }
                        .orEmpty(),
            )
        } else {
            null
        }

    fun asLocalShellCallOrNull(): ResponseLocalShellCall? =
        if (type == ResponseItemType.LocalShellCall) {
            ResponseLocalShellCall(
                id = id,
                status = status,
                callId = callId,
                action = action,
                environment = environment,
            )
        } else {
            null
        }

    fun asLocalShellCallOutputOrNull(): ResponseLocalShellCallOutput? =
        if (type == ResponseItemType.LocalShellCallOutput) {
            ResponseLocalShellCallOutput(
                id = id,
                status = status,
                output = (output as? JsonPrimitive)?.contentOrNull,
            )
        } else {
            null
        }

    fun asApplyPatchCallOrNull(): ResponseApplyPatchCall? =
        if (type == ResponseItemType.ApplyPatchCall) {
            ResponseApplyPatchCall(
                id = id,
                status = status,
                callId = callId,
                operation = operation,
            )
        } else {
            null
        }

    fun asApplyPatchCallOutputOrNull(): ResponseApplyPatchCallOutput? =
        if (type == ResponseItemType.ApplyPatchCallOutput) {
            ResponseApplyPatchCallOutput(
                id = id,
                status = status,
                callId = callId,
                output = (output as? JsonPrimitive)?.contentOrNull,
            )
        } else {
            null
        }

    fun asCompactionItemOrNull(): ResponseCompactionItem? =
        if (type == ResponseItemType.Compaction && !encryptedContent.isNullOrBlank()) {
            ResponseCompactionItem(
                id = id,
                encryptedContent = encryptedContent,
                createdBy = createdBy,
            )
        } else {
            null
        }

    fun toInputReferenceOrNull(): ResponseInputItem.ItemReference? =
        id?.takeIf { it.isNotBlank() }?.let(ResponseInputItem::ItemReference)
}

@Serializable
data class ResponseWebSearchSource(
    val type: String,
    val url: String,
    val title: String? = null,
)

@Serializable
data class ResponseWebSearchAction(
    val type: String,
    val query: String? = null,
    val queries: List<String> = emptyList(),
    val url: String? = null,
    val sources: List<ResponseWebSearchSource> = emptyList(),
)

data class ResponseWebSearchCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val action: ResponseWebSearchAction? = null,
)

@Serializable
data class ResponseFileSearchResult(
    val attributes: JsonObject? = null,
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val score: Double? = null,
    val text: String? = null,
)

data class ResponseFileSearchCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val queries: List<String> = emptyList(),
    val results: List<ResponseFileSearchResult> = emptyList(),
)

@Serializable
data class ResponseCodeInterpreterOutput(
    val type: String,
    val logs: String? = null,
    val url: String? = null,
    @SerialName("file_id") val fileId: String? = null,
)

data class ResponseCodeInterpreterCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val code: String? = null,
    val containerId: String? = null,
    val outputs: List<ResponseCodeInterpreterOutput> = emptyList(),
)

@Serializable
data class ResponseSafetyCheck(
    val id: String,
    val code: String? = null,
    val message: String? = null,
)

data class ResponseComputerCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val action: JsonObject? = null,
    val callId: String? = null,
    val pendingSafetyChecks: List<ResponseSafetyCheck> = emptyList(),
)

@Serializable
data class ResponseComputerCallOutputPayload(
    val type: String,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

data class ResponseComputerCallOutput(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val callId: String? = null,
    val output: ResponseComputerCallOutputPayload? = null,
    val acknowledgedSafetyChecks: List<ResponseSafetyCheck> = emptyList(),
)

data class ResponseCustomToolCall(
    val id: String? = null,
    val callId: String? = null,
    val name: String? = null,
    val input: String? = null,
)

data class ResponseCustomToolCallOutput(
    val id: String? = null,
    val callId: String? = null,
    val outputText: String? = null,
    val outputContent: List<JsonObject> = emptyList(),
)

@Serializable
data class ResponseShellCallOutcome(
    val type: String,
    @SerialName("exit_code") val exitCode: Int? = null,
)

@Serializable
data class ResponseShellCallChunk(
    val outcome: ResponseShellCallOutcome? = null,
    val stderr: String? = null,
    val stdout: String? = null,
)

data class ResponseShellCallOutput(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val callId: String? = null,
    val maxOutputLength: Int? = null,
    val createdBy: String? = null,
    val output: List<ResponseShellCallChunk> = emptyList(),
)

data class ResponseShellCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val callId: String? = null,
    val action: JsonObject? = null,
    val environment: JsonObject? = null,
)

data class ResponseLocalShellCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val callId: String? = null,
    val action: JsonObject? = null,
    val environment: JsonObject? = null,
)

data class ResponseLocalShellCallOutput(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val output: String? = null,
)

data class ResponseApplyPatchCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val callId: String? = null,
    val operation: JsonObject? = null,
)

data class ResponseApplyPatchCallOutput(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val callId: String? = null,
    val output: String? = null,
)

data class ResponseHostedToolCall(
    val id: String? = null,
    val type: ResponseItemType,
    val status: ResponseStatus? = null,
    val results: JsonArray? = null,
    val outputs: JsonArray? = null,
)

data class ResponseImageGenerationCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val result: String? = null,
    val revisedPrompt: String? = null,
)

data class ResponseMcpCall(
    val id: String? = null,
    val status: ResponseStatus? = null,
    val name: String? = null,
    val arguments: String? = null,
    val output: String? = null,
    val serverLabel: String? = null,
    val serverUrl: String? = null,
    val approvalRequestId: String? = null,
)

data class ResponseCompactionItem(
    val id: String? = null,
    val encryptedContent: String,
    val createdBy: String? = null,
)

@Serializable
data class ResponseObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @Serializable(with = ResponseStatusSerializer::class)
    val status: ResponseStatus? = null,
    val error: ResponseApiError? = null,
    @SerialName("incomplete_details") val incompleteDetails: ResponseIncompleteDetails? = null,
    val instructions: String? = null,
    val background: Boolean? = null,
    val conversation: JsonObject? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("max_tool_calls") val maxToolCalls: Int? = null,
    val model: String? = null,
    val output: List<ResponseOutputItem> = emptyList(),
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null,
    val prompt: ResponsePromptReference? = null,
    val reasoning: JsonObject? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val store: Boolean? = null,
    val temperature: Double? = null,
    val text: JsonObject? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val tools: JsonArray? = null,
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    @SerialName("prompt_cache_retention") val promptCacheRetention: String? = null,
    @SerialName("safety_identifier") val safetyIdentifier: String? = null,
    @SerialName("top_p") val topP: Double? = null,
    val truncation: String? = null,
    val usage: ResponseUsage? = null,
    val user: String? = null,
    val metadata: JsonObject? = null,
) {
    fun outputMessages(): List<ResponseOutputItem> = output.filter { it.type == ResponseItemType.Message }

    fun functionCalls(): List<ResponseFunctionCall> = output.mapNotNull { it.asFunctionCallOrNull() }

    fun hostedToolCalls(): List<ResponseHostedToolCall> = output.mapNotNull { it.asHostedToolCallOrNull() }

    fun imageGenerationCalls(): List<ResponseImageGenerationCall> = output.mapNotNull { it.asImageGenerationCallOrNull() }

    fun mcpCalls(): List<ResponseMcpCall> = output.mapNotNull { it.asMcpCallOrNull() }

    fun webSearchCalls(): List<ResponseWebSearchCall> = output.mapNotNull { it.asWebSearchCallOrNull() }

    fun fileSearchCalls(): List<ResponseFileSearchCall> = output.mapNotNull { it.asFileSearchCallOrNull() }

    fun codeInterpreterCalls(): List<ResponseCodeInterpreterCall> = output.mapNotNull { it.asCodeInterpreterCallOrNull() }

    fun computerCalls(): List<ResponseComputerCall> = output.mapNotNull { it.asComputerCallOrNull() }

    fun computerCallOutputs(): List<ResponseComputerCallOutput> = output.mapNotNull { it.asComputerCallOutputOrNull() }

    fun customToolCalls(): List<ResponseCustomToolCall> = output.mapNotNull { it.asCustomToolCallOrNull() }

    fun customToolCallOutputs(): List<ResponseCustomToolCallOutput> = output.mapNotNull { it.asCustomToolCallOutputOrNull() }

    fun shellCalls(): List<ResponseShellCall> = output.mapNotNull { it.asShellCallOrNull() }

    fun shellCallOutputs(): List<ResponseShellCallOutput> = output.mapNotNull { it.asShellCallOutputOrNull() }

    fun localShellCalls(): List<ResponseLocalShellCall> = output.mapNotNull { it.asLocalShellCallOrNull() }

    fun localShellCallOutputs(): List<ResponseLocalShellCallOutput> = output.mapNotNull { it.asLocalShellCallOutputOrNull() }

    fun applyPatchCalls(): List<ResponseApplyPatchCall> = output.mapNotNull { it.asApplyPatchCallOrNull() }

    fun applyPatchCallOutputs(): List<ResponseApplyPatchCallOutput> = output.mapNotNull { it.asApplyPatchCallOutputOrNull() }

    fun compactionItems(): List<ResponseCompactionItem> = output.mapNotNull { it.asCompactionItemOrNull() }

    fun incompleteReasonOrNull(): ResponseIncompleteReason? = incompleteDetails?.reason

    fun outputText(): String =
        outputMessages().joinToString(separator = "") { it.textContent() }

    fun outputJsonElementOrNull(): JsonElement? =
        outputText()
            .takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { OpenAIJson.parseToJsonElement(text) }.getOrNull() }

    inline fun <reified T> decodeOutputJson(): T =
        OpenAIJson.decodeFromJsonElement(outputJsonElementOrNull() ?: error("Response output is not valid JSON"))
}

enum class ResponseListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

data class ResponseListQuery(
    val model: ModelId? = null,
    val limit: Int = 20,
    val order: ResponseListOrder = ResponseListOrder.DESC,
    val after: String? = null,
    val before: String? = null,
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(!(after != null && before != null)) { "after and before cannot both be set" }
        require(after == null || after.isNotBlank()) { "after must not be blank when set" }
        require(before == null || before.isNotBlank()) { "before must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            model?.let { add("model" to it.value) }
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            before?.let { add("before" to it) }
        }
}

@Serializable
data class ResponsePage(
    @SerialName("object") val objectType: String? = null,
    val data: List<ResponseObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

enum class ResponseInputItemListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

data class ResponseInputItemListQuery(
    val limit: Int = 20,
    val order: ResponseInputItemListOrder = ResponseInputItemListOrder.DESC,
    val after: String? = null,
    val before: String? = null,
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(!(after != null && before != null)) { "after and before cannot both be set" }
        require(after == null || after.isNotBlank()) { "after must not be blank when set" }
        require(before == null || before.isNotBlank()) { "before must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            before?.let { add("before" to it) }
        }
}

@Serializable
data class ResponseInputItemObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    @Serializable(with = NullableResponseItemTypeSerializer::class)
    val type: ResponseItemType? = null,
    val role: String? = null,
    @Serializable(with = ResponseStatusSerializer::class)
    val status: ResponseStatus? = null,
    val content: JsonElement? = null,
    @SerialName("call_id") val callId: String? = null,
    val arguments: String? = null,
    val output: String? = null,
    val name: String? = null,
    val summary: JsonArray? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class ResponseInputItemPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<ResponseInputItemObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)
