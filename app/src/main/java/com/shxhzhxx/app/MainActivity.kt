package com.shxhzhxx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.shxhzhxx.urlloader.UrlLoaderEx
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "MainActivity"
private const val URL_BIG = "https://static.usasishu.com/bigFile.pdf"
private const val URL_BIG_VIDEO = "https://static.usasishu.com/bigVideoFile.mp4"
private const val URL_MAX_AGE = "http://plpwobkse.bkt.clouddn.com/ic_launcher.png"
private const val URL_BIG_IMG = "http://plpwobkse.bkt.clouddn.com/1125-2436-72.png"

class MainActivity : AppCompatActivity(), CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        job.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val loader = UrlLoaderEx(cacheDir)
        val threadPool = Executors.newCachedThreadPool()
        asyncLoad.setOnClickListener {
            loader.asyncLoad(URL_BIG, onComplete = {
                Log.d(TAG, "asyncLoad onComplete:${it.absolutePath}")
            }, onProgress = { total, current, speed ->
                Log.d(TAG, "asyncLoad onProgress: total:$total    current:$current    speed:$speed")
            })
        }
        var canceled = false
        syncLoad.setOnClickListener {
            canceled = false
            val task1 = threadPool.submit {
                Log.d(TAG, "syncLoad1:${loader.syncLoad(URL_BIG, { canceled }) { total, current, speed ->
                    Log.d(TAG, "syncLoad1 onProgress: total:$total    current:$current    speed:$speed")
                }
                }")
            }
            val task2 = threadPool.submit {
                Thread.sleep(1000)
                Log.d(TAG, "syncLoad2:${loader.syncLoad(URL_BIG, { false }) { total, current, speed ->
                    Log.d(TAG, "syncLoad2 onProgress: total:$total    current:$current    speed:$speed")
                }
                }")
            }
            threadPool.submit {
                Thread.sleep(3000)
                canceled = true
                task1.cancel(true)
            }
        }
        cancel.setOnClickListener {
            canceled = true
            loader.cancel(URL_BIG)
        }
        clear.setOnClickListener {
            loader.clearCache()
        }
        check.setOnClickListener {
            Log.d(TAG, "check:${loader.checkDownload(URL_BIG)}")

            Thread {
                Log.d(TAG,"thread start:${Thread.currentThread().id}")
                runBlocking<Unit>(Dispatchers.Main) {
                    Log.d(TAG,"runBlocking start:${Thread.currentThread().id}")
                    val asy=async {
                        Log.d(TAG,"async start:${Thread.currentThread().id}")
                        return@async suspendCoroutine<Boolean> {
                            Log.d(TAG,"suspend start:${Thread.currentThread().id}")
                            Thread{
                                Log.d(TAG,"sub thread start:${Thread.currentThread().id}")
                                Thread.sleep(1000)
                                it.resume(true)
                                Log.d(TAG,"sub thread end:${Thread.currentThread().id}")
                            }.start()
                            launch {
                                Log.d(TAG,"launch start:${Thread.currentThread().id}")
                                delay(5000)
                                Log.d(TAG,"launch end:${Thread.currentThread().id}")
                            }
                            Log.d(TAG,"suspend end:${Thread.currentThread().id}")
                        }.apply {
                            Log.d(TAG,"async end:${Thread.currentThread().id}") }
                    }
                    Log.d(TAG,"asy :${Thread.currentThread().id}")
                    asy.await()
                    Log.d(TAG,"runBlocking end:${Thread.currentThread().id}")
                }
                Log.d(TAG,"thread end:${Thread.currentThread().id}")
            }.start()
        }
    }
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
