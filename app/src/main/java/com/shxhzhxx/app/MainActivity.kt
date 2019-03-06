package com.shxhzhxx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.shxhzhxx.urlloader.TaskManagerEx
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.coroutines.CoroutineContext

const val TAG = "MainActivity"
const val URL_BIG = "https://static.usasishu.com/bigFile.pdf"
const val URL_BIG_VIDEO = "https://static.usasishu.com/bigVideoFile.mp4"
const val URL_MAX_AGE = "http://plpwobkse.bkt.clouddn.com/ic_launcher.png"
const val URL_BIG_IMG = "http://plpwobkse.bkt.clouddn.com/1125-2436-72.png"

class MainActivity : AppCompatActivity(), CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        job.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val manager = MyTaskManager()
        val threadPool = Executors.newCachedThreadPool()
        download.setOnClickListener {
            val key = Any()
            var canceled = false
            val task1 = threadPool.submit {
                Log.d(TAG, "task1:${Thread.currentThread().id}")
                Log.d(TAG, "task1:${manager.syncLoad(key) { canceled }}")
            }
            val task2 = threadPool.submit {
                Log.d(TAG, "task2:${Thread.currentThread().id}")
                Thread.sleep(300)
                Log.d(TAG, "task2:${manager.syncLoad(key) { false }}")
            }
            Thread.sleep(500)
            val id = manager.asyncLoad(key)
            threadPool.submit {
                Thread.sleep(2000)
                canceled = true
                task1.cancel(true)
                Thread.sleep(1000)
                runOnUiThread {
                    manager.unregister(id)
                }
            }
        }
        cancel.setOnClickListener {
            val future = FutureTask {
                Log.d(TAG, "future:${Thread.currentThread().id}")
                try {
                    val client = OkHttpClient.Builder().build()
                    val response = client.loadUrl(URL_BIG)
                    val inputStream = response?.body()?.byteStream()
                    if (inputStream != null) {
                        val buff = ByteArray(50 * 1024)
                        while (true) {
                            val n = inputStream.read(buff, 0, 50 * 1024)
                            Log.d(TAG, "read:$n")
                            if (n <= 0)
                                break
                        }
                    }
                }catch (e:InterruptedIOException){
                    throw InterruptedException(e.message)
                } catch (e: Throwable) {
                    Log.d(TAG, "future exception:${e.javaClass}")
                }
                return@FutureTask "asdfasdf"
            }
            val task1 = threadPool.submit {
                try {
                    future.run()
                    Log.d(TAG, "task1:${future.get()}")
                } catch (e: Throwable) {
                    Log.d(TAG, "task1:${e.javaClass}")
                }
            }
            threadPool.submit {
                Thread.sleep(1000)
                task1.cancel(true)
                try {
                    future.run()
                    Log.d(TAG, "task2:${future.get()}")
                } catch (e: Throwable) {
                    Log.d(TAG, "task2:${e.javaClass}")
                }
            }
        }
        clear.setOnClickListener {
        }
        check.setOnClickListener {
        }
    }
}

private fun OkHttpClient.loadUrl(url: String, converter: ((Request.Builder) -> Request.Builder) = { it }): Response? {
    cache()?.evictAll()
    val request = try {
        Request.Builder().url(url)
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "\"$url\" is not a valid HTTP or HTTPS URL")
        return null
    }
    return try {
        newCall(converter.invoke(request).build()).execute()
    } catch (e: InterruptedIOException) {
        throw InterruptedException(e.message)
    } catch (e: IOException) {
        null
    }
}

fun work(progress: ((Float) -> Unit)? = null): String? {
    Log.d(TAG, "work start:${Thread.currentThread().id}")
    repeat(10) {
        Thread.sleep(500)
        progress?.invoke(it.toFloat() / 10)
    }
    val result = UUID.randomUUID().toString()
    Log.d(TAG, "work finish:$result")
    return result
}

data class Params(val path: String, val width: Int, val height: Int, val centerCrop: Boolean)

private fun File.decodeBitmap(params: Params): Bitmap? {
    val centerCrop = params.centerCrop && params.height > 0 && params.width > 0
    if (params.height <= 0 && params.width <= 0) {
        Log.e(TAG, "load bitmap without compress :$absolutePath")
    }
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, opts)
    val (height, width) = listOf(params.height, params.width).map { return@map if (it <= 0) Int.MAX_VALUE else it }
    val (out, dst) = listOf(opts.outHeight to height, opts.outWidth to width)
            .run { return@run if (centerCrop) minBy { it.first / it.second } else maxBy { it.first / it.second } }!!
    opts.inSampleSize = out / dst
    opts.inDensity = out
    opts.inTargetDensity = dst * opts.inSampleSize
    opts.inScaled = true
    opts.inJustDecodeBounds = false

    return if (!centerCrop) BitmapFactory.decodeFile(absolutePath, opts) else
        try {
            BitmapRegionDecoder.newInstance(absolutePath, !canWrite()).decodeRegion(Rect(
                    opts.outWidth / 2 - width * opts.inSampleSize / 2,
                    opts.outHeight / 2 - height * opts.inSampleSize / 2,
                    opts.outWidth / 2 + width * opts.inSampleSize / 2,
                    opts.outHeight / 2 + height * opts.inSampleSize / 2), opts)
        } catch (e: IOException) {
            null
        }
}


class MyCallback(
        val onComplete: ((String) -> Unit)? = null,
        val onFailed: (() -> Unit)? = null,
        val onCanceled: (() -> Unit)? = null,
        val onProgress: ((progress: Float) -> Unit)? = null
)


class MyTaskManager(maxPoolSize: Int = 10) : TaskManagerEx<MyCallback, String>(maxPoolSize) {
    fun syncLoad(key: Any, canceled: () -> Boolean): String? {
        return syncStart(key, { MyTask(key) }, canceled, observer = MyCallback(onProgress = {
            Log.d(TAG, "syncLoad onProgress:$it")
        }))
    }

    fun asyncLoad(key: Any): Int {
        return asyncStart(key, { MyTask(key) }, observer = MyCallback(onComplete = {
            Log.d(TAG, "asyncLoad onComplete:$it")
        }, onProgress = {
            Log.d(TAG, "asyncLoad onProgress:$it")
        }, onFailed = {
            Log.d(TAG, "asyncLoad onFailed")
        }, onCanceled = {
            Log.d(TAG, "asyncLoad onCanceled")
        }))
    }

    inner class MyTask(key: Any) : Task(key) {
        override fun doInBackground(): String? {
            return work { progress ->
                observers.forEach { it?.onProgress?.invoke(progress) }
            }.apply {
                postResult = if (this != null) Runnable {
                    observers.forEach { it?.onComplete?.invoke(this) }
                } else Runnable {
                    observers.forEach { it?.onFailed }
                }
            }
        }
    }
}
