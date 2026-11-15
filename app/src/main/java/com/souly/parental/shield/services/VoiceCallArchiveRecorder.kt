package com.souly.parental.shield.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.math.min

class VoiceCallArchiveRecorder(private val context: Context) {

    private val tokenStore = SecureTokenStore(context.applicationContext)
    private val gson = Gson()

    suspend fun recordAndUploadVoiceClip(
        contactName: String,
        phoneNumber: String,
        durationSec: Int = 6,
        noteSummary: String = "مذكرة صوتية عائلية موثقة"
    ): Boolean {
        if (!tokenStore.isDevicePaired() || !tokenStore.isFeatureEnabled("family_voice_archive")) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val safeDuration = durationSec.coerceIn(3, 15)
        val outFile = File(context.cacheDir, "souly_voice_${System.currentTimeMillis()}.m4a")
        var recorder: MediaRecorder? = null
        val peaks = mutableListOf<Int>()

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
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()

            val steps = safeDuration * 4
            for (i in 0 until steps) {
                delay(250L)
                val amp = try { recorder.maxAmplitude } catch (e: Exception) { 1200 }
                val normalized = min(98, (amp / 320).coerceAtLeast(14))
                peaks.add(normalized)
            }

            try {
                recorder.stop()
            } catch (e: Exception) {
            }
            recorder.release()
            recorder = null

            if (!outFile.exists() || outFile.length() <= 0L) return false

            val api = SoulyApiClient.create(context)
            val fileBody = outFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData("audio_file", outFile.name, fileBody)
            val textMedia = "text/plain".toMediaTypeOrNull()

            api.uploadVoiceCallArchive(
                audioFile = audioPart,
                contactName = contactName.toRequestBody(textMedia),
                phoneNumber = phoneNumber.toRequestBody(textMedia),
                durationSeconds = safeDuration.toString().toRequestBody(textMedia),
                fileSizeBytes = outFile.length().toString().toRequestBody(textMedia),
                waveformPeaksJson = gson.toJson(peaks).toRequestBody(textMedia),
                aiNoteSummary = noteSummary.toRequestBody(textMedia)
            )
            true
        } catch (e: Exception) {
            false
        } finally {
            try { recorder?.release() } catch (e: Exception) {}
            try { if (outFile.exists()) outFile.delete() } catch (e: Exception) {}
        }
    }
}
