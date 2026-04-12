# Workflows And Operations

This page covers the broader workflow-oriented surfaces: conversations, Realtime bootstrap and call control, evals, batches, fine-tuning, models, embeddings, moderations, and webhooks.

## Conversations

Conversation methods:

- [createConversation][one.wabbit.web.openai.OpenAIApi.createConversation]
- [updateConversation][one.wabbit.web.openai.OpenAIApi.updateConversation]
- [getConversation][one.wabbit.web.openai.OpenAIApi.getConversation]
- [createConversationItems][one.wabbit.web.openai.OpenAIApi.createConversationItems]
- [listConversationItems][one.wabbit.web.openai.OpenAIApi.listConversationItems]
- [getConversationItem][one.wabbit.web.openai.OpenAIApi.getConversationItem]
- [deleteConversation][one.wabbit.web.openai.OpenAIApi.deleteConversation]
- [deleteConversationItem][one.wabbit.web.openai.OpenAIApi.deleteConversationItem]

These methods expose the newer conversation lifecycle without routing callers back through legacy Assistants-style APIs.

## Realtime

Realtime REST-facing methods:

- [createRealtimeSession][one.wabbit.web.openai.OpenAIApi.createRealtimeSession]
- [createRealtimeTranscriptionSession][one.wabbit.web.openai.OpenAIApi.createRealtimeTranscriptionSession]
- [createRealtimeClientSecret][one.wabbit.web.openai.OpenAIApi.createRealtimeClientSecret]
- [acceptRealtimeCall][one.wabbit.web.openai.OpenAIApi.acceptRealtimeCall]
- [hangupRealtimeCall][one.wabbit.web.openai.OpenAIApi.hangupRealtimeCall]
- [referRealtimeCall][one.wabbit.web.openai.OpenAIApi.referRealtimeCall]
- [rejectRealtimeCall][one.wabbit.web.openai.OpenAIApi.rejectRealtimeCall]

Main typed config surfaces include:

- [RealtimeSessionConfig][one.wabbit.web.openai.RealtimeSessionConfig]
- [RealtimeTranscriptionSessionConfig][one.wabbit.web.openai.RealtimeTranscriptionSessionConfig]
- [RealtimeCallAcceptRequest][one.wabbit.web.openai.RealtimeCallAcceptRequest]

This module focuses on Realtime bootstrap and call control, not on wrapping websocket session transport into a higher-level client abstraction.

## Evals

Eval methods:

- [createEval][one.wabbit.web.openai.OpenAIApi.createEval]
- [getEval][one.wabbit.web.openai.OpenAIApi.getEval]
- [listEvals][one.wabbit.web.openai.OpenAIApi.listEvals]
- [updateEval][one.wabbit.web.openai.OpenAIApi.updateEval]
- [deleteEval][one.wabbit.web.openai.OpenAIApi.deleteEval]
- [createEvalRun][one.wabbit.web.openai.OpenAIApi.createEvalRun]
- [getEvalRun][one.wabbit.web.openai.OpenAIApi.getEvalRun]
- [listEvalRuns][one.wabbit.web.openai.OpenAIApi.listEvalRuns]
- [updateEvalRun][one.wabbit.web.openai.OpenAIApi.updateEvalRun]
- [deleteEvalRun][one.wabbit.web.openai.OpenAIApi.deleteEvalRun]
- [cancelEvalRun][one.wabbit.web.openai.OpenAIApi.cancelEvalRun]
- [getEvalRunOutputItem][one.wabbit.web.openai.OpenAIApi.getEvalRunOutputItem]
- [listEvalRunOutputItems][one.wabbit.web.openai.OpenAIApi.listEvalRunOutputItems]

The eval request and response surface was rebuilt around the current documented schema, including:

- structured `dataSourceConfig`
- structured `testingCriteria`
- typed run `dataSource`
- typed result counts and output-item sample/results data

## Batches

Batch methods:

- [createBatch][one.wabbit.web.openai.OpenAIApi.createBatch]
- [getBatch][one.wabbit.web.openai.OpenAIApi.getBatch]
- [listBatches][one.wabbit.web.openai.OpenAIApi.listBatches]
- [cancelBatch][one.wabbit.web.openai.OpenAIApi.cancelBatch]

These are the general-purpose OpenAI batch APIs, not the xAI-specific batch surface.

## Fine-Tuning

Fine-tuning methods include:

- job create/get/list/cancel/pause/resume
- event listing
- checkpoint listing
- checkpoint permission create/list/delete
- grader run and grader validate

Main entry types include:

- [FineTuningJobCreateRequest][one.wabbit.web.openai.FineTuningJobCreateRequest]
- [FineTuningJob][one.wabbit.web.openai.FineTuningJob]
- [FineTuningCheckpointPermissionCreateRequest][one.wabbit.web.openai.FineTuningCheckpointPermissionCreateRequest]
- [FineTuningGraderRunRequest][one.wabbit.web.openai.FineTuningGraderRunRequest]
- [FineTuningGraderValidateRequest][one.wabbit.web.openai.FineTuningGraderValidateRequest]

## Models, Embeddings, And Moderations

Smaller but important utility surfaces:

- [createEmbedding][one.wabbit.web.openai.OpenAIApi.createEmbedding]
- [listModels][one.wabbit.web.openai.OpenAIApi.listModels]
- [getModel][one.wabbit.web.openai.OpenAIApi.getModel]
- [deleteModel][one.wabbit.web.openai.OpenAIApi.deleteModel]
- [createModeration][one.wabbit.web.openai.OpenAIApi.createModeration]

These methods are intentionally straightforward and mostly use typed request/response objects with fewer lifecycle concerns than the larger surfaces.

## Webhooks

Webhook helpers are not methods on `OpenAIApi`, but they are part of the public module surface:

- [decodeOpenAIWebhookEvent][one.wabbit.web.openai.decodeOpenAIWebhookEvent]
- [decodeOpenAIWebhookEventOrNull][one.wabbit.web.openai.decodeOpenAIWebhookEventOrNull]
- [OpenAIWebhookVerifier][one.wabbit.web.openai.OpenAIWebhookVerifier]

The verifier implements the Standard Webhooks-style OpenAI header contract:

- `webhook-id`
- `webhook-timestamp`
- `webhook-signature`

It validates timestamp tolerance and HMAC signature before decoding the event payload.
