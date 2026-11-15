package com.souly.parental.shield.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.SocialActivityItemDto
import com.souly.parental.shield.data.remote.dto.SocialActivitySyncRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class SoulyNotificationCaptureService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val pendingSocialItems = ConcurrentLinkedQueue<SocialActivityItemDto>()
        private val activeNotificationCache = ConcurrentHashMap<String, SocialActivityItemDto>()

        private val socialPackagesMap = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.snapchat.android" to "Snapchat"
        )

        fun enqueueSocialEvent(item: SocialActivityItemDto) {
            pendingSocialItems.add(item)
            while (pendingSocialItems.size > 60) {
                pendingSocialItems.poll()
            }
        }

        fun drainPendingSocialItems(): List<SocialActivityItemDto> {
            val list = mutableListOf<SocialActivityItemDto>()
            while (true) {
                val item = pendingSocialItems.poll() ?: break
                list.add(item)
            }
            return list
        }

        private fun isOtpOrSensitiveAuthText(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("otp") ||
                lower.contains("verification code") ||
                lower.contains("رمز التحقق") ||
                lower.contains("كود التفعيل") ||
                lower.contains("كلمة المرور") ||
                lower.contains("password")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val appName = socialPackagesMap[pkg] ?: return

        val store = SecureTokenStore(applicationContext)
        if (!store.isDevicePaired() || !store.isFeatureEnabled("social_activity_sync")) return

        val extras = sbn.notification?.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val rawBody = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: return

        if (sender.isBlank() || rawBody.isBlank()) return
        if (rawBody.contains("جاري البحث عن رسائل") || rawBody.contains("Checking for new messages", ignoreCase = true)) return
        if (isOtpOrSensitiveAuthText(rawBody)) return

        val isVoiceMsg = rawBody.contains("رسالة صوتية") || rawBody.contains("Voice message", ignoreCase = true) || rawBody.contains("🎤")
        val isDeletedNotice = rawBody.contains("تم حذف هذه الرسالة") || rawBody.contains("This message was deleted", ignoreCase = true)
        val nowIso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val item = SocialActivityItemDto(
            app_name = appName,
            contact_name = sender.take(120),
            contact_identifier = sender.take(120),
            direction = "incoming",
            message_text = rawBody.take(500),
            media_type = if (isVoiceMsg) "voice" else "text",
            voice_duration_sec = if (isVoiceMsg) 12 else 0,
            is_deletion_event = isDeletedNotice,
            captured_at = nowIso
        )

        activeNotificationCache[sbn.key] = item
        enqueueSocialEvent(item)
        flushSocialQueueAsync()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null) return
        val cached = activeNotificationCache.remove(sbn.key) ?: return
        if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
            val deletedEvent = cached.copy(
                is_deletion_event = true,
                captured_at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
            enqueueSocialEvent(deletedEvent)
            flushSocialQueueAsync()
        }
    }

    private fun flushSocialQueueAsync() {
        val batch = drainPendingSocialItems()
        if (batch.isEmpty()) return
        serviceScope.launch {
            try {
                SoulyApiClient.create(applicationContext).syncSocialActivity(
                    SocialActivitySyncRequest(messages = batch)
                )
            } catch (e: Exception) {
                batch.forEach { enqueueSocialEvent(it) }
            }
        }
    }
}
