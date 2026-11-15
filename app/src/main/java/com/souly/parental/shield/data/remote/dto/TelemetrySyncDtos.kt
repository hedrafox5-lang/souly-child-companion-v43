package com.souly.parental.shield.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LocationPointDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy_meters") val accuracy_meters: Float,
    @SerializedName("address_text") val address_text: String,
    @SerializedName("speed_kmh") val speed_kmh: Float,
    @SerializedName("movement_state") val movement_state: String,
    @SerializedName("is_outside_safe_zone") val is_outside_safe_zone: Boolean
)

data class WifiLogDto(
    @SerializedName("ssid") val ssid: String,
    @SerializedName("bssid_mac") val bssid_mac: String? = null,
    @SerializedName("estimated_location_label") val estimated_location_label: String = "شبكة لاسلكية",
    @SerializedName("security_type") val security_type: String = "WPA2/WPA3"
)

data class LocationSyncRequest(
    @SerializedName("device_uuid") val device_uuid: String,
    @SerializedName("is_outside_safe_zone") val is_outside_safe_zone: Boolean,
    @SerializedName("locations") val locations: List<LocationPointDto>,
    @SerializedName("wifi_history") val wifi_history: List<WifiLogDto> = emptyList()
)

data class LocationSyncResponse(
    @SerializedName("is_outside_safe_zone") val is_outside_safe_zone: Boolean = false,
    @SerializedName("active_safe_zones") val active_safe_zones: List<SafeZoneDto>? = emptyList()
)

data class InstalledAppUsageDto(
    @SerializedName("package_name") val package_name: String,
    @SerializedName("app_name") val app_name: String,
    @SerializedName("category_label") val category_label: String = "تطبيقات الهاتف",
    @SerializedName("usage_today_minutes") val usage_today_minutes: Int = 0,
    @SerializedName("is_secondary_utility_app") val is_secondary_utility_app: Boolean = false
)

data class BrowsingLogItemDto(
    @SerializedName("page_title") val page_title: String,
    @SerializedName("url") val url: String,
    @SerializedName("browser_package") val browser_package: String = "DNS_Shield",
    @SerializedName("is_private_tab") val is_private_tab: Boolean = false,
    @SerializedName("filter_status") val filter_status: String = "filtered_by_dns",
    @SerializedName("visited_at") val visited_at: String? = null
)

data class AppUsageSyncRequest(
    @SerializedName("installed_apps") val installed_apps: List<InstalledAppUsageDto>,
    @SerializedName("browsing_logs") val browsing_logs: List<BrowsingLogItemDto> = emptyList()
)

data class SocialActivityItemDto(
    @SerializedName("app_name") val app_name: String,
    @SerializedName("contact_name") val contact_name: String,
    @SerializedName("contact_identifier") val contact_identifier: String? = null,
    @SerializedName("direction") val direction: String = "incoming",
    @SerializedName("message_text") val message_text: String? = null,
    @SerializedName("media_type") val media_type: String = "text",
    @SerializedName("voice_duration_sec") val voice_duration_sec: Int = 0,
    @SerializedName("is_deletion_event") val is_deletion_event: Boolean = false,
    @SerializedName("captured_at") val captured_at: String? = null
)

data class SocialActivitySyncRequest(
    @SerializedName("messages") val messages: List<SocialActivityItemDto>
)

data class SocialActivitySyncResponse(
    @SerializedName("synced_count") val synced_count: Int = 0,
    @SerializedName("wellbeing_alerts_triggered") val wellbeing_alerts_triggered: Int = 0
)

data class CallLogItemDto(
    @SerializedName("contact_name") val contact_name: String,
    @SerializedName("phone_number") val phone_number: String,
    @SerializedName("call_type") val call_type: String = "incoming",
    @SerializedName("duration_seconds") val duration_seconds: Int = 0,
    @SerializedName("sim_slot_index") val sim_slot_index: Int = 0,
    @SerializedName("called_at") val called_at: String? = null
)

data class SmsLogItemDto(
    @SerializedName("sender_name") val sender_name: String,
    @SerializedName("phone_number") val phone_number: String,
    @SerializedName("direction") val direction: String = "incoming",
    @SerializedName("sms_body") val sms_body: String,
    @SerializedName("received_at") val received_at: String? = null
)

data class ContactEntryDto(
    @SerializedName("contact_name") val contact_name: String,
    @SerializedName("phone_number") val phone_number: String
)

data class TelephonySyncRequest(
    @SerializedName("calls") val calls: List<CallLogItemDto> = emptyList(),
    @SerializedName("sms_messages") val sms_messages: List<SmsLogItemDto> = emptyList(),
    @SerializedName("contacts") val contacts: List<ContactEntryDto> = emptyList()
)

data class TelephonySyncResponse(
    @SerializedName("restricted_numbers") val restricted_numbers: List<String>? = emptyList()
)

data class TextInputAnalyticsItemDto(
    @SerializedName("event_type") val event_type: String = "TYPED_TEXT",
    @SerializedName("app_name") val app_name: String,
    @SerializedName("draft_input_text") val draft_input_text: String? = null,
    @SerializedName("is_revised_draft") val is_revised_draft: Boolean = false,
    @SerializedName("final_submitted_text") val final_submitted_text: String? = null,
    @SerializedName("typed_at") val typed_at: String? = null
)

data class ClipboardEntryItemDto(
    @SerializedName("source_app") val source_app: String,
    @SerializedName("entry_type") val entry_type: String = "text",
    @SerializedName("content_text") val content_text: String,
    @SerializedName("context_note") val context_note: String? = null
)

data class TextInputSyncRequest(
    @SerializedName("text_inputs") val text_inputs: List<TextInputAnalyticsItemDto> = emptyList(),
    @SerializedName("clipboard_entries") val clipboard_entries: List<ClipboardEntryItemDto> = emptyList()
)

data class TextInputSyncResponse(
    @SerializedName("synced_inputs") val synced_inputs: Int = 0,
    @SerializedName("synced_clipboard") val synced_clipboard: Int = 0,
    @SerializedName("wellbeing_alerts") val wellbeing_alerts: Int = 0
)

data class WebRtcAnswerRequest(
    @SerializedName("sdp_answer") val sdp_answer: String
)

data class WebRtcIceCandidateRequest(
    @SerializedName("candidate") val candidate: String,
    @SerializedName("sdpMid") val sdpMid: String? = "0",
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int = 0
)
