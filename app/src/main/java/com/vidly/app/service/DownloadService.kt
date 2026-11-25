package com.vidly.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.vidly.app.BuildConfig
import com.vidly.app.data.repository.DownloadRepository
import com.vidly.app.data.repository.DownloadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val repo = DownloadRepository()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "DownloadService started tanpa URL")
            stopSelf()
            return START_NOT_STICKY
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "mulai download dari service: $url")
        scope.launch(Dispatchers.IO) {
            val result = repo.downloadVideo(applicationContext, url)
            when (result) {
                is DownloadResult.Success -> Log.d(TAG, "download sukses: ${result.bytes} byte")
                is DownloadResult.Failure -> Log.e(TAG, "download gagal: ${result.error.userMessage}")
            }
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "VidlyDownloadService"
        const val EXTRA_URL = "download_url"
    }
}
