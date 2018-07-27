package com.shxhzhxx.urlloader;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DownloadManager.init(this);
        findViewById(R.id.download).setOnClickListener(this);

        List<Integer> list = new ArrayList<>();
        list.add(1);
        list.add(2);
        list.add(3);
        list.add(list.remove(1));
        list.add(list.remove(0));
        list.add(4);
        list.add(5);
        Log.d(TAG, "list: " + list);
    }

    @Override
    public void onClick(View view) {
        DownloadManager.getInstance().load("https://image.yizhujiao.com/FiZr1lFxhobKLogy4pkTfLqv6xrV");
    }
}
