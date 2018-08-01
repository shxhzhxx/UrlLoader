package com.shxhzhxx.urlloader;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

import com.shxhzhxx.library.UrlLoader;

import java.io.File;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "MainActivity";
    private static final String URL = "https://image.yizhujiao.com/FiZr1lFxhobKLogy4pkTfLqv6xrV";
    private static final String URL_BIG = "http://ovfjn9jer.bkt.clouddn.com/%E3%80%90%E4%B8%AD%E6%96%87%E5%85%AB%E7%BA%A7%E3%80%91%E4%B8%A4%E4%B8%AA%E5%9B%BD%E4%BA%BA%E5%B1%95%E5%BC%80%E4%BA%86%E6%83%8A%E4%BA%BA%E7%9A%84%E8%8B%B1%E8%AF%AD%E5%85%AB%E7%BA%A7%E5%AF%B9%E8%AF%9D_%28Av16874218_P1%29.mp4";

    private TextView mState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DownloadManager.init(this);

        mState = findViewById(R.id.state);
        findViewById(R.id.download).setOnClickListener(this);
        findViewById(R.id.cancel).setOnClickListener(this);
        findViewById(R.id.clear).setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.download:
                DownloadManager.getInstance().load(URL_BIG, new UrlLoader.ProgressObserver() {
                    @Override
                    public void onComplete(File file) {
                        mState.setText("onComplete: " + file.getAbsolutePath());
                    }

                    @Override
                    public void onFailed() {
                        mState.setText("onFailed");
                    }

                    @Override
                    public void onCanceled() {
                        mState.setText("onCanceled");
                    }

                    @Override
                    public void onProgressUpdate(long total, long current, long speed) {
                        mState.setText(String.format(Locale.CHINA, "onProgressUpdate: %.2f%%   %dKB/s", ((double) 100 * current / total), (int) (speed / 1024)));
                    }
                });
                break;
            case R.id.cancel:
                DownloadManager.getInstance().cancel(URL_BIG);
                break;
            case R.id.clear:
                DownloadManager.getInstance().clearCache(URL_BIG);
                break;
        }

    }
}
