package com.shxhzhxx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.shxhzhxx.urlloader.UrlLoader
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.*

const val TAG = "MainActivity"
const val URL_BIG = "https://static.usasishu.com/bigFile.pdf"
const val URL_BIG_VIDEO = "https://static.usasishu.com/bigVideoFile.mp4"
const val URL_MAX_AGE = "http://plpwobkse.bkt.clouddn.com/ic_launcher.png"
const val URL_BIG_IMG = "http://plpwobkse.bkt.clouddn.com/1125-2436-72.png"

class MainActivity : AppCompatActivity() {
    private var bitmap: Bitmap? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val url = URL_BIG
        val loader = UrlLoader(cacheDir)

        download.setOnClickListener {
            Thread {
                val task  = FutureTask<String> {work()}

                val threadPool = Executors.newCachedThreadPool()
                val f = threadPool.submit {
                    //某同步任务，1s后取消
                    try {
                        task.run()
                    } catch (e: Throwable) {
                        Log.d(TAG, "run exception:${e.message}")
                        Log.d(TAG, "isCancelled 1:${task.isCancelled}")
                        Log.d(TAG, "isDone 1:${task.isDone}")
                    }
                    try {
                        Log.d(TAG, "get 1:${task.get()}")
                        Log.d(TAG, "future.isCancelled:${task.isCancelled}")
                    } catch (e: Exception) {
                        Log.d(TAG, "get 1 exception:${e.message}")
                        Log.d(TAG, "isCancelled 1:${task.isCancelled}")
                        Log.d(TAG, "isDone 1:${task.isDone}")
                    }
                }

                Thread.sleep(1000)
                Thread {
                    //另一个监听者，需要在任务被取消后重启任务
                    try {
                        task.run()
                        Log.d(TAG,"run")
                        Log.d(TAG, "get 2:${task.get()}")
                        Log.d(TAG,"get 3:${task.get()}")
                    } catch (e: Exception) {
                        Log.d(TAG, "get 2 exception:${e.message}")
                        Log.d(TAG, "isCancelled 2:${task.isCancelled}")
                        Log.d(TAG, "isDone 2:${task.isDone}")
                    }
                }.start()
                Thread.sleep(1000)
                f.cancel(true)
            }.start()
        }
        cancel.setOnClickListener {
        }
        clear.setOnClickListener {
        }
        check.setOnClickListener {
        }
    }

    private fun work(): String? {
        Log.d(TAG, "work start:${Thread.currentThread().id}")

        Thread.sleep(5000)
        val result = UUID.randomUUID().toString()
        Log.d(TAG, "work finish:$result")
        return result
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
