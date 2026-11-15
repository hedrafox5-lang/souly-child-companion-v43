package com.souly.parental.shield.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.souly.parental.shield.data.remote.dto.PolicyConfigData

class SecureTokenStore(context: Context) {

    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)
    private val gson = Gson()

    private fun createSecurePrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "souly_shield_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            appContext.getSharedPreferences("souly_shield_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    private fun defaultHardwareName(): String {
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return model.ifBlank { "هاتف أندرويد" }
    }

    fun savePairingCredentials(
        deviceUuid: String,
        sanctumToken: String,
        smsSecret: String,
        hubBaseUrl: String,
        childName: String = defaultHardwareName(),
        featureFlags: Map<String, Boolean>? = null,
        wellbeingKeywords: List<String>? = null
    ) {
        val resolvedName = childName.trim().ifBlank { defaultHardwareName() }
        val editor = prefs.edit()
            .putString("device_uuid", deviceUuid)
            .putString("sanctum_token", sanctumToken)
            .putString("sms_command_secret", smsSecret)
            .putString("hub_base_url", normalizeBaseUrl(hubBaseUrl))
            .putString("child_name", resolvedName)
            .putBoolean("is_paired", true)

        if (featureFlags != null) {
            editor.putString("feature_flags_json", gson.toJson(featureFlags))
        }
        if (wellbeingKeywords != null) {
            editor.putString("wellbeing_keywords_json", gson.toJson(wellbeingKeywords))
        }
        editor.apply()
    }

    fun clearPairingSession() {
        prefs.edit()
            .remove("device_uuid")
            .remove("sanctum_token")
            .remove("sms_command_secret")
            .putBoolean("is_paired", false)
            .apply()
    }

    fun isDevicePaired(): Boolean {
        return prefs.getBoolean("is_paired", false) && !getSanctumToken().isNullOrBlank()
    }

    fun getDeviceUuid(): String? = prefs.getString("device_uuid", null)

    fun getSanctumToken(): String? = prefs.getString("sanctum_token", null)

    fun getSmsSecret(): String? = prefs.getString("sms_command_secret", null)

    fun getChildName(): String {
        val stored = prefs.getString("child_name", null)?.trim()
        return if (stored.isNullOrBlank()) defaultHardwareName() else stored
    }

    fun setHubBaseUrl(url: String) {
        prefs.edit().putString("hub_base_url", normalizeBaseUrl(url)).apply()
    }

    fun getHubBaseUrl(): String {
        val raw = prefs.getString("hub_base_url", "https://soulyeg.online") ?: "https://soulyeg.online"
        return normalizeBaseUrl(raw)
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith("/api/android/v1")) {
            "$trimmed/"
        } else {
            "$trimmed/api/android/v1/"
        }
    }

    fun saveQuickMode(mode: String) {
        prefs.edit().putString("quick_balance_mode", mode).apply()
    }

    fun getQuickMode(): String = prefs.getString("quick_balance_mode", "normal") ?: "normal"

    fun saveFamilyPauseState(paused: Boolean, siren: Boolean, message: String?) {
        prefs.edit()
            .putBoolean("is_family_paused", paused)
            .putBoolean("is_siren_active", siren)
            .putString("family_pause_message", message ?: "تم إيقاف الهاتف مؤقتاً من قِبل والدك.")
            .apply()
    }

    fun isFamilyPaused(): Boolean = prefs.getBoolean("is_family_paused", false)

    fun getFamilyPauseMessage(): String =
        prefs.getString("family_pause_message", "تم إيقاف الهاتف مؤقتاً من قِبل والدك.") ?: "تم إيقاف الهاتف مؤقتاً من قِبل والدك."

    fun saveDnsAndVpnFlags(dnsEnabled: Boolean, vpnRestricted: Boolean) {
        prefs.edit()
            .putBoolean("dns_family_filter_enabled", dnsEnabled)
            .putBoolean("vpn_restriction_enabled", vpnRestricted)
            .apply()
    }

    fun isDnsFamilyFilterEnabled(): Boolean = prefs.getBoolean("dns_family_filter_enabled", true)

    fun saveLastHeartbeatTime(timestamp: String) {
        prefs.edit().putString("last_heartbeat_time", timestamp).apply()
    }

    fun getLastHeartbeatTime(): String = prefs.getString("last_heartbeat_time", "لم تتم المزامنة بعد") ?: "لم تتم المزامنة بعد"

    fun saveFeatureFlags(flags: Map<String, Boolean>) {
        prefs.edit().putString("feature_flags_json", gson.toJson(flags)).apply()
    }

    fun getFeatureFlags(): Map<String, Boolean> {
        val raw = prefs.getString("feature_flags_json", null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, Boolean>>() {}.type
                return gson.fromJson(raw, type) ?: emptyMap()
            } catch (e: Exception) {
            }
        }
        return getCachedPolicy()?.feature_flags ?: emptyMap()
    }

    fun isFeatureEnabled(featureKey: String): Boolean {
        val flags = getFeatureFlags()
        if (flags.isEmpty()) return true
        return flags[featureKey] ?: true
    }

    fun saveRestrictedContacts(numbers: List<String>) {
        prefs.edit().putString("restricted_contacts_json", gson.toJson(numbers)).apply()
    }

    fun getRestrictedContacts(): List<String> {
        val raw = prefs.getString("restricted_contacts_json", null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                return gson.fromJson(raw, type) ?: emptyList()
            } catch (e: Exception) {
            }
        }
        return getCachedPolicy()?.restricted_contacts ?: emptyList()
    }

    fun getWellbeingKeywords(): List<String> {
        val policyKeywords = getCachedPolicy()?.wellbeing_keywords
        if (!policyKeywords.isNullOrEmpty()) return policyKeywords
        val raw = prefs.getString("wellbeing_keywords_json", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveLastSyncedCallTimestamp(tsMillis: Long) {
        prefs.edit().putLong("last_synced_call_ts", tsMillis).apply()
    }

    fun getLastSyncedCallTimestamp(): Long = prefs.getLong("last_synced_call_ts", 0L)

    fun saveLastSyncedSmsTimestamp(tsMillis: Long) {
        prefs.edit().putLong("last_synced_sms_ts", tsMillis).apply()
    }

    fun getLastSyncedSmsTimestamp(): Long = prefs.getLong("last_synced_sms_ts", 0L)

    fun saveLastBackedUpMediaId(mediaId: Long) {
        prefs.edit().putLong("last_backed_up_media_id", mediaId).apply()
    }

    fun getLastBackedUpMediaId(): Long = prefs.getLong("last_backed_up_media_id", 0L)

    fun saveCachedPolicy(policy: PolicyConfigData) {
        prefs.edit().putString("cached_policy_json", gson.toJson(policy)).apply()
        saveQuickMode(policy.quick_balance_mode ?: "normal")
        saveDnsAndVpnFlags(
            policy.dns_family_filter_enabled ?: true,
            policy.vpn_restriction_enabled ?: true
        )
        policy.feature_flags?.let { saveFeatureFlags(it) }
        policy.restricted_contacts?.let { saveRestrictedContacts(it) }
    }

    fun getCachedPolicy(): PolicyConfigData? {
        val raw = prefs.getString("cached_policy_json", null) ?: return null
        return try {
            gson.fromJson(raw, PolicyConfigData::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
