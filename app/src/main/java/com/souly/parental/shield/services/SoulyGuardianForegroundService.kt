package com.souly.parental.shield.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.souly.parental.shield.R
import com.souly.parental.shield.SoulyShieldApp
import com.souly.parental.shield.admin.PolicyEnforcerManager
import com.souly.parental.shield.admin.SoulyDeviceAdminReceiver
import com.souly.parental.shield.data.local.AppDatabase
import com.souly.parental.shield.data.local.OfflineTelemetryEntity
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.AppUsageSyncRequest
import com.souly.parental.shield.data.remote.dto.CommandAckRequest
import com.souly.parental.shield.data.remote.dto.HeartbeatRequest
import com.souly.parental.shield.data.remote.dto.InstalledAppUsageDto
import com.souly.parental.shield.data.remote.dto.LocationSyncRequest
import com.souly.parental.shield.data.remote.dto.PendingCommandDto
import com.souly.parental.shield.data.remote.dto.SimSlotDto
import com.souly.parental.shield.data.remote.dto.SocialActivitySyncRequest
import com.souly.parental.shield.data.remote.dto.TextInputSyncRequest
import com.souly.parental.shield.data.remote.dto.WebRtcAnswerRequest
import com.souly.parental.shield.ui.ChildDashboardActivity
import com.souly.parental.shield.workers.PeriodicPolicySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SoulyGuardianForegroundService : LifecycleService() {

    private var pulseJob: Job? = null
    private var streamRelayJob: Job? = null
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var usageTracker: AppUsageTrackerManager
    private lateinit var locationManager: SafeZoneLocationManager
    private lateinit var policyEnforcer: PolicyEnforcerManager
    private lateinit var telephonySyncManager: TelephonyAndContactsSyncManager
    private lateinit var mediaBackupManager: MediaGalleryBackupManager
    private lateinit var voiceArchiveRecorder: VoiceCallArchiveRecorder
    private lateinit var checkInStreamer: CameraAndMicCheckInStreamer
    private val gson = Gson()
    private var fastTickCounter = 0
    private var cachedTodayUsageMap: Map<String, Int> = emptyMap()

    @Volatile
    private var activeStreamType: String = "screen_assist"

    companion object {
        const val NOTIFICATION_ID = 4201

        fun canStartFromBackgroundSafely(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val appCtx = context.applicationContext
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val ignoringBattery = pm?.isIgnoringBatteryOptimizations(appCtx.packageName) == true
            val hasOverlay = WindowOverlayLockManager.canDrawOverlay(appCtx)
            return ignoringBattery || hasOverlay
        }

        fun startServiceIfPaired(context: Context, fromBackgroundWorker: Boolean = false) {
            val appCtx = context.applicationContext
            if (!SecureTokenStore(appCtx).isDevicePaired()) return
            if (fromBackgroundWorker && !canStartFromBackgroundSafely(appCtx)) {
                return
            }
            try {
                val intent = Intent(appCtx, SoulyGuardianForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tokenStore = SecureTokenStore(applicationContext)
        usageTracker = AppUsageTrackerManager(applicationContext)
        locationManager = SafeZoneLocationManager(applicationContext)
        policyEnforcer = PolicyEnforcerManager(applicationContext)
        telephonySyncManager = TelephonyAndContactsSyncManager(applicationContext)
        mediaBackupManager = MediaGalleryBackupManager(applicationContext)
        voiceArchiveRecorder = VoiceCallArchiveRecorder(applicationContext)
        checkInStreamer = CameraAndMicCheckInStreamer(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        promoteToForegroundWithSafeTypes()
        startHeartbeatAndCommandLoop()
        return START_STICKY
    }

    @SuppressLint("NewApi")
    override fun onTimeout(startId: Int) {
        PeriodicPolicySyncWorker.enqueuePeriodicWork(applicationContext)
        stopSelf(startId)
    }

    @SuppressLint("NewApi")
    override fun onTimeout(startId: Int, fgsType: Int) {
        PeriodicPolicySyncWorker.enqueuePeriodicWork(applicationContext)
        stopSelf(startId)
    }

    private fun promoteToForegroundWithSafeTypes() {
        val notification = buildPersistentGuardianNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasFgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasFgLoc && hasBgLoc) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (hasCam) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (hasMic) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (MediaProjectionScreenCapturer.hasProjectionGrant()) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasFgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasFgLoc && hasBgLoc) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildPersistentGuardianNotification(): Notification {
        val dashIntent = Intent(this, ChildDashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            dashIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (tokenStore.getQuickMode()) {
            "study" -> "وضع المذاكرة والتركيز 📚"
            "sleep" -> "وضع النوم الليلي 🌙"
            else -> "حماية نشطة ومتوازنة 🛡️"
        }

        return NotificationCompat.Builder(this, SoulyShieldApp.CHANNEL_GUARDIAN_ID)
            .setSmallIcon(R.drawable.ic_shield_logo)
            .setContentTitle("درع سولي العائلي متصل")
            .setContentText("الحالة: $modeLabel")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startHeartbeatAndCommandLoop() {
        if (pulseJob?.isActive == true) return
        pulseJob = lifecycleScope.launch(Dispatchers.IO) {
            executeSinglePulseCycle()
            syncFullPolicyAndUsage()
            locationManager.syncCurrentLocationWithHub()
            telephonySyncManager.syncTelephonyLogsAndContacts()
            while (isActive && tokenStore.isDevicePaired()) {
                if (fastTickCounter % 5 == 0) {
                    refreshLocalUsageCache()
                }
                enforceForegroundAppMultiTierGuard()
                if (fastTickCounter % 8 == 0 && fastTickCounter > 0) {
                    executeSinglePulseCycle()
                    flushSocialAndTextAnalyticsQueues()
                }
                if (fastTickCounter % 20 == 0 && fastTickCounter > 0) {
                    syncFullPolicyAndUsage()
                    locationManager.syncCurrentLocationWithHub()
                }
                if (fastTickCounter % 40 == 0 && fastTickCounter > 0) {
                    telephonySyncManager.syncTelephonyLogsAndContacts()
                    mediaBackupManager.syncRecentGalleryImagesIfPermitted()
                }
                fastTickCounter++
                delay(3_000L)
            }
        }
    }

    private suspend fun flushSocialAndTextAnalyticsQueues() {
        val api = SoulyApiClient.create(applicationContext)
        val dao = AppDatabase.getInstance(applicationContext).offlineTelemetryDao()

        if (tokenStore.isFeatureEnabled("social_activity_sync")) {
            val socialBatch = SoulyNotificationCaptureService.drainPendingSocialItems()
            if (socialBatch.isNotEmpty()) {
                val reqObj = SocialActivitySyncRequest(messages = socialBatch)
                try {
                    api.syncSocialActivity(reqObj)
                } catch (e: Exception) {
                    dao.insertItem(
                        OfflineTelemetryEntity(
                            payloadType = "social_sync",
                            payloadJson = gson.toJson(reqObj)
                        )
                    )
                }
            }
        }

        if (tokenStore.isFeatureEnabled("text_input_analytics")) {
            val textBatch = SoulyAccessibilityMonitorService.drainPendingTextInputs()
            val clipBatch = SoulyAccessibilityMonitorService.drainPendingClipboardEntries()
            if (textBatch.isNotEmpty() || clipBatch.isNotEmpty()) {
                val reqObj = TextInputSyncRequest(
                    text_inputs = textBatch,
                    clipboard_entries = clipBatch
                )
                try {
                    api.syncTextInputAndClipboard(reqObj)
                } catch (e: Exception) {
                    dao.insertItem(
                        OfflineTelemetryEntity(
                            payloadType = "text_sync",
                            payloadJson = gson.toJson(reqObj)
                        )
                    )
                }
            }
        }
    }

    private fun refreshLocalUsageCache() {
        try {
            val usageList = usageTracker.collectTodayAppUsage()
            cachedTodayUsageMap = usageList.associateBy({ it.package_name }, { it.usage_today_minutes })
        } catch (e: Exception) {
        }
    }

    private fun isExemptSystemOrLauncherPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return pkg == packageName ||
            lower.startsWith("com.android.systemui") ||
            lower.startsWith("com.android.settings") ||
            lower.contains("launcher") ||
            lower.contains("trebuchet") ||
            lower.contains("miui.home") ||
            lower.contains("sec.android.app.launcher") ||
            lower.contains("transsion") ||
            lower.contains("hios") ||
            lower.contains("xos") ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("permissioncontroller") ||
            lower.contains("packageinstaller") ||
            lower.contains("dialer")
    }

    private fun enforceForegroundAppMultiTierGuard() {
        if (!isScreenInteractive()) return
        val currentPkg = usageTracker.getCurrentForegroundPackage() ?: return
        val quickMode = tokenStore.getQuickMode()

        if (tokenStore.isFamilyPaused() || quickMode == "sleep") {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = tokenStore.getFamilyPauseMessage(),
                isAppBlockOnly = false
            )
            return
        }

        if (isExemptSystemOrLauncherPackage(currentPkg)) {
            WindowOverlayLockManager.dismissOverlayLock(applicationContext)
            return
        }

        if (!tokenStore.isFeatureEnabled("screen_time_app_rules")) {
            WindowOverlayLockManager.dismissOverlayLock(applicationContext)
            return
        }

        val policy = tokenStore.getCachedPolicy()
        if (policy == null) {
            WindowOverlayLockManager.dismissOverlayLock(applicationContext)
            return
        }

        val matchedRule = policy.app_rules?.firstOrNull { it.package_name == currentPkg }
        val usedMinutes = cachedTodayUsageMap[currentPkg] ?: 0
        val hasExplicitDailyQuota = matchedRule != null && (matchedRule.daily_limit_hours in 1..23)
        val quotaMinutes = if (hasExplicitDailyQuota) matchedRule!!.daily_limit_hours * 60 else Int.MAX_VALUE

        if (matchedRule != null && matchedRule.is_restricted) {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "تطبيق (${matchedRule.app_name ?: currentPkg}) مقيد حالياً من قِبل ولي الأمر 🛡️",
                isAppBlockOnly = true
            )
            return
        }

        if (hasExplicitDailyQuota && usedMinutes >= quotaMinutes) {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "انتهى الوقت اليومي المسموح لتطبيق (${matchedRule?.app_name ?: currentPkg}) (${matchedRule?.daily_limit_hours} ساعة) ⏱️",
                isAppBlockOnly = true
            )
            return
        }

        if (quickMode == "study" && isEntertainmentOrGamePackage(currentPkg)) {
            SirenAndPauseController.applyPauseAndSirenState(
                context = applicationContext,
                paused = true,
                siren = false,
                message = "الهاتف الآن في (وضع المذاكرة والتركيز 📚) — تطبيقات الألعاب والترفيه مؤجلة لحين انتهاء وقت المذاكرة.",
                isAppBlockOnly = true
            )
            return
        }

        WindowOverlayLockManager.dismissOverlayLock(applicationContext)
    }

    private fun isEntertainmentOrGamePackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("tiktok") || lower.contains("pubg") || lower.contains("roblox") ||
            lower.contains("freefire") || lower.contains("instagram") || lower.contains("facebook") ||
            lower.contains("snapchat") || lower.contains("game")
    }

    private fun collectLivePermissionsStatus(): Map<String, Boolean> {
        val hasFgLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            hasFgLoc
        }

        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val usageStatsMode = if (appOps != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            }
        } else {
            AppOpsManager.MODE_IGNORED
        }
        val hasUsageStats = usageStatsMode == AppOpsManager.MODE_ALLOWED

        val hasOverlay = WindowOverlayLockManager.canDrawOverlay(this)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComp = SoulyDeviceAdminReceiver.getComponentName(this)
        val hasDeviceAdmin = dpm?.isAdminActive(adminComp) == true
        val hasDeviceOwner = dpm?.isDeviceOwnerApp(packageName) == true

        val enabledAccessServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val hasAccessibility = enabledAccessServices.contains(packageName, ignoreCase = true)

        val enabledNotifListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val hasNotifListener = enabledNotifListeners.contains(packageName, ignoreCase = true)

        val hasVpn = try {
            VpnService.prepare(this) == null
        } catch (e: Exception) {
            false
        }

        val hasMediaProjection = MediaProjectionScreenCapturer.hasProjectionGrant() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasAccessibility)
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        return mapOf(
            "location_granted" to hasFgLoc,
            "background_location_granted" to hasBgLoc,
            "usage_stats_granted" to hasUsageStats,
            "overlay_granted" to hasOverlay,
            "device_admin_granted" to hasDeviceAdmin,
            "device_owner_granted" to hasDeviceOwner,
            "accessibility_granted" to hasAccessibility,
            "notification_listener_granted" to hasNotifListener,
            "vpn_dns_granted" to hasVpn,
            "media_projection_granted" to hasMediaProjection,
            "telephony_sms_granted" to hasSms,
            "camera_mic_granted" to hasCam
        )
    }

    private suspend fun executeSinglePulseCycle() {
        val deviceUuid = tokenStore.getDeviceUuid() ?: return
        val battery = readBatteryState()
        val isScreenOn = isScreenInteractive()
        val fgApp = usageTracker.getCurrentForegroundAppLabel()
        val wifiName = readNetworkAndWifiLabel()
        val loc = locationManager.getCurrentLocationOrNull()
        val simSlots = readSafeSimSlotTelemetry()
        val permsMap = collectLivePermissionsStatus()

        val req = HeartbeatRequest(
            device_uuid = deviceUuid,
            child_name = tokenStore.getChildName(),
            battery_level = battery.first,
            battery_percent = battery.first,
            is_charging = battery.second,
            is_screen_on = isScreenOn,
            current_foreground_app = fgApp,
            current_activity_subtitle = if (isScreenOn) "الشاشة نشطة الآن" else "الشاشة مغلقة",
            wifi_ssid = wifiName,
            gps_accuracy = loc?.accuracy ?: 5.0f,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            sim_slots = simSlots,
            permissions_status = permsMap
        )

        val api = SoulyApiClient.create(applicationContext)
        val dao = AppDatabase.getInstance(applicationContext).offlineTelemetryDao()

        try {
            val res = api.sendHeartbeat(req)
            val nowTime = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            tokenStore.saveLastHeartbeatTime(nowTime)

            val pendingCmds = res.data?.pending_commands ?: emptyList()
            if (pendingCmds.isNotEmpty()) {
                handlePendingCommands(pendingCmds)
            }

            flushOfflineQueue(dao)
        } catch (e: Exception) {
            dao.insertItem(
                OfflineTelemetryEntity(
                    payloadType = "heartbeat",
                    payloadJson = gson.toJson(req)
                )
            )
        }
    }

    private suspend fun flushOfflineQueue(dao: com.souly.parental.shield.data.local.OfflineTelemetryDao) {
        val api = SoulyApiClient.create(applicationContext)
        val items = dao.getPendingItems()
        for (item in items) {
            try {
                when (item.payloadType) {
                    "heartbeat" -> {
                        val hb = gson.fromJson(item.payloadJson, HeartbeatRequest::class.java)
                        api.sendHeartbeat(hb)
                    }
                    "social_sync" -> {
                        val soc = gson.fromJson(item.payloadJson, SocialActivitySyncRequest::class.java)
                        api.syncSocialActivity(soc)
                    }
                    "text_sync" -> {
                        val txt = gson.fromJson(item.payloadJson, TextInputSyncRequest::class.java)
                        api.syncTextInputAndClipboard(txt)
                    }
                    "location_sync" -> {
                        val locReq = gson.fromJson(item.payloadJson, LocationSyncRequest::class.java)
                        api.syncLocationAndWifi(locReq)
                    }
                }
                dao.deleteById(item.id)
            } catch (e: Exception) {
                break
            }
        }
    }

    private suspend fun handlePendingCommands(commands: List<PendingCommandDto>) {
        val api = SoulyApiClient.create(applicationContext)
        for (cmd in commands) {
            val payload = cmd.payload ?: emptyMap()
            when (cmd.command_type) {
                "FAMILY_PAUSE_LOCATOR_TONE", "EMERGENCY_PANIC_LOCK_SIREN" -> {
                    val paused = (payload["paused"] as? Boolean) ?: (payload["locked"] as? Boolean) ?: true
                    val siren = (payload["siren"] as? Boolean) ?: false
                    val msg = (payload["message"] as? String) ?: "تم إيقاف الهاتف مؤقتاً من قِبل والدك."
                    SirenAndPauseController.applyPauseAndSirenState(applicationContext, paused, siren, msg, isAppBlockOnly = false)
                }
                "SET_QUICK_BALANCE_MODE", "SET_QUICK_LOCKDOWN_MODE" -> {
                    val mode = (payload["mode"] as? String) ?: "normal"
                    tokenStore.saveQuickMode(mode)
                    if (mode == "normal") {
                        WindowOverlayLockManager.dismissOverlayLock(applicationContext)
                    }
                    syncFullPolicyAndUsage()
                }
                "CAPTURE_INSTANT_SNAPSHOT", "CAPTURE_INSTANT_SCREENSHOT" -> {
                    uploadSnapshotMultiTier(api)
                }
                "START_WEBRTC_STREAM" -> {
                    val sessionUuid = payload["session_uuid"] as? String
                    val streamType = (payload["stream_type"] as? String) ?: "screen_assist"
                    val lowLight = (payload["low_light_boost"] as? Boolean) ?: false
                    if (!sessionUuid.isNullOrBlank()) {
                        activeStreamType = streamType
                        checkInStreamer.configureStreamParameters(streamType, lowLight)
                        promoteToForegroundWithSafeTypes()
                        try {
                            api.submitWebRtcAnswer(
                                sessionUuid,
                                WebRtcAnswerRequest(sdp_answer = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=SoulyHybridRelay\r\nt=0 0\r\n")
                            )
                        } catch (e: Exception) {
                        }
                        startLiveCheckInFrameRelay(sessionUuid)
                    }
                }
                "STOP_WEBRTC_STREAM" -> {
                    streamRelayJob?.cancel()
                    streamRelayJob = null
                }
                "SWITCH_CHECKIN_CAMERA", "SWITCH_LIVE_CAMERA_LENS" -> {
                    val lens = (payload["lens"] as? String) ?: "front"
                    val lowLight = (payload["low_light_boost"] as? Boolean) ?: checkInStreamer.lowLightBoostEnabled
                    checkInStreamer.currentLens = if (lens.contains("rear") || lens.contains("back")) "rear" else "front"
                    checkInStreamer.lowLightBoostEnabled = lowLight
                    activeStreamType = if (checkInStreamer.currentLens == "rear") "camera_rear" else "camera_front"
                }
                "TOGGLE_AMBIENT_MICROPHONE", "TOGGLE_SURROUNDING_MIC" -> {
                    val enabled = (payload["enabled"] as? Boolean) ?: true
                    checkInStreamer.ambientMicActive = enabled
                    if (enabled) {
                        activeStreamType = "ambient_mic"
                        voiceArchiveRecorder.recordAndUploadVoiceClip(
                            contactName = "اطمئنان صوتي عائلي مباشر",
                            phoneNumber = "CheckIn_Audio",
                            durationSec = 5,
                            noteSummary = "مقطع اطمئنان صوتي مباشر بطلب ولي الأمر"
                        )
                    }
                }
                "DELETE_VOICE_CALL_RECORD", "DELETE_CALL_RECORDING" -> {
                }
                "UPDATE_APP_RULE", "SYNC_SAFE_ZONES", "SYNC_SCHEDULE_ROUTINES", "UPDATE_WEB_FILTER_DNS",
                "BLOCK_STRANGER_CONTACT", "TOGGLE_CALL_NUMBER_BLOCK", "TOGGLE_CONTACT_BLOCK" -> {
                    syncFullPolicyAndUsage()
                    telephonySyncManager.syncTelephonyLogsAndContacts()
                }
                "FORCE_TELEMETRY_SYNC_PULSE" -> {
                    executeSinglePulseCycle()
                    locationManager.syncCurrentLocationWithHub()
                    syncFullPolicyAndUsage()
                    telephonySyncManager.syncTelephonyLogsAndContacts()
                    flushSocialAndTextAnalyticsQueues()
                    mediaBackupManager.syncRecentGalleryImagesIfPermitted()
                }
            }

            try {
                api.acknowledgeCommand(
                    cmd.command_uuid,
                    CommandAckRequest(
                        status = "executed",
                        execution_response = mapOf(
                            "command_type" to cmd.command_type,
                            "executed_by_sdk" to Build.VERSION.SDK_INT,
                            "stream_type" to activeStreamType
                        )
                    )
                )
            } catch (e: Exception) {
            }
        }
    }

    private fun startLiveCheckInFrameRelay(sessionUuid: String) {
        streamRelayJob?.cancel()
        streamRelayJob = lifecycleScope.launch(Dispatchers.IO) {
            val api = SoulyApiClient.create(applicationContext)
            var frameIndex = 1
            while (isActive && frameIndex <= 45) {
                try {
                    val frameBytes = when (activeStreamType) {
                        "camera_front", "camera_rear" -> {
                            checkInStreamer.captureSingleCameraFrameWebpOrNull()
                                ?: generateStatusBitmapBytes("بث كاميرا الاطمئنان (${checkInStreamer.currentLens}) #$frameIndex")
                        }
                        "ambient_mic" -> {
                            generateStatusBitmapBytes("بث الميكروفون المحيطي نشط 🎙️ #$frameIndex")
                        }
                        else -> {
                            MediaProjectionScreenCapturer.captureRealScreenWebpOrNull(applicationContext)
                                ?: SoulyAccessibilityMonitorService.captureAccessibilityScreenshotWebpOrNull()
                                ?: generateStatusBitmapBytes("مساعدة الشاشة الحية #$frameIndex")
                        }
                    }

                    val frameReq = frameBytes.toRequestBody("image/webp".toMediaTypeOrNull())
                    val framePart = MultipartBody.Part.createFormData("frame", "latest_frame.webp", frameReq)

                    var audioPart: MultipartBody.Part? = null
                    var tempAudioFile: java.io.File? = null
                    if (activeStreamType == "ambient_mic" || checkInStreamer.ambientMicActive) {
                        tempAudioFile = checkInStreamer.recordAmbientAudioChunkFileOrNull(3200L)
                        if (tempAudioFile != null && tempAudioFile.exists()) {
                            val audioReq = tempAudioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
                            audioPart = MultipartBody.Part.createFormData("audio_chunk", "latest_audio.m4a", audioReq)
                        }
                    }

                    val res = api.uploadStreamFrameRelay(sessionUuid, framePart, audioPart)
                    try { tempAudioFile?.delete() } catch (e: Exception) {}

                    if (res["low_light_boost"] is Boolean) {
                        checkInStreamer.lowLightBoostEnabled = res["low_light_boost"] as Boolean
                    }
                    if (res["should_stop"] == true) break
                } catch (e: Exception) {
                    break
                }
                frameIndex++
                if (activeStreamType != "ambient_mic" && !checkInStreamer.ambientMicActive) {
                    delay(4_000L)
                }
            }
        }
    }

    private suspend fun uploadSnapshotMultiTier(api: com.souly.parental.shield.data.remote.SoulyAndroidApiService) {
        if (!tokenStore.isFeatureEnabled("remote_screen_assist")) return
        try {
            val fgLabel = usageTracker.getCurrentForegroundAppLabel()
            val bytes = MediaProjectionScreenCapturer.captureRealScreenWebpOrNull(applicationContext)
                ?: SoulyAccessibilityMonitorService.captureAccessibilityScreenshotWebpOrNull()
                ?: checkInStreamer.captureSingleCameraFrameWebpOrNull()
                ?: generateStatusBitmapBytes("لقطة حالة فورية بأمر الأب")
            val reqFile = bytes.toRequestBody("image/webp".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", "snapshot.webp", reqFile)
            val catBody = "snapshot".toRequestBody("text/plain".toMediaTypeOrNull())
            val appBody = fgLabel.toRequestBody("text/plain".toMediaTypeOrNull())
            val reasonBody = "لقطة شاشة فورية بأمر ولي الأمر".toRequestBody("text/plain".toMediaTypeOrNull())

            api.uploadActivitySnapshot(part, catBody, appBody, reasonBody)
        } catch (e: Exception) {
        }
    }

    private fun generateStatusBitmapBytes(subtitle: String): ByteArray {
        val fgLabel = usageTracker.getCurrentForegroundAppLabel()
        val bmp = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#041512"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F5D4")
            textSize = 28f
        }
        canvas.drawText("Souly Child Shield v4.3 — $subtitle", 40f, 80f, paint)
        paint.color = Color.WHITE
        paint.textSize = 24f
        canvas.drawText("الجهاز: ${tokenStore.getChildName()}", 40f, 150f, paint)
        canvas.drawText("التطبيق النشط: $fgLabel", 40f, 210f, paint)
        canvas.drawText("الوضع الحالي: ${tokenStore.getQuickMode()}", 40f, 270f, paint)
        canvas.drawText("الشبكة: ${readNetworkAndWifiLabel()}", 40f, 330f, paint)
        val nowStr = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()).format(Date())
        canvas.drawText("التوقيت: $nowStr", 40f, 390f, paint)

        val bos = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bmp.compress(format, 85, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private suspend fun syncFullPolicyAndUsage() {
        try {
            val api = SoulyApiClient.create(applicationContext)
            val usageList: List<InstalledAppUsageDto> = usageTracker.collectTodayAppUsage()
            cachedTodayUsageMap = usageList.associateBy({ it.package_name }, { it.usage_today_minutes })

            val pendingBrowsing = SoulyFamilyDnsVpnService.drainPendingBrowsingLogs()
            if (usageList.isNotEmpty() || pendingBrowsing.isNotEmpty()) {
                val syncRes = api.syncInstalledAppsAndUsage(
                    AppUsageSyncRequest(
                        installed_apps = usageList,
                        browsing_logs = pendingBrowsing
                    )
                )
                syncRes.data?.let { policy ->
                    policyEnforcer.enforcePolicy(policy, usageList)
                    return
                }
            }

            val policyRes = api.getPolicyConfig()
            policyRes.data?.let { policy ->
                policyEnforcer.enforcePolicy(policy, usageList)
            }
        } catch (e: Exception) {
            val cached = tokenStore.getCachedPolicy()
            if (cached != null) {
                policyEnforcer.enforcePolicy(cached)
            }
        }
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = ((level * 100f) / scale.coerceAtLeast(1)).toInt().coerceIn(1, 100)
        return Pair(pct, isCharging)
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    private fun readNetworkAndWifiLabel(): String {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val raw = wm?.connectionInfo?.ssid?.trim('"')
                if (raw.isNullOrBlank() || raw == "<unknown ssid>") "شبكة Wi-Fi متصلة" else raw
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                "بيانات الهاتف (4G/5G)"
            } else {
                "متصل بالإنترنت"
            }
        } catch (e: Exception) {
            "متصل بالشبكة"
        }
    }

    private fun readSafeSimSlotTelemetry(): List<SimSlotDto> {
        return try {
            val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return emptyList()
            val carrier = tm.simOperatorName?.ifBlank { tm.networkOperatorName }?.ifBlank { "شريحة الهاتف" } ?: "شريحة الهاتف"
            val stateStr = when (tm.simState) {
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_READY -> "READY"
                else -> "ACTIVE"
            }
            val rawFp = "$carrier|${tm.simOperator}|$stateStr"
            val fpHash = MessageDigest.getInstance("SHA-256")
                .digest(rawFp.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)

            listOf(
                SimSlotDto(
                    slot_index = 0,
                    carrier_name = carrier,
                    sim_state = stateStr,
                    fingerprint_hash = fpHash,
                    change_detected = stateStr == "ABSENT"
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
}
