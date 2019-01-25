package com.shxhzhxx.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.shxhzhxx.urlloader.UrlLoader
import java.io.File
import java.io.IOException

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

        val url = URL_BIG_IMG
        val loader = UrlLoader(cacheDir)

        var id: Int? = null

        findViewById<View>(R.id.download).setOnClickListener {
            id = loader.load(url,
                    onComplete = { file ->
                        bitmap = file.decodeBitmap(Params("", 300, 300, true))
                        Log.d(TAG, "w:${bitmap?.width}")
                        Log.d(TAG, "h:${bitmap?.height}")
                    },
                    onProgress = { total, current, speed ->
                        Log.d(TAG, "onProgress:$total   $current   $speed")
                    },
                    onCanceled = {
                        Log.d(TAG, "onCanceled")
                    },
                    onFailed = {
                        Log.d(TAG, "onFailed")
                    }
            )
        }
        findViewById<Button>(R.id.cancel).setOnClickListener {
            id?.let { loader.unregister(it) }
        }
        findViewById<Button>(R.id.clear).setOnClickListener {
            loader.clearCache()
        }
        findViewById<Button>(R.id.check).setOnClickListener {
            Log.d(TAG, "check:${loader.checkDownload(url)}")
        }
    }

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
}

data class Params(val path: String, val width: Int, val height: Int, val centerCrop: Boolean)
