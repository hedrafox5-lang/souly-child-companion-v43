package com.souly.parental.shield.services

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.dto.BrowsingLogItemDto
import com.souly.parental.shield.data.remote.dto.ClipboardEntryItemDto
import com.souly.parental.shield.data.remote.dto.TextInputAnalyticsItemDto
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

class SoulyAccessibilityMonitorService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private var lastObservedUrl: String = ""

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChanged()
    }

    companion object {
        @Volatile
        var activeInstance: SoulyAccessibilityMonitorService? = null
            private set

        @Volatile
        var latestAccessibilityForegroundPkg: String? = null
            private set

        private val pendingTextInputs = ConcurrentLinkedQueue<TextInputAnalyticsItemDto>()
        private val pendingClipboardEntries = ConcurrentLinkedQueue<ClipboardEntryItemDto>()

        private val browserUrlBarViewIds = listOf(
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.microsoft.emmx:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.brave.browser:id/url_bar"
        )

        fun drainPendingTextInputs(): List<TextInputAnalyticsItemDto> {
            val list = mutableListOf<TextInputAnalyticsItemDto>()
            while (true) {
                val item = pendingTextInputs.poll() ?: break
                list.add(item)
            }
            return list
        }

        fun drainPendingClipboardEntries(): List<ClipboardEntryItemDto> {
            val list = mutableListOf<ClipboardEntryItemDto>()
            while (true) {
                val item = pendingClipboardEntries.poll() ?: break
                list.add(item)
            }
            return list
        }

        suspend fun captureAccessibilityScreenshotWebpOrNull(): ByteArray? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val service = activeInstance ?: return null
            return suspendCancellableCoroutine { cont ->
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val hwBuffer = screenshot.hardwareBuffer
                                    val colorSpace = screenshot.colorSpace
                                    val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    hwBuffer.close()
                                    if (hwBitmap == null) {
                                        if (cont.isActive) cont.resume(null)
                                        return
                                    }
                                    val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                    val bos = ByteArrayOutputStream()
                                    softBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 78, bos)
                                    softBitmap.recycle()
                                    if (cont.isActive) cont.resume(bos.toByteArray())
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg.startsWith("com.android.systemui")) return

        val store = SecureTokenStore(applicationContext)
        if (!store.isDevicePaired()) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            latestAccessibilityForegroundPkg = pkg
            enforceInstantAppRestrictionIfNeeded(pkg, store)
        }

        if (isBrowserPackage(pkg) && store.isFeatureEnabled("web_filter_media_backup")) {
            inspectBrowserUrlBar(pkg, store)
        }
    }

    private fun enforceInstantAppRestrictionIfNeeded(pkg: String, store: SecureTokenStore) {
        if (!store.isFeatureEnabled("screen_time_app_rules")) return
        val policy = store.getCachedPolicy() ?: return
        val matchedRule = policy.app_rules?.firstOrNull { it.package_name == pkg && it.is_restricted }
        if (matchedRule != null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "تطبيق (${matchedRule.app_name ?: pkg}) مقيد حالياً من قِبل ولي الأمر 🛡️",
                isAppBlockOnly = true
            )
        }
    }

    private fun isBrowserPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("chrome") ||
            lower.contains("firefox") ||
            lower.contains("sbrowser") ||
            lower.contains("emmx") ||
            lower.contains("opera") ||
            lower.contains("brave") ||
            lower.contains("browser")
    }

    private fun inspectBrowserUrlBar(pkg: String, store: SecureTokenStore) {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            for (viewId in browserUrlBarViewIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    val rawUrl = nodes[0]?.text?.toString()?.trim() ?: ""
                    if (rawUrl.isNotBlank() && rawUrl.contains(".") && !rawUrl.contains(" ") && rawUrl != lastObservedUrl) {
                        lastObservedUrl = rawUrl
                        val normalizedUrl = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
                        val blockedDomains = SoulyFamilyDnsVpnService.getActiveBlockedDomains()
                        val isBlocked = blockedDomains.any { domain ->
                            domain.isNotBlank() && rawUrl.lowercase().contains(domain)
                        }

                        SoulyFamilyDnsVpnService.recordBrowserVisitEvent(
                            BrowsingLogItemDto(
                                page_title = rawUrl.take(120),
                                url = normalizedUrl.take(255),
                                browser_package = pkg,
                                is_private_tab = false,
                                filter_status = if (isBlocked) "filtered_by_dns" else "logged",
                                visited_at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                            )
                        )

                        if (isBlocked) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        checkWellbeingKeywordsInQuery(rawUrl, pkg, store)
                    }
                    break
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun checkWellbeingKeywordsInQuery(queryText: String, appPkg: String, store: SecureTokenStore) {
        if (!store.isFeatureEnabled("text_input_analytics")) return
        val keywords = store.getWellbeingKeywords()
        if (keywords.isEmpty()) return

        val matched = keywords.firstOrNull { kw ->
            kw.isNotBlank() && queryText.contains(kw, ignoreCase = true)
        } ?: return

        val nowIso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        pendingTextInputs.add(
            TextInputAnalyticsItemDto(
                event_type = "TYPED_TEXT",
                app_name = appPkg,
                draft_input_text = "تنبيه كلمة مفتاحية للسلامة: $matched",
                is_revised_draft = false,
                final_submitted_text = queryText.take(250),
                typed_at = nowIso
            )
        )
        while (pendingTextInputs.size > 40) {
            pendingTextInputs.poll()
        }
    }

    private fun handleClipboardChanged() {
        try {
            val store = SecureTokenStore(applicationContext)
            if (!store.isDevicePaired() || !store.isFeatureEnabled("text_input_analytics")) return
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            if (text.isBlank() || text.startsWith("SOULY-")) return

            val keywords = store.getWellbeingKeywords()
            val isUrl = text.startsWith("http://") || text.startsWith("https://")
            val matchesKeyword = keywords.any { kw -> kw.isNotBlank() && text.contains(kw, ignoreCase = true) }
            if (!isUrl && !matchesKeyword) return

            val fgPkg = latestAccessibilityForegroundPkg ?: "System_Clipboard"
            pendingClipboardEntries.add(
                ClipboardEntryItemDto(
                    source_app = fgPkg,
                    entry_type = if (isUrl) "url" else "text",
                    content_text = text.take(300),
                    context_note = if (matchesKeyword) "تطابق مع كلمات السلامة العائلية" else "نسخ رابط ويب"
                )
            )
            while (pendingClipboardEntries.size > 30) {
                pendingClipboardEntries.poll()
            }
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        activeInstance = null
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
