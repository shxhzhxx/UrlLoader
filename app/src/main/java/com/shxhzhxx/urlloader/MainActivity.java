package com.shxhzhxx.urlloader;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Context mContext = null;
        if (Math.random() > 0.5) {
            mContext = this;
        }
        DownloadManager.init(mContext);
        findViewById(R.id.download).setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        DownloadManager.getInstance().load("https://image.yizhujiao.com/FiZr1lFxhobKLogy4pkTfLqv6xrV");
    }
}
