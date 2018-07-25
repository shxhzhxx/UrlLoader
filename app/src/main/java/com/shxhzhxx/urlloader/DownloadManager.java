package com.shxhzhxx.urlloader;

import android.content.Context;
import android.support.annotation.NonNull;

import com.shxhzhxx.library.UrlLoader;

public abstract class DownloadManager {
    private static UrlLoader mInstance;

    public static void init(@NonNull Context context) {
        mInstance = new UrlLoader(context.getCacheDir(), 300 * 1024 * 1024, 5);
    }

    public static UrlLoader getInstance() {
        return mInstance;
    }
}
