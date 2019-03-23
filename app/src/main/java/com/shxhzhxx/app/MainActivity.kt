package com.shxhzhxx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.shxhzhxx.urlloader.UrlLoader
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.coroutines.CoroutineContext

private const val TAG = "MainActivity"
private const val URL_BIG = "https://static.usasishu.com/bigFile.pdf"
private const val URL_BIG_VIDEO = "https://static.usasishu.com/bigVideoFile.mp4"
private const val URL_MAX_AGE = "http://plpwobkse.bkt.clouddn.com/ic_launcher.png"
private const val URL_BIG_IMG = "http://plpwobkse.bkt.clouddn.com/1125-2436-72.png"
private const val URL_IMG = "https://static.usasishu.com/image/2018/09/30/bg-index.jpg"
private const val URL_IMG_TINY = "https://static.usasishu.com/image/2018/09/29/img-grew-freely.png"

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

        val loader = UrlLoader(cacheDir)
        val threadPool = Executors.newCachedThreadPool()
        asyncLoad.setOnClickListener {
            loader.asyncLoad(URL_IMG, onComplete = {
                Log.d(TAG, "asyncLoad onComplete:${it.absolutePath}")
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setImageBitmap(it.decodeBitmap(Params(it.absolutePath, iv.width, iv.height, true)))
            }, onProgress = { total, current, speed ->
                Log.d(TAG, "asyncLoad onProgress: total:$total    current:$current    speed:$speed")
            }, onCanceled = {
                Log.d(TAG, "asyncLoad onCanceled")
            }, onFailed = {
                Log.d(TAG, "asyncLoad onFailed")
            })
        }
        var canceled = false
        var task1: Future<*>? = null
        syncLoad.setOnClickListener {
            canceled = false
            task1 = threadPool.submit {
                Log.d(TAG, "syncLoad1:${loader.syncLoad(URL_BIG, { canceled }) { total, current, speed ->
                    Log.d(TAG, "syncLoad1 onProgress: total:$total    current:$current    speed:$speed")
                }
                }")
            }
        }
        cancel.setOnClickListener {
            Log.d(TAG, "cancel")
            loader.cancel(URL_BIG)
        }
        clear.setOnClickListener {
            loader.clearCache()
        }
        check.setOnClickListener {
            Log.d(TAG, "check:${loader.checkDownload(URL_BIG)}")
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
            .run { return@run if (centerCrop) minBy { it.first.toFloat() / it.second } else maxBy { it.first.toFloat() / it.second } }!!

    Log.d(TAG,"width:$width")
    Log.d(TAG,"height:$height")
    Log.d(TAG,"outWidth:${opts.outWidth}")
    Log.d(TAG,"outHeight:${opts.outHeight}")
    opts.inSampleSize = out / dst
    opts.inDensity = out
    opts.inTargetDensity = dst
    opts.inScaled = true
    opts.inJustDecodeBounds = false

    return if (!centerCrop) BitmapFactory.decodeFile(absolutePath, opts) else
        try {
            val inSampleSize = out.toFloat() / dst
            val bitmap = BitmapRegionDecoder.newInstance(absolutePath, !canWrite()).decodeRegion(Rect(
                    (opts.outWidth / 2 - width * inSampleSize / 2).toInt(),
                    (opts.outHeight / 2 - height * inSampleSize / 2).toInt(),
                    (opts.outWidth / 2 + width * inSampleSize / 2).toInt(),
                    (opts.outHeight / 2 + height * inSampleSize / 2).toInt()), opts) ?: return null
            Log.d(TAG,"raw height:${bitmap.height}")
            Log.d(TAG,"raw width:${bitmap.width}")
            Bitmap.createScaledBitmap(bitmap, height, width, true).also {
                Log.d(TAG,"bitmap.height:${it.height}")
                Log.d(TAG,"bitmap.width:${it.width}")
            }
        } catch (e: IOException) {
            null
        }
}
