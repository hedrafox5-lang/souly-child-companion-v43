package com.souly.parental.shield.data.remote

import com.souly.parental.shield.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface SoulyAndroidApiService {

    @POST("pair-device")
    suspend fun pairDevice(@Body request: PairDeviceRequest): ApiResponse<PairDeviceData>

    @POST("auth/pair-device")
    suspend fun pairDeviceAuthAlias(@Body request: PairDeviceRequest): ApiResponse<PairDeviceData>

    @GET("policy-config")
    suspend fun getPolicyConfig(): ApiResponse<PolicyConfigData>

    @POST("heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest): ApiResponse<HeartbeatData>

    @GET("commands/pending")
    suspend fun getPendingCommands(): ApiResponse<HeartbeatData>

    @POST("commands/{commandUuid}/ack")
    suspend fun acknowledgeCommand(
        @Path("commandUuid") commandUuid: String,
        @Body request: CommandAckRequest
    ): ApiResponse<Map<String, Any?>>

    @POST("sms-fallback/ack")
    suspend fun acknowledgeSmsFallback(
        @Body request: Map<String, String>
    ): ApiResponse<Map<String, Any?>>

    @POST("sync/location-safe-zones")
    suspend fun syncLocationAndWifi(
        @Body request: LocationSyncRequest
    ): ApiResponse<LocationSyncResponse>

    @POST("sync/app-rules-web-filter")
    suspend fun syncInstalledAppsAndUsage(
        @Body request: AppUsageSyncRequest
    ): ApiResponse<PolicyConfigData>

    @POST("sync/social-activity")
    suspend fun syncSocialActivity(
        @Body request: SocialActivitySyncRequest
    ): ApiResponse<SocialActivitySyncResponse>

    @POST("sync/telephony-rules")
    suspend fun syncTelephonyAndContacts(
        @Body request: TelephonySyncRequest
    ): ApiResponse<TelephonySyncResponse>

    @POST("sync/text-input-analytics")
    suspend fun syncTextInputAndClipboard(
        @Body request: TextInputSyncRequest
    ): ApiResponse<TextInputSyncResponse>

    @Multipart
    @POST("upload/activity-snapshot")
    suspend fun uploadActivitySnapshot(
        @Part file: MultipartBody.Part,
        @Part("upload_category") uploadCategory: RequestBody,
        @Part("foreground_app_name") foregroundAppName: RequestBody,
        @Part("capture_reason") captureReason: RequestBody,
        @Part("file_name") fileName: RequestBody? = null,
        @Part("source_folder") sourceFolder: RequestBody? = null
    ): ApiResponse<Map<String, Any?>>

    @Multipart
    @POST("upload/voice-archive")
    suspend fun uploadVoiceCallArchive(
        @Part audioFile: MultipartBody.Part,
        @Part("contact_name") contactName: RequestBody,
        @Part("phone_number") phoneNumber: RequestBody,
        @Part("duration_seconds") durationSeconds: RequestBody,
        @Part("file_size_bytes") fileSizeBytes: RequestBody,
        @Part("waveform_peaks_json") waveformPeaksJson: RequestBody,
        @Part("ai_note_summary") aiNoteSummary: RequestBody
    ): ApiResponse<Map<String, Any?>>

    @POST("webrtc/{sessionUuid}/answer")
    suspend fun submitWebRtcAnswer(
        @Path("sessionUuid") sessionUuid: String,
        @Body request: WebRtcAnswerRequest
    ): ApiResponse<Map<String, Any?>>

    @POST("webrtc/{sessionUuid}/ice")
    suspend fun submitWebRtcIceCandidate(
        @Path("sessionUuid") sessionUuid: String,
        @Body request: WebRtcIceCandidateRequest
    ): ApiResponse<Map<String, Any?>>

    @Multipart
    @POST("stream/{sessionUuid}/frame-relay")
    suspend fun uploadStreamFrameRelay(
        @Path("sessionUuid") sessionUuid: String,
        @Part frame: MultipartBody.Part? = null,
        @Part audioChunk: MultipartBody.Part? = null
    ): Map<String, Any?>
}
