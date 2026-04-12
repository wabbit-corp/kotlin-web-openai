# Files, Uploads, And Vector Stores

This page covers the storage-oriented surfaces in `kotlin-web-openai`.

## Files

File methods:

- [uploadFile][one.wabbit.web.openai.OpenAIApi.uploadFile]
- [getFile][one.wabbit.web.openai.OpenAIApi.getFile]
- [listFiles][one.wabbit.web.openai.OpenAIApi.listFiles]
- [deleteFile][one.wabbit.web.openai.OpenAIApi.deleteFile]
- [downloadFile][one.wabbit.web.openai.OpenAIApi.downloadFile]
- [downloadFileTo][one.wabbit.web.openai.OpenAIApi.downloadFileTo]

File creation supports both eager and streaming request types:

- [FileCreateRequest][one.wabbit.web.openai.FileCreateRequest]
- [StreamingFileCreateRequest][one.wabbit.web.openai.StreamingFileCreateRequest]

## Uploads

Multipart uploads are modeled separately from ordinary file create:

- [createUpload][one.wabbit.web.openai.OpenAIApi.createUpload]
- [addUploadPart][one.wabbit.web.openai.OpenAIApi.addUploadPart]
- [completeUpload][one.wabbit.web.openai.OpenAIApi.completeUpload]
- [cancelUpload][one.wabbit.web.openai.OpenAIApi.cancelUpload]

Use:

- [BinaryUpload][one.wabbit.web.openai.BinaryUpload] for eager parts
- [StreamingBinaryUpload][one.wabbit.web.openai.StreamingBinaryUpload] for channel-backed parts

Multipart filenames are sanitized defensively before being written into `Content-Disposition`.

## Vector Stores

Vector store methods:

- [createVectorStore][one.wabbit.web.openai.OpenAIApi.createVectorStore]
- [getVectorStore][one.wabbit.web.openai.OpenAIApi.getVectorStore]
- [listVectorStores][one.wabbit.web.openai.OpenAIApi.listVectorStores]
- [updateVectorStore][one.wabbit.web.openai.OpenAIApi.updateVectorStore]
- [deleteVectorStore][one.wabbit.web.openai.OpenAIApi.deleteVectorStore]
- [searchVectorStore][one.wabbit.web.openai.OpenAIApi.searchVectorStore]

Request types include:

- [VectorStoreCreateRequest][one.wabbit.web.openai.VectorStoreCreateRequest]
- [VectorStoreUpdateRequest][one.wabbit.web.openai.VectorStoreUpdateRequest]
- [VectorStoreSearchRequest][one.wabbit.web.openai.VectorStoreSearchRequest]

## Vector Store Files

Vector store file methods:

- [createVectorStoreFile][one.wabbit.web.openai.OpenAIApi.createVectorStoreFile]
- [getVectorStoreFile][one.wabbit.web.openai.OpenAIApi.getVectorStoreFile]
- [getVectorStoreFileContent][one.wabbit.web.openai.OpenAIApi.getVectorStoreFileContent]
- [listVectorStoreFiles][one.wabbit.web.openai.OpenAIApi.listVectorStoreFiles]
- [updateVectorStoreFile][one.wabbit.web.openai.OpenAIApi.updateVectorStoreFile]
- [deleteVectorStoreFile][one.wabbit.web.openai.OpenAIApi.deleteVectorStoreFile]

Attributes are modeled through [VectorStoreAttributes][one.wabbit.web.openai.VectorStoreAttributes], which now supports string, number, and boolean values instead of a string-only map.

## Vector Store File Batches

Batch methods:

- [createVectorStoreFileBatch][one.wabbit.web.openai.OpenAIApi.createVectorStoreFileBatch]
- [getVectorStoreFileBatch][one.wabbit.web.openai.OpenAIApi.getVectorStoreFileBatch]
- [cancelVectorStoreFileBatch][one.wabbit.web.openai.OpenAIApi.cancelVectorStoreFileBatch]
- [listVectorStoreFileBatches][one.wabbit.web.openai.OpenAIApi.listVectorStoreFileBatches]
- [listVectorStoreFileBatchFiles][one.wabbit.web.openai.OpenAIApi.listVectorStoreFileBatchFiles]

[VectorStoreFileBatchCreateRequest][one.wabbit.web.openai.VectorStoreFileBatchCreateRequest] supports both:

- simple top-level `fileIds`
- per-file batch items with attributes and chunking strategy

## Practical Rules

- Prefer `downloadFileTo(...)` when file size is unknown or potentially large.
- Use `VectorStoreAttributes` instead of dropping to raw JSON for ordinary string/number/boolean metadata.
- Use streaming uploads for large file or upload-part payloads.
