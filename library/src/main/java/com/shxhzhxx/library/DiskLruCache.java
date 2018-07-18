package com.shxhzhxx.library;

import android.os.FileObserver;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.File;
import java.io.FilenameFilter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class DiskLruCache extends LruCache<String, DiskLruCache.Info> implements FilenameFilter {
    private static final String TAG = "DiskLruCache";
    private File mCachePath;
    private MessageDigest mMsgDigest;
    private FileObserver mFileObserver;//hold reference


    /**
     * it's not safe to change file(in other thread) while constructor function is running.
     */
    public DiskLruCache(File cachePath, int maxSize) {
        super(maxSize);
        mCachePath = cachePath;
        if (mCachePath == null)
            throw new IllegalArgumentException("DiskLruCache cachePath=null");
        if ((!mCachePath.exists() || !mCachePath.isDirectory()) && !mCachePath.mkdirs())
            throw new IllegalArgumentException("DiskLruCache create cachePath failed");
        try {
            mMsgDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("initialize DiskLruCache failed, " + e.getMessage());
        }

        mFileObserver = new FileObserver(cachePath.getAbsolutePath(),
                FileObserver.OPEN | FileObserver.DELETE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM | FileObserver.CLOSE_WRITE | FileObserver.CLOSE_NOWRITE) {
            /**
             * This method is invoked on a special FileObserver thread.
             * */
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !accept(mCachePath, path))
                    return;
                switch (event) {
                    case FileObserver.OPEN:
                        remove(path);
                        break;
                    case FileObserver.DELETE:
                    case FileObserver.MOVED_TO:
                    case FileObserver.MOVED_FROM:
                    case FileObserver.CLOSE_WRITE:
                    case FileObserver.CLOSE_NOWRITE:
                        File file = new File(mCachePath, path);
                        Info info = new Info(file, sizeOf(file));
                        if (event == FileObserver.CLOSE_NOWRITE) {//read
                            file.setLastModified(System.currentTimeMillis());
                        }
                        put(path, info);
                        break;
                }
            }
        };
        mFileObserver.startWatching();

        File[] files = mCachePath.listFiles(this);
        Arrays.sort(files, (o1, o2) -> Long.compare(o1.lastModified(), o2.lastModified()));
        for (File file : files) {
            put(file.getName(), new Info(file, sizeOf(file)));
        }
    }

    public static class Info {
        Info(File file, int size) {
            this.file = file;
            this.size = size;
        }

        File file;
        int size;
    }

    /**
     * this function needs to be thread safety
     */
    @Override
    public boolean accept(File dir, String name) {
        return new File(dir, name).isFile();
    }

    public File getFile(String key) {
        return getFile(key, null);
    }

    protected File getFile(String key, String suffix) {
        String child = md5(key);
        if (!TextUtils.isEmpty(suffix)) {
            child += "." + suffix;
        }
        return new File(mCachePath, child);
    }

    /**
     * this function needs to be thread safety
     */
    protected int sizeOf(File file) {
        if (file == null || !file.exists())
            return 0;
        return (int) file.length();
    }

    @Override
    protected int sizeOf(String key, Info value) {
        return value.size;
    }

    public String md5(String raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : mMsgDigest.digest(raw.getBytes()))
            sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
        return sb.toString();
    }

    @Override
    protected void entryRemoved(boolean evicted, String key, Info oldValue, Info newValue) {
        if (evicted)
            oldValue.file.delete();
    }
}
