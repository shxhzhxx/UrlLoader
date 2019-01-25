package com.shxhzhxx.urlloader

import android.os.Build
import android.util.Log
import androidx.annotation.IntRange
import androidx.annotation.RequiresApi
import okhttp3.*
import java.io.*
import java.util.concurrent.TimeUnit


private const val TAG = "UrlLoader"
private const val MAX_BUF_SIZE = 50 * 1024
private const val MIN_BUF_SIZE = 512
private const val LAST_CHECKED = "UrlLoader-Last-Checked"

class Callback(
        val onComplete: ((File) -> Unit)? = null,
        val onFailed: (() -> Unit)? = null,
        val onCanceled: (() -> Unit)? = null,
        val onProgress: ((total: Long, current: Long, speed: Long) -> Unit)? = null
)


class UrlLoader(cachePath: File, @IntRange(from = 1) maxCacheSize: Int = 100 * 1024 * 1024) : TaskManager<Callback>() {
    private val cache = UrlLoaderCache(cachePath, maxCacheSize).apply { prepare() }
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS).build()

    @JvmOverloads
    fun load(url: String, tag: Any? = null,
             onComplete: ((File) -> Unit)? = null,
             onFailed: (() -> Unit)? = null,
             onCanceled: (() -> Unit)? = null,
             onProgress: ((total: Long, current: Long, speed: Long) -> Unit)? = null
    ) = start(url, { Worker(url) }, tag, Callback(onComplete, onFailed, onCanceled, onProgress)).also { id ->
        if (id < 0) {
            onFailed?.invoke()
        }
    }


    /**
     * @param checkAge if true, check expire.
     * @return true if download is complete and result still valid
     * */
    fun checkDownload(url: String, checkAge: Boolean = true): Boolean {
        if (isRunning(url))
            return false
        val headerCache = getHeaderCache(url)
        val dataCache = getDataCache(url)
        if (!headerCache.exists() || !dataCache.exists())
            return false
        val headers = headerCache.readHeaders() ?: return false
        try {
            if (headers["Content-Length"]?.toLong() != dataCache.length())
                return false
            return !checkAge || headers.isFresh()
        } catch (e: NumberFormatException) {
            return false
        }
    }

    fun clearCache() {
        cache.evictAll()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun resizeCache(@IntRange(from = 1) size: Int) {
        cache.resize(size)
    }

    val cacheSize get() = cache.size()
    fun clearCache(url: String) = !isRunning(url) && cache.clearCache(url)
    fun getHeaderCache(url: String) = cache.getHeaderCache(url)
    fun getDataCache(url: String) = cache.getDataCache(url)
    fun md5(raw: String) = cache.md5(raw)


    /**
     * https://www.rfc-editor.org/rfc/rfc2068.txt
     * https://developer.mozilla.org/en-US/docs/Web/HTTP/Conditional_requests
     */
    fun syncLoad(url: String, progress: ((total: Long, current: Long, speed: Long) -> Unit)? = null): File? {
        val headerCache = cache.getHeaderCache(url)
        val dataCache = cache.getDataCache(url)
        fun resetCache() =
                try {
                    (!headerCache.exists() || headerCache.delete()) && headerCache.createNewFile() &&
                            (!dataCache.exists() || dataCache.delete()) && dataCache.createNewFile()
                } catch (e: IOException) {
                    Log.e(TAG, "resetCache failed, IOException: ${e.message}")
                    false
                }

        fun readBody(body: ResponseBody, @IntRange(from = 0) initLen: Long = 0): File? {
            val contentLen = body.contentLength()
            val totalLen = if (contentLen == -1L) -1 else contentLen + initLen

            val buffLen: Int = arrayOf(MIN_BUF_SIZE, MAX_BUF_SIZE, contentLen.toInt() / 20).sortedArray()[1]
            val buff = ByteArray(buffLen)
            val inputStream = body.byteStream()
            val fos = try {
                FileOutputStream(dataCache, true)
            } catch (e: Exception) {
                return null
            }

            return try {
                var time = System.currentTimeMillis()
                var currentLen = initLen
                var delta = 0
                var t = time
                while (true) {
                    val n = inputStream.read(buff, 0, buffLen)
                    if (n <= 0)
                        break
                    fos.write(buff, 0, n)
                    if (progress == null)
                        continue
                    delta += n
                    t = System.currentTimeMillis()
                    if (t - time > 100) {
                        currentLen += delta
                        progress.invoke(totalLen, currentLen, delta * 1000 / (t - time))
                        time = t
                        delta = 0
                    }
                }
                progress?.invoke(totalLen, currentLen + delta, delta * 1000 / Math.max(t - time, 1))
                dataCache
            } catch (e: IOException) {
                Log.e(TAG, "read IOException: ${e.message}")
                null
            } finally {
                fos.close()
                body.close()
            }
        }

        fun readResponse(response: Response) =
                if (headerCache.writeHeaders(response.headers())) {
                    readBody(response.body()!!)
                } else {
                    response.body()!!.close()
                    null
                }

        fun download(): File? {
            if (!resetCache())
                return null
            val response = client.loadUrl(url) ?: return null
            return if (response.isSuccessful) {
                readResponse(response)
            } else {
                Log.e(TAG, "HTTP status code: ${response.code()}")
                response.body()!!.close() //according to api doc, body shall never be null.
                null
            }
        }

        if (!headerCache.exists() || !dataCache.exists())
            return download()
        val headers = headerCache.readHeaders() ?: return download()

        fun resumeDownload(): File? {
            if (headers["Accept-Ranges"] != "bytes") //server does not support resume download of OCTET unit.
                return download()
            val validator = headers["ETag"] ?: headers["Last-Modified"] ?: return download()
            val initLen = dataCache.length()
            val response = client.loadUrl(url) { builder ->
                builder.addHeader("Range", "bytes=$initLen-")
                builder.addHeader("If-Range", validator)
                return@loadUrl builder
            } ?: return null
            return when {
                response.code() == 206 /*Partial Content*/ -> {
                    headerCache.writeHeaders(headers.merge(response.headers()))
                    readBody(response.body()!!, initLen)
                }
                response.isSuccessful -> if (resetCache()) readResponse(response) else null
                else -> {
                    Log.e(TAG, "HTTP status code: ${response.code()}")
                    response.close()
                    null
                }
            }
        }

        try {
            val contentLength = headers["Content-Length"]?.toLong() ?: return download()
            if (dataCache.length() > contentLength) //irregular circumstance
                return download()
            if (dataCache.length() < contentLength)
                return resumeDownload()
        } catch (e: NumberFormatException) {
            return download()
        }

        if (headers.isFresh()) {
            return dataCache
        }
        val validatorPair = headers["ETag"]?.let { "If-None-Match" to it }
                ?: headers["Last-Modified"]?.let { "If-Modified-Since" to it } ?: return download()
        val response = client.loadUrl(url) { builder ->
            builder.header(validatorPair.first, validatorPair.second)
            return@loadUrl builder
        } ?: return null
        return when {
            response.code() == 304/*not modified*/ -> {
                headerCache.writeHeaders(headers.merge(response.headers()))
                response.close()
                dataCache
            }
            response.isSuccessful -> if (resetCache()) readResponse(response) else null
            else -> {
                Log.e(TAG, "HTTP status code: ${response.code()}")
                response.close()
                null
            }
        }
    }


    internal inner class Worker(private val url: String) : Task(url) {
        override fun doInBackground() {
            if (isCanceled)
                return
            val file = syncLoad(url) { total, current, speed ->
                handler.post {
                    if (!isCanceled)
                        observers.forEach { it?.onProgress?.invoke(total, current, speed) }
                }
            }
            postResult = if (file != null && (!file.canWrite() || file.setWritable(false, false))) {
                Runnable {
                    observers.forEach { it?.onComplete?.invoke(file) }
                }
            } else {
                Runnable {
                    observers.forEach { it?.onFailed?.invoke() }
                }
            }
        }

        override fun onCanceled() {
            observers.forEach { it?.onCanceled?.invoke() }
        }

        override fun onObserverUnregistered(observer: Callback?) {
            observer?.onCanceled?.invoke()
        }
    }
}


