package com.shxhzhxx.urlloader;

import android.os.FileObserver;
import android.support.annotation.AnyThread;
import android.support.annotation.IntRange;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.File;
import java.io.FilenameFilter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Note: {@link #size()} is not guaranteed to be precisely.
 * <p>
 * The observer of cachePath get callback on a special FileObserver thread.
 * <p>
 * If a DiskLruCache is garbage collected, it
 * will stop control cache size.  To ensure you keep controlling cache size, you must
 * keep a reference to the DiskLruCache instance from some other live object.
 */
public class DiskLruCache extends LruCache<String, DiskLruCache.Info> implements FilenameFilter {
    private File mCachePath;
    private MessageDigest mMsgDigest;
    private FileObserver mFileObserver; //hold reference
    private Lock mInitLock;

    public DiskLruCache(@NonNull File cachePath, @IntRange(from = 1) int maxSize) {
        super(maxSize);
        mCachePath = cachePath;
        if ((!mCachePath.exists() || !mCachePath.isDirectory()) && !mCachePath.mkdirs())
            throw new IllegalArgumentException("DiskLruCache create cachePath failed");
        try {
            mMsgDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("initialize DiskLruCache failed, " + e.getMessage());
        }

        mInitLock = new ReentrantLock();
        mInitLock.lock();
        mFileObserver = new FileObserver(cachePath.getAbsolutePath(),
                FileObserver.OPEN | FileObserver.DELETE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM | FileObserver.CLOSE_WRITE | FileObserver.CLOSE_NOWRITE) {
            /**
             * This method is invoked on a special FileObserver thread.
             * */
            @WorkerThread
            @Override
            public void onEvent(final int event, @Nullable final String path) {
                if (path == null || !accept(mCachePath, path))
                    return;
                if (mInitLock != null) {
                    mInitLock.lock();
                }
                switch (event) {
                    case FileObserver.MOVED_FROM:
                    case FileObserver.DELETE:
                        remove(path);
                        break;
                    case FileObserver.OPEN:
                    case FileObserver.MOVED_TO:
                    case FileObserver.CLOSE_WRITE:
                    case FileObserver.CLOSE_NOWRITE:
                        File file = new File(mCachePath, path);
                        Info info = new Info(file, sizeOf(file));
                        if (event == FileObserver.CLOSE_NOWRITE || event == FileObserver.OPEN) {//read
                            file.setLastModified(System.currentTimeMillis());
                        }
                        put(path, info);
                        break;
                }
                if (mInitLock != null) {
                    mInitLock.unlock();
                    mInitLock = null;
                }
            }
        };
        mFileObserver.startWatching();

        File[] files = mCachePath.listFiles(this);
        final Map<File, Long> fileLastModified = new HashMap<>();
        for (File file : files) {
            fileLastModified.put(file, file.lastModified());
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return (int) (fileLastModified.get(o1) - fileLastModified.get(o2));
            }
        });
        for (File file : files) {
            put(file.getName(), new Info(file, sizeOf(file)));
        }

        mInitLock.unlock();
    }

    /**
     * If a DiskLruCache is garbage collected, it will stop control cache size.
     * you can actively promote it by calling this function.
     */
    public void release() {
        mFileObserver.stopWatching();
    }

    static class Info {
        Info(@NonNull File file, @IntRange(from = 0) int size) {
            this.file = file;
            this.size = size;
        }

        final File file;
        final int size;
    }

    @AnyThread
    @Override
    public boolean accept(File dir, @NonNull String name) {
        return new File(dir, name).isFile();
    }

    public File getFile(@NonNull String key) {
        return getFile(key, null);
    }

    protected File getFile(@NonNull String key, String suffix) {
        String child = md5(key);
        if (!TextUtils.isEmpty(suffix)) {
            child += "." + suffix;
        }
        return new File(mCachePath, child);
    }

    /**
     * @param file is already checked by {@link #accept(File, String)}
     */
    @AnyThread
    protected @IntRange(from = 0)
    int sizeOf(@NonNull File file) {
        return (int) file.length();
    }

    @Override
    protected @IntRange(from = 0)
    int sizeOf(String key, Info value) {
        return value.size;
    }

    @MainThread
    public synchronized final String md5(@NonNull String raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : mMsgDigest.digest(raw.getBytes()))
            sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
        return sb.toString();
    }

    protected void onDelete(Info info){
        info.file.delete();
    }

    @Override
    protected final void entryRemoved(boolean evicted, String key, Info oldValue, Info newValue) {
        if (newValue == null)
            onDelete(oldValue);
    }
}
