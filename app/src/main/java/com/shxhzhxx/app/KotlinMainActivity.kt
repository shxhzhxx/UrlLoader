package com.shxhzhxx.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shxhzhxx.urlloader.UrlLoaderEx

class KotlinMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val loader = UrlLoaderEx(cacheDir, 10 * 1024)
        loader.load("",
                onComplete = { file ->

                },
                onProgressUpdate = { total, current, speed ->

                },
                onCanceled = {

                }
        )
    }
}