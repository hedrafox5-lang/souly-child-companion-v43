package com.souly.parental.shield.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

class CameraAndMicCheckInStreamer(private val context: Context) {

    @Volatile
    var currentLens: String = "front"

    @Volatile
    var lowLightBoostEnabled: Boolean = false

    @Volatile
    var ambientMicActive: Boolean = false

    fun configureStreamParameters(streamType: String, lowLight: Boolean) {
        lowLightBoostEnabled = lowLight
        when (streamType) {
            "camera_rear" -> {
                currentLens = "rear"
                ambientMicActive = false
            }
            "camera_front" -> {
                currentLens = "front"
                ambientMicActive = false
            }
            "ambient_mic" -> {
                ambientMicActive = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun captureSingleCameraFrameWebpOrNull(): ByteArray? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val targetFacing = if (currentLens == "rear") {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null

        val handlerThread = HandlerThread("SoulyCheckInCamThread").apply { start() }
        val handler = Handler(handlerThread.looper)
        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)

        return try {
            val rawJpeg = withTimeoutOrNull(3500L) {
                suspendCancellableCoroutine<ByteArray?> { cont ->
                    var openedCamera: CameraDevice? = null
                    imageReader.setOnImageAvailableListener({ reader ->
                        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                        if (img != null) {
                            try {
                                val buffer = img.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                img.close()
                                try { openedCamera?.close() } catch (e: Exception) {}
                                if (cont.isActive) cont.resume(bytes)
                            } catch (e: Exception) {
                                try { img.close() } catch (ex: Exception) {}
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    }, handler)

                    try {
                        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                openedCamera = camera
                                try {
                                    val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        if (lowLightBoostEnabled) {
                                            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 4)
                                        }
                                    }
                                    @Suppress("DEPRECATION")
                                    camera.createCaptureSession(
                                        listOf(imageReader.surface),
                                        object : CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(session: CameraCaptureSession) {
                                                try {
                                                    session.capture(captureBuilder.build(), null, handler)
                                                } catch (e: Exception) {
                                                    try { camera.close() } catch (ex: Exception) {}
                                                    if (cont.isActive) cont.resume(null)
                                                }
                                            }

                                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                                try { camera.close() } catch (ex: Exception) {}
                                                if (cont.isActive) cont.resume(null)
                                            }
                                        },
                                        handler
                                    )
                                } catch (e: Exception) {
                                    try { camera.close() } catch (ex: Exception) {}
                                    if (cont.isActive) cont.resume(null)
                                }
                            }

                            override fun onDisconnected(camera: CameraDevice) {
                                try { camera.close() } catch (e: Exception) {}
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onError(camera: CameraDevice, error: Int) {
                                try { camera.close() } catch (e: Exception) {}
                                if (cont.isActive) cont.resume(null)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } ?: return null

            convertJpegToWebpWithNightBoost(rawJpeg, lowLightBoostEnabled)
        } catch (e: Exception) {
            null
        } finally {
            try { imageReader.close() } catch (e: Exception) {}
            try { handlerThread.quitSafely() } catch (e: Exception) {}
        }
    }

    private fun convertJpegToWebpWithNightBoost(jpegBytes: ByteArray, applyLowLightBoost: Boolean): ByteArray? {
        return try {
            val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
            val targetBitmap = if (applyLowLightBoost) {
                val boosted = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(boosted)
                val cm = ColorMatrix(
                    floatArrayOf(
                        1.35f, 0f, 0f, 0f, 38f,
                        0f, 1.45f, 0f, 0f, 42f,
                        0f, 0f, 1.35f, 0f, 38f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                }
                canvas.drawBitmap(decoded, 0f, 0f, paint)
                decoded.recycle()
                boosted
            } else {
                decoded
            }

            val bos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            targetBitmap.compress(format, 80, bos)
            targetBitmap.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun recordAmbientAudioChunkFileOrNull(durationMs: Long = 3500L): File? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val chunkFile = File(context.cacheDir, "checkin_audio_${System.currentTimeMillis()}.m4a")
        var recorder: MediaRecorder? = null

        return try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16000)
            recorder.setAudioEncodingBitRate(32000)
            recorder.setOutputFile(chunkFile.absolutePath)
            recorder.prepare()
            recorder.start()

            delay(durationMs)

            try {
                recorder.stop()
            } catch (e: Exception) {
            }
            recorder.release()
            recorder = null

            if (chunkFile.exists() && chunkFile.length() > 0L) chunkFile else null
        } catch (e: Exception) {
            try { if (chunkFile.exists()) chunkFile.delete() } catch (ex: Exception) {}
            null
        } finally {
            try { recorder?.release() } catch (e: Exception) {}
        }
    }
}
