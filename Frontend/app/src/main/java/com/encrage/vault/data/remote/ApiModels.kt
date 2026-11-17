package com.encrage.vault.data.remote

import com.google.gson.annotations.SerializedName

data class LoginRequest(val username: String, val password: String)
data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)
data class LogoutRequest(@SerializedName("refresh_token") val refreshToken: String)
data class ChangePasswordRequest(
    @SerializedName("current_password") val currentPassword: String,
    @SerializedName("new_password") val newPassword: String,
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("expires_in") val expiresInSeconds: Long,
)

data class MediaDto(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("media_fingerprint") val fingerprint: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("media_type") val mediaType: String,
    @SerializedName("datetime_taken") val datetimeTaken: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerializedName("duration_seconds") val durationSeconds: Double? = null,
    @SerializedName("plaintext_size_bytes") val sizeBytes: Long,
)

data class MediaPageDto(
    val items: List<MediaDto>,
    @SerializedName("next_cursor") val nextCursor: String?,
)

data class StorageDto(
    @SerializedName("space_used_bytes") val usedBytes: Long,
    @SerializedName("space_allocated_bytes") val allocatedBytes: Long,
    @SerializedName("space_available_bytes") val availableBytes: Long = 0,
)

data class FingerprintCheckRequest(
    @SerializedName("media_fingerprints") val mediaFingerprints: List<String>,
)

data class FingerprintMatchDto(
    @SerializedName("media_fingerprint") val mediaFingerprint: String,
    @SerializedName("media_id") val mediaId: String,
)

data class FingerprintCheckResponse(
    val matches: List<FingerprintMatchDto>,
)

data class UploadCreateRequest(
    @SerializedName("media_fingerprint") val mediaFingerprint: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("media_type") val mediaType: String,
    @SerializedName("plaintext_size_bytes") val plaintextSizeBytes: Long,
    @SerializedName("datetime_taken") val datetimeTaken: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerializedName("duration_seconds") val durationSeconds: Double? = null,
    @SerializedName("private_metadata_ciphertext") val privateMetadataCiphertext: String? = null,
    @SerializedName("encryption_version") val encryptionVersion: Int = 1,
    @SerializedName("total_chunks") val totalChunks: Int,
)

data class UploadCreateResponse(
    @SerializedName("upload_id") val uploadId: String?,
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("already_exists") val alreadyExists: Boolean,
    @SerializedName("upload_in_progress") val uploadInProgress: Boolean = false,
)

data class UploadStatusResponse(
    @SerializedName("upload_id") val uploadId: String,
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("total_chunks") val totalChunks: Int,
    @SerializedName("uploaded_chunks") val uploadedChunks: List<Int>,
    @SerializedName("thumbnail_uploaded") val thumbnailUploaded: Boolean,
    @SerializedName("stored_size_bytes") val storedSizeBytes: Long,
    val state: String,
)

data class ChunkUploadResponse(
    @SerializedName("chunk_index") val chunkIndex: Int,
    @SerializedName("stored_size_bytes") val storedSizeBytes: Long,
    @SerializedName("ciphertext_sha256") val ciphertextSha256: String,
    val idempotent: Boolean,
)

data class ThumbnailUploadResponse(
    @SerializedName("stored_size_bytes") val storedSizeBytes: Long,
    @SerializedName("ciphertext_sha256") val ciphertextSha256: String,
    val idempotent: Boolean,
)

data class MediaResponseDto(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("media_fingerprint") val fingerprint: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("media_type") val mediaType: String,
    @SerializedName("plaintext_size_bytes") val plaintextSizeBytes: Long,
    @SerializedName("datetime_taken") val datetimeTaken: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerializedName("duration_seconds") val durationSeconds: Double? = null,
    @SerializedName("total_chunks") val totalChunks: Int,
)
