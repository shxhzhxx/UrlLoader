package com.shxhzhxx.library;

import java.io.File;
import java.util.UUID;

public class UrlLoaderCache extends DiskLruCache {
    private final String mSuffixData="shx_d";
    private final String mSuffixHeader="shx_h";

    public UrlLoaderCache(File cachePath, int maxSize) {
        super(cachePath, maxSize);
    }

    @Override
    public boolean accept(File dir, String name) {
        return name.endsWith("." + mSuffixData);
    }

    @Override
    protected int sizeOf(File file) {
        if (file == null || !accept(file.getParentFile(), file.getName()))
            return 0;
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
