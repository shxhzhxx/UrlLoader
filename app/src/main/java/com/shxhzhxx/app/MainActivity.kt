package com.shxhzhxx.app

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.shxhzhxx.urlloader.UrlLoader

const val TAG = "MainActivity"
const val URL_BIG = "https://static.usasishu.com/bigFile.pdf"
const val URL_BIG_VIDEO = "https://static.usasishu.com/bigVideoFile.mp4"
const val URL_MAX_AGE = "http://plpwobkse.bkt.clouddn.com/ic_launcher.png"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val url = URL_BIG_VIDEO
        val loader = UrlLoader(cacheDir)

        var id: Int? = null
        findViewById<Button>(R.id.download).setOnClickListener {
            id = loader.load(url,
                    onComplete = { file ->
                        Log.d(TAG, "onComplete:${file.absolutePath}")
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
}