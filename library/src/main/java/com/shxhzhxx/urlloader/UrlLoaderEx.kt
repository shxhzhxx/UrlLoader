package com.shxhzhxx.urlloader

import androidx.annotation.IntRange
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit


private const val UPDATE_TIME_INTERVAL = 500
private const val MAX_BUF_SIZE = 8192
private const val MIN_BUF_SIZE = 512

class Callback(
        val onComplete: ((File) -> Unit)? = null,
        val onFailed: (() -> Unit)? = null,
        val onCanceled: (() -> Unit)? = null,
        val onProgressUpdate: ((total: Long, current: Long, speed: Long) -> Unit)? = null
)

class UrlLoaderEx(cachePath: File, @IntRange(from = 1) maxCacheSize: Int, maxPoolSize: Int = 4) : TaskManager<Callback>(maxPoolSize) {
    private val cache = UrlLoaderCache(cachePath, maxCacheSize)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS).build()

    fun load(url: String, tag: String? = null,
             onComplete: ((File) -> Unit)? = null,
             onFailed: (() -> Unit)? = null,
             onCanceled: (() -> Unit)? = null,
             onProgressUpdate: ((total: Long, current: Long, speed: Long) -> Unit)? = null
    ): Int {
        val callback = Callback(onComplete, onFailed, onCanceled, onProgressUpdate)
        return start(url, { WorkThread(url) }, tag, callback).also { id ->
            if (id < 0) {
                callback.onFailed?.invoke()
            }
        }
    }

    private fun syncLoad(url: String): File {
        val headCache = cache.getHeaderCache(url)
        val dataCache = cache.getDataCache(url)

        return File("")
    }

    private fun resetCache(): Boolean {
        return false
    }

    private fun download(): Boolean {
        return false
    }

    private fun resumeDownload(): Boolean {
        return false
    }

    private fun readResponse(): Boolean {
        return false
    }

    private fun readBody(): Boolean {
        return false
    }

    internal inner class WorkThread(private val url: String) : Task(url) {
        override fun doInBackground() {

        }
    }
}