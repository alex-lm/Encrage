package com.encrage.vault.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<TokenResponse>

    @POST("auth/logout")
    suspend fun logout(@Body request: LogoutRequest): Response<Unit>
}

interface EncrageApi {
    @POST("auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<Unit>

    @GET("media")
    suspend fun media(@Query("cursor") cursor: String?, @Query("limit") limit: Int = 100): MediaPageDto

    @GET("media/{media_id}/thumbnail")
    suspend fun thumbnail(@Path("media_id") mediaId: String): ResponseBody

    @GET("media/{media_id}/chunks/{chunk_index}")
    suspend fun chunk(@Path("media_id") mediaId: String, @Path("chunk_index") chunkIndex: Int): ResponseBody

    @DELETE("media/{media_id}")
    suspend fun deleteMedia(@Path("media_id") mediaId: String): Response<Unit>

    @GET("users/me/storage")
    suspend fun storage(): StorageDto

    @POST("media/fingerprints/check")
    suspend fun checkFingerprints(@Body request: FingerprintCheckRequest): FingerprintCheckResponse

    @POST("media/uploads")
    suspend fun createUpload(@Body request: UploadCreateRequest): UploadCreateResponse

    @GET("media/uploads/{upload_id}")
    suspend fun uploadStatus(@Path("upload_id") uploadId: String): UploadStatusResponse

    @PUT("media/uploads/{upload_id}/chunks/{chunk_index}")
    suspend fun uploadChunk(
        @Path("upload_id") uploadId: String,
        @Path("chunk_index") chunkIndex: Int,
        @Body body: RequestBody,
    ): ChunkUploadResponse

    @PUT("media/uploads/{upload_id}/thumbnail")
    suspend fun uploadThumbnail(
        @Path("upload_id") uploadId: String,
        @Body body: RequestBody,
    ): ThumbnailUploadResponse

    @POST("media/uploads/{upload_id}/complete")
    suspend fun completeUpload(@Path("upload_id") uploadId: String): MediaResponseDto

    @DELETE("media/uploads/{upload_id}")
    suspend fun cancelUpload(@Path("upload_id") uploadId: String): Response<Unit>
}
