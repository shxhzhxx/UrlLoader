package com.shxhzhxx.urlloader;

import android.os.FileObserver;
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

import androidx.annotation.AnyThread;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;


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
                        /*
                         * Read operation will not update file's lastModified.
                         * Once we update file's lastModified manually here, we need to do it for all operations to keep relative order of files,
                         * regardless of whether it is a read operation.
                         * */
                        file.setLastModified(System.currentTimeMillis());
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
                Long t1 = fileLastModified.get(o1);
                Long t2 = fileLastModified.get(o2);
                assert t1 != null && t2 != null;
                return (int) (t1 - t2);
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

    public synchronized final String md5(@NonNull String raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : mMsgDigest.digest(raw.getBytes()))
            sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
        return sb.toString();
    }

    protected void onDelete(Info info) {
        info.file.delete();
    }

    @Override
    protected final void entryRemoved(boolean evicted, String key, Info oldValue, Info newValue) {
        if (newValue == null)
            onDelete(oldValue);
    }
}

/*
 * 关于多线程文件操作环境下实例正确初始化的分析：
 *
 * 先定义“正确”的标准：主线程初始化实例完成且事件回调线程里的所有事件都处理完毕后，
 *                       ·记录了指定目录下所有符合条件的文件；
 *                       ·记录的文件大小与真实大小相符；
 *                      ·记录的文件顺序是按最后修改时间从小到大排序的。
 *
 * FileObserver基于linux的inotify，查看inotify的man-pages得到如下有用的信息：
 *   ·未读的同样事件会被底层合并（连续？）
 *   ·事件队列有上限，超出后事件会被丢弃（忽略）
 *   ·事件顺序有保证
 *
 * 主线程的执行顺序是 上锁->注册监听->读取目录当前状态（细分为先读取文件列表files，获取files里每个文件的最后修改时间，
 *                                                       根据时间排序files，获取files里每个文件的大小。  注意这些操作都不是原子性的）->释放锁
 *
 *
 * 证明：记录了指定目录下所有符合条件的文件
 *       这个命题可以分割成针对每个文件的独立子命题。 子命题：该文件如果符合条件，那么一定被记录在内存中。
 *       如果所有子命题成立，则这个命题成立。
 *       在主线程完成初始化并释放锁后，所有被挂起的事件会按序执行，如果文件在主线程上锁后有被修改，那么最后一个事件决定了内存中的记录状态（每次处理事件都会覆盖之前的状态），
 *       同时由于事件按序传递，最后一个事件也代表了文件的实际状态。
 *       如果文件在主线程上锁后没有被修改，那么上锁后读取的文件状态就是文件的实际状态。
 *       命题成立。
 *
 * 证明：记录的文件大小与真实大小相符
 *       证明同上。
 *       命题成立。
 *
 * 证明：记录的文件顺序是按最后修改时间从小到大排序的
 *       将所有文件分为两类，从注册监听开始到所有事件处理完毕为止没有被修改的文件为集合A，有被修改的为集合B。
 *       对于集合A，主线程读取的最后修改时间有效，最后排序结果是正确的相对顺序。
 *       对于集合B，所有文件都至少有一个相对应的事件等待处理，其中最后一个事件决定了该文件在LRU队列中的位置，
 *       同时由于事件按序传递，最后一个事件也代表了文件的实际顺序。集合B的文件（在LRU队列中的位置）全都在集合A的后面。
 *
 *
 * 从上面的证明可以看出，对任意文件，只需要处理队列中与其相关的最后一个事件，其他的都可以忽略。
 *
 * */
