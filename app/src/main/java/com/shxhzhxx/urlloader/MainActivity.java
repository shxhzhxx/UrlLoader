package com.shxhzhxx.urlloader;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import com.shxhzhxx.library.UrlLoader;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        UrlLoader.init(getCacheDir());
        UrlLoader.load("https://image.yizhujiao.com/FiZr1lFxhobKLogy4pkTfLqv6xrV", new UrlLoader.ProgressObserver() {
            @Override
            public void onComplete(File file) {
                Log.d(TAG, "onComplete");
            }

            @Override
            public void onFailed() {
                Log.d(TAG, "onFailed");
            }

            @Override
            public void onCanceled() {
                Log.d(TAG, "onCanceled");
            }
        });
    }
}
