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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.coroutines.CoroutineContext

const val TAG = "MainActivity"
const val URL_BIG = "https://static.usasishu.com/bigFile.pdf"
const val URL_BIG_VIDEO = "https://static.usasishu.com/bigVideoFile.mp4"
const val URL_MAX_AGE = "http://plpwobkse.bkt.clouddn.com/ic_launcher.png"
const val URL_BIG_IMG = "http://plpwobkse.bkt.clouddn.com/1125-2436-72.png"

class MainActivity : AppCompatActivity(), CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job
    private var bitmap: Bitmap? = null
    lateinit var job: Job
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
        setContentView(R.layout.activity_main)

        download.setOnClickListener {
            val promise = async(start = CoroutineStart.LAZY) {
                coroutineWork()
            }
            val task = async {
                Log.d(TAG,"task:${promise.await()}")
            }
            launch {
                Log.d(TAG,"launch:${promise.await()}")
                delay(2000)
            }
            launch {
                delay(1000)
                task.cancel()
            }
        }

        cancel.setOnClickListener {
        }
        clear.setOnClickListener {
        }
        check.setOnClickListener {
        }
    }
}

suspend fun failedConcurrentSum(): Int = coroutineScope {
    val one = async<Int> {
        try {
            delay(Long.MAX_VALUE) // Emulates very long computation
            42
        } finally {
            println("First child was cancelled")
        }
    }
    val two = async<Int> {
        delay(1000)
        println("Second child throws an exception")
        throw ArithmeticException()
    }
    one.await() + two.await()
}

suspend fun coroutineWork(progress: ((Float) -> Unit)? = null): String? {
    return runBlocking{
        Log.d(TAG, "work start:${Thread.currentThread().id}")
        repeat(10) {
            try {
                delay(500)
            } catch (e: CancellationException) {
                Log.d(TAG, "CancellationException")
                return@runBlocking null
            } finally {
                Log.d(TAG, "finally")
            }
            progress?.invoke(it.toFloat() / 10)
        }
        val result = UUID.randomUUID().toString()
        Log.d(TAG, "work finish:$result")
        return@runBlocking result
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
