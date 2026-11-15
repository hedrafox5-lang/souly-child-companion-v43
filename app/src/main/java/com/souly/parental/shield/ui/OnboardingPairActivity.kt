package com.souly.parental.shield.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.souly.parental.shield.admin.SoulyDeviceAdminReceiver
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import com.souly.parental.shield.data.remote.dto.PairDeviceRequest
import com.souly.parental.shield.databinding.ActivityOnboardingPairBinding
import com.souly.parental.shield.services.MediaProjectionScreenCapturer
import com.souly.parental.shield.services.SoulyFamilyDnsVpnService
import com.souly.parental.shield.services.SoulyGuardianForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.util.concurrent.Executors

class OnboardingPairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingPairBinding
    private lateinit var tokenStore: SecureTokenStore
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isProcessingBarcode = false

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startQrScannerCamera()
        } else {
            binding.tvScannerOverlayHint.text = "يمكنك كتابة كود الربط بالأسفل أو استخدام الربط بضغطة واحدة"
        }
        refreshLivePermissionsUi()
    }

    private val bgLocationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestIgnoreBatteryOptimizationsIfNeeded()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
        refreshLivePermissionsUi()
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasForegroundLoc = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (hasForegroundLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) {
                bgLocationPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return@registerForActivityResult
            }
        }
        requestIgnoreBatteryOptimizationsIfNeeded()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
        refreshLivePermissionsUi()
        Toast.makeText(this, "تم تحديث صلاحيات الموقع والإشعارات ✅", Toast.LENGTH_SHORT).show()
    }

    private val telephonyContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshLivePermissionsUi()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
        Toast.makeText(this, "تم تحديث صلاحيات المكالمات والرسائل وجهات الاتصال ✅", Toast.LENGTH_SHORT).show()
    }

    private val cameraMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshLivePermissionsUi()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
        Toast.makeText(this, "تم تحديث صلاحيات الكاميرا والميكروفون للاطمئنان العائلي ✅", Toast.LENGTH_SHORT).show()
    }

    private val mediaStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshLivePermissionsUi()
        SoulyGuardianForegroundService.startServiceIfPaired(this)
        Toast.makeText(this, "تم تحديث صلاحيات الصور والوسائط للنسخ الاحتياطي ✅", Toast.LENGTH_SHORT).show()
    }

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        SoulyFamilyDnsVpnService.startFilterIfPermitted(this)
        requestMediaProjectionIfNeeded()
        refreshLivePermissionsUi()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            MediaProjectionScreenCapturer.saveProjectionGrant(result.resultCode, result.data)
            SoulyGuardianForegroundService.startServiceIfPaired(this)
            Toast.makeText(this, "تم تفعيل مشاركة الشاشة ودرع الـ DNS العائلي ✅", Toast.LENGTH_SHORT).show()
        }
        refreshLivePermissionsUi()
    }

    private val callScreeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshLivePermissionsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = SecureTokenStore(this)
        val managePermissionsOnly = intent.getBooleanExtra("manage_permissions_only", false)

        if (tokenStore.isDevicePaired() && !managePermissionsOnly) {
            SoulyGuardianForegroundService.startServiceIfPaired(this)
            navigateToChildDashboard()
            return
        }

        binding = ActivityOnboardingPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissionButtons()
        setupPairingButton()
        refreshLivePermissionsUi()

        if (managePermissionsOnly && tokenStore.isDevicePaired()) {
            binding.qrScannerContainer.visibility = View.GONE
            binding.pairingFormContainer.visibility = View.GONE
        } else {
            checkCameraAndStartScanner()
            checkIncomingDeepLink(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLivePermissionsUi()
        if (!tokenStore.isDevicePaired()) {
            autoFillCodeFromClipboardIfPresent()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIncomingDeepLink(intent)
    }

    private fun autoFillCodeFromClipboardIfPresent() {
        try {
            if (binding.etPairingCode.text.toString().isNotBlank()) return
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            val regex = Regex("SOULY-[A-Z0-9]{4}-\\d{4}", RegexOption.IGNORE_CASE)
            val match = regex.find(clipText)
            if (match != null) {
                binding.etPairingCode.setText(match.value.uppercase())
                binding.tvPairingStatus.text = "✨ تم التقاط كود الربط (${match.value.uppercase()}) من الحافظة تلقائياً! اضغط ربط الآن."
            }
        } catch (e: Exception) {
        }
    }

    private fun checkIncomingDeepLink(incomingIntent: Intent?) {
        val data: Uri = incomingIntent?.data ?: return
        if (data.scheme == "soulyshield" && data.host == "pair") {
            val code = data.getQueryParameter("code") ?: data.getQueryParameter("pairing_code")
            val server = data.getQueryParameter("server")
            val child = data.getQueryParameter("child_name")
            if (!server.isNullOrBlank()) binding.etServerUrl.setText(server)
            if (!child.isNullOrBlank()) binding.etChildName.setText(child)
            if (!code.isNullOrBlank()) {
                binding.etPairingCode.setText(code)
                performDevicePairing()
            }
        }
    }

    private fun checkCameraAndStartScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startQrScannerCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startQrScannerCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && !isProcessingBarcode) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                val raw = barcode.rawValue ?: continue
                                handleScannedQrPayload(raw)
                                break
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScannedQrPayload(rawText: String) {
        if (isProcessingBarcode) return
        isProcessingBarcode = true
        runOnUiThread {
            try {
                if (rawText.trim().startsWith("{")) {
                    val json = JSONObject(rawText)
                    val code = json.optString("pairing_code", "")
                    val hubApi = json.optString("hub_api_base", "")
                    val childName = json.optString("child_name", "")
                    if (code.isNotBlank()) binding.etPairingCode.setText(code)
                    if (hubApi.isNotBlank()) binding.etServerUrl.setText(hubApi.removeSuffix("/api/android/v1"))
                    if (childName.isNotBlank()) binding.etChildName.setText(childName)
                } else {
                    binding.etPairingCode.setText(rawText.trim())
                }
                binding.tvPairingStatus.text = "تم التقاط رمز الـ QR بنجاح! جاري الربط التلقائي..."
                performDevicePairing()
            } catch (e: Exception) {
                isProcessingBarcode = false
            }
        }
    }

    private fun setupPairingButton() {
        binding.btnPairDeviceNow.setOnClickListener {
            performDevicePairing()
        }
    }

    private fun performDevicePairing() {
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val fullModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val childName = binding.etChildName.text.toString().trim().ifBlank { fullModel.ifBlank { "هاتف أندرويد" } }

        if (code.isBlank()) {
            Toast.makeText(this, "يرجى إدخال كود الربط المشفر أو مسح رمز الـ QR", Toast.LENGTH_SHORT).show()
            isProcessingBarcode = false
            return
        }

        tokenStore.setHubBaseUrl(serverUrl)
        binding.btnPairDeviceNow.isEnabled = false
        binding.tvPairingStatus.text = "⏳ جاري الاتصال بسيرفر سولي وإتمام الربط المشفر..."

        val batteryInfo = readInitialBatteryState()
        val wifiSsid = readInitialWifiSsid()
        val simCarrier = readInitialSimCarrier()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = SoulyApiClient.create(this@OnboardingPairActivity)
                val response = api.pairDevice(
                    PairDeviceRequest(
                        pairing_code = code,
                        child_name = childName,
                        device_model = fullModel,
                        manufacturer = Build.MANUFACTURER ?: "Android",
                        android_version = "Android ${Build.VERSION.RELEASE}",
                        sdk_int = Build.VERSION.SDK_INT,
                        battery_level = batteryInfo.first,
                        is_charging = batteryInfo.second,
                        is_screen_on = true,
                        wifi_ssid = wifiSsid,
                        sim_carrier = simCarrier
                    )
                )

                withContext(Dispatchers.Main) {
                    val data = response.data
                    val resolvedToken = data?.sanctum_token ?: data?.api_token
                    if (response.success && data != null && !resolvedToken.isNullOrBlank()) {
                        tokenStore.savePairingCredentials(
                            deviceUuid = data.device_uuid,
                            sanctumToken = resolvedToken,
                            smsSecret = data.sms_command_secret,
                            hubBaseUrl = serverUrl,
                            childName = childName,
                            featureFlags = data.feature_flags,
                            wellbeingKeywords = data.wellbeing_keywords
                        )
                        SoulyGuardianForegroundService.startServiceIfPaired(this@OnboardingPairActivity)
                        Toast.makeText(this@OnboardingPairActivity, "🎉 تم ربط الهاتف بلوحة تحكم الوالدين بنجاح!", Toast.LENGTH_LONG).show()
                        navigateToChildDashboard()
                    } else {
                        binding.btnPairDeviceNow.isEnabled = true
                        isProcessingBarcode = false
                        binding.tvPairingStatus.text = "⚠️ تعذر الربط: ${response.message ?: "تأكد من صحة الكود أو اشتراك الباقة"}"
                    }
                }
            } catch (httpEx: HttpException) {
                val errorText = try {
                    val errRaw = httpEx.response()?.errorBody()?.string()
                    if (!errRaw.isNullOrBlank() && errRaw.trim().startsWith("{")) {
                        val errJson = JSONObject(errRaw)
                        errJson.optString("message").ifBlank { "كود الربط غير صالح أو انتهت حصة الباقة (${httpEx.code()})" }
                    } else {
                        "تعذر الربط مع السيرفر (كود الخطأ ${httpEx.code()})"
                    }
                } catch (e: Exception) {
                    "تعذر الربط مع السيرفر (كود الخطأ ${httpEx.code()})"
                }
                withContext(Dispatchers.Main) {
                    binding.btnPairDeviceNow.isEnabled = true
                    isProcessingBarcode = false
                    binding.tvPairingStatus.text = "⚠️ $errorText"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnPairDeviceNow.isEnabled = true
                    isProcessingBarcode = false
                    binding.tvPairingStatus.text = "⚠️ خطأ في الاتصال بالسيرفر: تأكد من الرابط واتصال الإنترنت"
                }
            }
        }
    }

    private fun readInitialBatteryState(): Pair<Int, Boolean> {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pct = ((level * 100f) / scale.coerceAtLeast(1)).toInt().coerceIn(1, 100)
            Pair(pct, charging)
        } catch (e: Exception) {
            Pair(85, false)
        }
    }

    private fun readInitialWifiSsid(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val raw = wm?.connectionInfo?.ssid?.trim('"')
            if (raw.isNullOrBlank() || raw == "<unknown ssid>") null else raw
        } catch (e: Exception) {
            null
        }
    }

    private fun readInitialSimCarrier(): String? {
        return try {
            val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.simOperatorName?.ifBlank { tm.networkOperatorName }?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun setupPermissionButtons() {
        binding.btnGrantLocation.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            locationPermLauncher.launch(perms.toTypedArray())
        }

        binding.btnGrantUsageStats.setOnClickListener {
            if (!hasUsageStatsPermission()) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else {
                Toast.makeText(this, "صلاحية إحصائيات الاستخدام مفعّلة بالفعل ✅", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null && !nm.canUseFullScreenIntent()) {
                    try {
                        val fsIntent = Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(fsIntent)
                    } catch (e: Exception) {
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "صلاحية الظهور فوق التطبيقات مفعّلة بالفعل ✅", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantDeviceAdmin.setOnClickListener {
            requestCallScreeningRoleIfAvailable()
            val comp = SoulyDeviceAdminReceiver.getComponentName(this)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(com.souly.parental.shield.R.string.device_admin_description))
            }
            startActivity(intent)
        }

        binding.btnGrantVpnDns.setOnClickListener {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermLauncher.launch(vpnIntent)
            } else {
                SoulyFamilyDnsVpnService.startFilterIfPermitted(this)
                requestMediaProjectionIfNeeded()
            }
        }

        binding.btnGrantAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "اختر (درع سولي العائلي) وقم بتفعيله. إذا ظهر مقيداً في أندرويد 13+، افتح إعدادات التطبيق أولاً.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
            }
        }

        binding.btnGrantNotificationListener.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Toast.makeText(this, "قم بتفعيل وصول الإشعارات لـ (درع سولي العائلي)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
            }
        }

        binding.btnGrantTelephonyContacts.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            telephonyContactsLauncher.launch(perms.toTypedArray())
        }

        binding.btnGrantCameraMic.setOnClickListener {
            cameraMicLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }

        binding.btnGrantMediaStorage.setOnClickListener {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            mediaStorageLauncher.launch(perms)
        }

        binding.btnOpenAppDetailsRestrictedSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "في أندرويد 13–15: اضغط القائمة ⋮ أعلى الشاشة ثم اختر (السماح بالإعدادات المقيدة)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
            }
        }
    }

    private fun refreshLivePermissionsUi() {
        if (!::binding.isInitialized) return
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasUsage = hasUsageStatsPermission()
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val hasAdmin = dpm?.isAdminActive(SoulyDeviceAdminReceiver.getComponentName(this)) == true || dpm?.isDeviceOwnerApp(packageName) == true
        val hasVpn = try { VpnService.prepare(this) == null } catch (e: Exception) { false }
        val enabledAccess = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val hasAccessibility = enabledAccess.contains(packageName, ignoreCase = true)
        val enabledNotif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val hasNotif = enabledNotif.contains(packageName, ignoreCase = true)
        val hasTelephony = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasCamMic = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        binding.btnGrantLocation.text = "${if (hasLoc) "✅" else "⚠️"} 1. الموقع الجغرافي بالخلفية والإشعارات"
        binding.btnGrantUsageStats.text = "${if (hasUsage) "✅" else "⚠️"} 2. إحصائيات وقت استخدام التطبيقات"
        binding.btnGrantOverlay.text = "${if (hasOverlay) "✅" else "⚠️"} 3. الظهور فوق التطبيقات وشاشة التوقف"
        binding.btnGrantDeviceAdmin.text = "${if (hasAdmin) "✅" else "⚠️"} 4. مسؤول حماية الجهاز وفلترة المكالمات"
        binding.btnGrantVpnDns.text = "${if (hasVpn) "✅" else "⚠️"} 5. درع الـ DNS العائلي ولقطات الشاشة"
        binding.btnGrantAccessibility.text = "${if (hasAccessibility) "✅" else "⚠️"} 6. خدمة إمكانية الوصول (Accessibility)"
        binding.btnGrantNotificationListener.text = "${if (hasNotif) "✅" else "⚠️"} 7. قارئ تنبيهات تطبيقات التواصل"
        binding.btnGrantTelephonyContacts.text = "${if (hasTelephony) "✅" else "⚠️"} 8. سجل المكالمات والرسائل وجهات الاتصال"
        binding.btnGrantCameraMic.text = "${if (hasCamMic) "✅" else "⚠️"} 9. الكاميرا والميكروفون للاطمئنان العائلي"
        binding.btnGrantMediaStorage.text = "${if (hasMedia) "✅" else "⚠️"} 10. الوسائط والصور للنسخ الاحتياطي"

        val grantedCount = listOf(
            hasLoc, hasUsage, hasOverlay, hasAdmin, hasVpn,
            hasAccessibility, hasNotif, hasTelephony, hasCamMic, hasMedia
        ).count { it }
        binding.tvPermissionsSummaryHeader.text = "⚙️ الصلاحيات الممنوحة حالياً: ($grantedCount من 10) — اضغط على أي صلاحية لتفعيلها:"
    }

    private fun requestMediaProjectionIfNeeded() {
        if (!MediaProjectionScreenCapturer.hasProjectionGrant()) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            } catch (e: Exception) {
            }
        } else {
            Toast.makeText(this, "درع الـ DNS ولقطات الشاشة الفورية نشطان بالفعل ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestCallScreeningRoleIfAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rm = getSystemService(RoleManager::class.java) ?: return
                if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    callScreeningRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun navigateToChildDashboard() {
        startActivity(Intent(this, ChildDashboardActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
