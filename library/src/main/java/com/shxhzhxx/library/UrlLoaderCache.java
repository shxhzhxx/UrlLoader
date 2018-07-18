package com.shxhzhxx.library;

import java.io.File;
import java.util.UUID;

public class UrlLoaderCache extends DiskLruCache {
    private static final String RANDOM_SALT_KEY = "com.shxhzhxx.module.utils.UrlLoaderCache_randomSaltKey";
    private String mSuffixData;
    private String mSuffixHeader;

    public UrlLoaderCache(File cachePath, int maxSize) {
        super(cachePath, maxSize);
    }

    private String getRandomSalt() {
//        String randomSalt = Settings.getString(RANDOM_SALT_KEY, null);
//        if (randomSalt == null) {
            /*
             * using md5 as file name is a common choice,
             * to avoid file name conflict by accident,
             * we add some random salt in suffix of cache file.
             * The salt is different between devices.
             * */
//            randomSalt = md5(UUID.randomUUID().toString()).substring(0, 5);
//            Settings.putString(RANDOM_SALT_KEY, randomSalt);
//        }
//        return randomSalt;
        return "shxhzhxx";
    }

    private String suffixData() {
        if (mSuffixData == null) {
            synchronized (UrlLoaderCache.this) {
                if (mSuffixData == null) {
                    mSuffixData = getRandomSalt() + "_d";
                }
            }
        }
        return mSuffixData;
    }

    private String suffixHeader() {
        if (mSuffixHeader == null) {
            synchronized (UrlLoaderCache.this) {
                if (mSuffixHeader == null) {
                    mSuffixHeader = getRandomSalt() + "_h";
                }
            }
        }
        return mSuffixHeader;
    }

    @Override
    public boolean accept(File dir, String name) {
        return name.endsWith("." + suffixData());
    }

    @Override
    protected int sizeOf(File file) {
        if (file == null || !accept(file.getParentFile(), file.getName()))
            return 0;
        return (int) (file.length() + findHeaderCache(file).length());
    }

    public File getHeaderCache(String url) {
        return getFile(url, suffixHeader());
    }

    public File getDataCache(String url) {
        return getFile(url, suffixData());
    }

    private File findHeaderCache(File dataCache) {
        String absolutePath = dataCache.getAbsolutePath();
        return new File(absolutePath.substring(0, absolutePath.length() - suffixData().length()) + suffixHeader());
    }

    @Override
    protected void entryRemoved(boolean evicted, String key, Info oldValue, Info newValue) {
        if (evicted) {
            findHeaderCache(oldValue.file).delete();
            oldValue.file.delete();
        }
    }
}
