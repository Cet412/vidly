package com.vidly.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.vidly.app.BuildConfig
import com.vidly.app.data.api.ApiClient
import com.vidly.app.data.local.HistoryEntity
import com.vidly.app.data.manager.DownloadManager
import com.vidly.app.data.repository.HistoryRepository
import com.vidly.app.notification.NotificationHelper
import com.vidly.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface DownloadResult {
    data class Success(val uri: Uri, val bytes: Long) : DownloadResult
    data class Failure(val error: DownloadError) : DownloadResult
}

class DownloadRepository {

    suspend fun downloadVideo(context: Context, url: String): DownloadResult = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.d(TAG, "mulai download url=$url")
        val notif = NotificationHelper(context)
        val downloadManager = DownloadManager.getInstance()
        val downloadId = downloadManager.addDownload(url, url)

        if (!NetworkUtils.isOnline(context)) {
            notif.showErrorNotification("Tidak ada koneksi", "Periksa koneksi internet Anda.")
            downloadManager.markFailed(downloadId, "Offline")
            return@withContext DownloadResult.Failure(DownloadError.Network("offline"))
        }

        notif.showProgressNotification("Mengunduh...", 0)

        var lastError: DownloadError = DownloadError.Unknown("unknown")
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++
            Log.d(TAG, "percobaan $attempt/$maxAttempts")

            val result = try {
                fetch(context, url, notif, downloadId, downloadManager)
            } catch (e: UnknownHostException) {
                Log.e(TAG, "host tidak ditemukan", e)
                DownloadResult.Failure(DownloadError.Network(e.message ?: "unknown host"))
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "timeout", e)
                DownloadResult.Failure(DownloadError.Timeout(e.message ?: "timeout"))
            } catch (e: IOException) {
                Log.e(TAG, "io error", e)
                DownloadResult.Failure(DownloadError.Network(e.message ?: "io error"))
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error", e)
                DownloadResult.Failure(DownloadError.Unknown(e.message ?: "unknown"))
            }

            when (result) {
                is DownloadResult.Success -> {
                    notif.cancelNotification()
                    downloadManager.markCompleted(downloadId)
                    return@withContext result
                }
                is DownloadResult.Failure -> {
                    lastError = result.error
                    val shouldRetry = when (result.error) {
                        is DownloadError.Network -> true
                        is DownloadError.Timeout -> true
                        is DownloadError.Backend -> result.error.code in 500..599
                        else -> false
                    }

                    if (shouldRetry && attempt < maxAttempts) {
                        val delayMs = (1000L * (1 shl (attempt - 1))) // 1s, 2s, 4s
                        Log.d(TAG, "retry dalam ${delayMs}ms")
                        delay(delayMs)
                        continue
                    }

                    // Tidak retry — tampilkan error
                    notif.showErrorNotification("Unduhan gagal", result.error.userMessage)
                    notif.cancelNotification()
                    return@withContext result
                }
            }
        }

        // Semua percobaan gagal
        notif.showErrorNotification("Unduhan gagal", lastError.userMessage)
        notif.cancelNotification()
        downloadManager.markFailed(downloadId, lastError.userMessage)
        return@withContext DownloadResult.Failure(lastError)
    }

    private suspend fun fetch(context: Context, url: String, notif: NotificationHelper, downloadId: String, downloadManager: DownloadManager): DownloadResult {
        val endpoint = "${BuildConfig.API_BASE_URL.trimEnd('/')}/api/v1/download"
        val payload = JSONObject().put("url", url).toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "POST $endpoint")
        ApiClient.httpClient.newCall(request).execute().use { response ->
            if (BuildConfig.DEBUG) Log.d(TAG, "response code=${response.code} contentType=${response.header("Content-Type")} length=${response.header("Content-Length")}")

            if (response.code == 429) {
                val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 3600
                Log.w(TAG, "rate limited, retry after $retryAfter s")
                return DownloadResult.Failure(DownloadError.RateLimited(retryAfter))
            }

            if (!response.isSuccessful) {
                val body = response.body?.string()?.take(300).orEmpty()
                Log.w(TAG, "backend menolak: ${response.code} $body")
                return DownloadResult.Failure(DownloadError.Backend(response.code, body))
            }

            val body = response.body
            if (body == null) {
                Log.e(TAG, "response body null")
                return DownloadResult.Failure(DownloadError.Unknown("Response kosong"))
            }

            val displayName = fileName(response.header("Content-Disposition"))
            val mimeType = guessMimeType(displayName, response.header("Content-Type"))
            val isImage = mimeType.startsWith("image/")
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            Log.d(TAG, "nama file=$displayName size=$contentLength mime=$mimeType isImage=$isImage")

            val uri = createMediaStoreEntry(context, displayName, mimeType, isImage)
                ?: return DownloadResult.Failure(DownloadError.Storage("Gagal membuat entri MediaStore"))

            val written = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    var totalWritten = 0L
                    val buffer = ByteArray(8192)
                    val input = body.byteStream()
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalWritten += bytesRead
                        val progressPercent = (totalWritten * 100 / maxOf(contentLength, 1L)).toInt()
                        notif.showProgressNotification("Mengunduh: ${displayName.take(30)}", progressPercent)
                        downloadManager.updateProgress(downloadId, totalWritten, contentLength)
                    }
                    totalWritten
                }
            }.getOrElse { e ->
                Log.e(TAG, "gagal menulis ke MediaStore, hapus entri", e)
                context.contentResolver.delete(uri, null, null)
                return DownloadResult.Failure(DownloadError.Storage(e.message ?: "storage error"))
            }

            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            Log.d(TAG, "selesai: $written byte -> $uri")
            notif.showCompletionNotification("Unduhan selesai!", displayName)
            notif.cancelNotification()

            // Simpan ke history DB
            try {
                HistoryRepository(context).insert(
                    HistoryEntity(
                        url = url,
                        title = displayName,
                        platform = when {
    url.contains("instagram", ignoreCase = true) -> "Instagram"
    url.contains("tiktok", ignoreCase = true) -> "TikTok"
    url.contains("youtube", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true) -> "YouTube"
    else -> "Unknown"
},
                        filePath = uri.toString(),
                        fileSize = written,
                        mimeType = mimeType
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "gagal simpan history", e)
            }

            return DownloadResult.Success(uri, written)
        }
    }

    private fun createMediaStoreEntry(context: Context, displayName: String, mimeType: String, isImage: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val baseDir = if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/Vidly")
            }
        }

        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val uri = context.contentResolver.insert(collection, values)
        if (uri == null) {
            Log.e(TAG, "insert MediaStore mengembalikan null")
        }
        return uri
    }

    internal fun fileName(contentDisposition: String?): String {
        val fromHeader = contentDisposition
            ?.substringAfter("filename=\"", "")
            ?.substringBefore("\"")
            ?.takeIf { it.isNotBlank() }
        return if (fromHeader != null) "Vidly_$fromHeader" else "Vidly_${System.currentTimeMillis()}.mp4"
    }

    private fun guessMimeType(filename: String, contentType: String?): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "VidlyDownload"

        fun extractPlatform(url: String): String = when {
            url.contains("instagram", ignoreCase = true) -> "Instagram"
            url.contains("tiktok", ignoreCase = true) -> "TikTok"
            url.contains("youtube", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true) -> "YouTube"
            else -> "Unknown"
        }
    }
}
