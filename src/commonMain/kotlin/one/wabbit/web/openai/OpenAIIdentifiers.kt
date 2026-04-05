package one.wabbit.web.openai

import kotlin.jvm.JvmInline

private fun requireIdentifier(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
}

@JvmInline
value class ResponseId(val value: String) {
    init {
        requireIdentifier(value, "response id")
    }
}

@JvmInline
value class ModelId(val value: String) {
    init {
        requireIdentifier(value, "model id")
    }
}

@JvmInline
value class ChatCompletionId(val value: String) {
    init {
        requireIdentifier(value, "chat completion id")
    }
}

@JvmInline
value class FileId(val value: String) {
    init {
        requireIdentifier(value, "file id")
    }
}

@JvmInline
value class VectorStoreId(val value: String) {
    init {
        requireIdentifier(value, "vector store id")
    }
}

@JvmInline
value class VectorStoreFileBatchId(val value: String) {
    init {
        requireIdentifier(value, "vector store file batch id")
    }
}

@JvmInline
value class BatchId(val value: String) {
    init {
        requireIdentifier(value, "batch id")
    }
}

@JvmInline
value class FineTuningJobId(val value: String) {
    init {
        requireIdentifier(value, "fine-tuning job id")
    }
}

@JvmInline
value class FineTunedModelCheckpointId(val value: String) {
    init {
        requireIdentifier(value, "fine-tuned model checkpoint id")
    }
}

@JvmInline
value class FineTuningCheckpointPermissionId(val value: String) {
    init {
        requireIdentifier(value, "fine-tuning checkpoint permission id")
    }
}

@JvmInline
value class UploadId(val value: String) {
    init {
        requireIdentifier(value, "upload id")
    }
}

@JvmInline
value class UploadPartId(val value: String) {
    init {
        requireIdentifier(value, "upload part id")
    }
}

@JvmInline
value class EvalId(val value: String) {
    init {
        requireIdentifier(value, "eval id")
    }
}

@JvmInline
value class EvalRunId(val value: String) {
    init {
        requireIdentifier(value, "eval run id")
    }
}

@JvmInline
value class EvalRunOutputItemId(val value: String) {
    init {
        requireIdentifier(value, "eval run output item id")
    }
}

@JvmInline
value class VideoId(val value: String) {
    init {
        requireIdentifier(value, "video id")
    }
}

@JvmInline
value class VideoCharacterId(val value: String) {
    init {
        requireIdentifier(value, "video character id")
    }
}

@JvmInline
value class XAIBatchId(val value: String) {
    init {
        requireIdentifier(value, "xAI batch id")
    }
}

@JvmInline
value class XAIVoiceId(val value: String) {
    init {
        requireIdentifier(value, "xAI voice id")
    }
}

@JvmInline
value class ConversationId(val value: String) {
    init {
        requireIdentifier(value, "conversation id")
    }
}

@JvmInline
value class ConversationItemId(val value: String) {
    init {
        requireIdentifier(value, "conversation item id")
    }
}

@JvmInline
value class RealtimeCallId(val value: String) {
    init {
        requireIdentifier(value, "realtime call id")
    }
}

@JvmInline
value class ToolCallId(val value: String) {
    init {
        requireIdentifier(value, "tool call id")
    }
}

@JvmInline
value class McpApprovalRequestId(val value: String) {
    init {
        requireIdentifier(value, "mcp approval request id")
    }
}
