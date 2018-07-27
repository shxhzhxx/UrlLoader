package com.shxhzhxx.library;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;

import java.io.File;

public class UrlLoaderCache extends DiskLruCache {
    private final String mSuffixData = "6aef4_d";
    private final String mSuffixHeader = "6aef4_h";

    public UrlLoaderCache(@NonNull File cachePath, @IntRange(from = 1) int maxSize) {
        super(cachePath, maxSize);
    }

    @Override
    public boolean accept(File dir, @NonNull String name) {
        return name.endsWith("." + mSuffixData);
    }

    @Override
    protected int sizeOf(@NonNull File file) {
        return (int) (file.length() + findHeaderCache(file).length());
    }

    public File getHeaderCache(String url) {
        return getFile(url, mSuffixHeader);
    }

    public File getDataCache(String url) {
        return getFile(url, mSuffixData);
    }

    private File findHeaderCache(File dataCache) {
        String absolutePath = dataCache.getAbsolutePath();
        return new File(absolutePath.substring(0, absolutePath.length() - mSuffixData.length()) + mSuffixHeader);
    }

    @Override
    protected void entryRemoved(boolean evicted, String key, Info oldValue, Info newValue) {
        if (evicted) {
            findHeaderCache(oldValue.file).delete();
            oldValue.file.delete();
        }
    }
}