private fun OkHttpClient.loadUrl(url: String, converter: ((Request.Builder) -> Request.Builder) = { it }): Response? {
    val request = try {
        Request.Builder().url(url)
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "\"$url\" is not a valid HTTP or HTTPS URL")
        return null
    }
    return try {
        newCall(converter.invoke(request).build()).execute()
    } catch (e: IOException) {
        Log.e(TAG, "execute IOException: ${e.message}")
        null
    }
}

private fun Headers.validator(): String? {
    return get("ETag") ?: get("Last-Modified")
}

private fun Headers.updateLastChecked(): Headers = newBuilder().set(LAST_CHECKED, (System.currentTimeMillis() / 1000).toString()).build()
private fun Headers.merge(headers: Headers): Headers = newBuilder().apply {
    for (name in listOf("Cache-Control", "ETag", "Last-Modified")) {
        headers[name]?.let { set(name, it) }
    }
}.build()

private fun Headers.isFresh(): Boolean {
    val lastChecked = get(LAST_CHECKED)?.toLong() ?: return false
    return System.currentTimeMillis() / 1000 < lastChecked + CacheControl.parse(this).maxAgeSeconds()
}

private fun File.writeHeaders(headers: Headers) =
        try {
            FileOutputStream(this).write(headers.updateLastChecked().toString().toByteArray())
            true
        } catch (e: IOException) {
            false
        }

private fun File.readHeaders(): Headers? {
    val reader = try {
        BufferedReader(FileReader(this))
    } catch (e: FileNotFoundException) {
        return null
    }
    val builder = Headers.Builder()
    return try {
        while (true) {
            val line = reader.readLine() ?: break
            try {
                builder.add(line)
            } catch (ignore: IllegalArgumentException) {
                //skip headers with format exception
            }
        }
        builder.build()
    } catch (e: IOException) {
        null
    } finally {
        reader.close()
    }
}