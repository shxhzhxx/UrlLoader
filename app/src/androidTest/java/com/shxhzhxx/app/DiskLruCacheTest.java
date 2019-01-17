package com.shxhzhxx.app;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import com.shxhzhxx.urlloader.DiskLruCache;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class DiskLruCacheTest extends BasicTest {
    private static final String TAG = "DiskLruCacheTest";

    private File cachePath;

    @Before
    public void before() {
        Context appContext = InstrumentationRegistry.getTargetContext();
        cachePath = appContext.getCacheDir();
    }

    private boolean deletePath(File path) {
        if (path.isFile())
            return path.delete();
        for (File child : path.listFiles()) {
            if (!deletePath(child))
                return false;
        }
        return true;
    }

    @Test
    public void test() {
        for (int i = 0; i < 100; ++i) {
            Log.d(TAG, "initTest: " + i);
            deletePath(cachePath);
            initTest((int) (2000 * Math.random()));
        }

        for (int i = 0; i < 100; ++i) {
            Log.d(TAG, "lruTest: " + i);
            deletePath(cachePath);
            lruTest((int) (2000 * Math.random()));
        }
    }

    private void initTest(final int scale) {
        Assert.assertTrue(runTest(() -> {
            try {
                for (int i = 0; i < scale; ++i) {
                    OutputStream os = new FileOutputStream(new File(cachePath, String.valueOf(i)));
                    os.write(data());
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
                result(false);
                return;
            }

            Thread addThread = new AddThread(cachePath, scale);
            addThread.start();

            Thread editThread = new EditThread(cachePath, scale);
            editThread.start();

            final DiskLruCache diskLruCache = new DiskLruCache(cachePath, 30 * 1024 * 1024);
            addThread.interrupt();
            editThread.interrupt();

            AtomicInteger counter = new AtomicInteger(0);
            Runnable run = new Runnable() {
                @Override
                public void run() {
                    int size = 0;
                    for (File file : cachePath.listFiles())
                        size += file.length();
                    int cacheSize = diskLruCache.size();
                    if (cacheSize == size) {
                        diskLruCache.release();
                        result(true);
                    } else {
                        if (counter.addAndGet(1) > 10) {
                            result(false);
                        } else {
                            Log.d(TAG, "size: " + size);
                            Log.d(TAG, "cacheSize: " + cacheSize);
                            mHandler.postDelayed(this, Math.max(150, scale / 10));
                            Thread.yield();
                        }
                    }
                }
            };
            mHandler.postDelayed(run, scale / 10);
        }));
    }

    private class AddThread extends Thread {
        private File cachePath;
        private int scale;

        AddThread(File cachePath, int scale) {
            this.cachePath = cachePath;
            this.scale = scale;
        }

        @Override
        public void run() {
            try {
                for (int i = scale; i < scale * 2 && !isInterrupted(); ++i) {
                    OutputStream os = new FileOutputStream(new File(cachePath, String.valueOf(i)));
                    os.write(data());
                    os.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    private class EditThread extends Thread {
        private File cachePath;
        private int scale;

        EditThread(File cachePath, int scale) {
            this.cachePath = cachePath;
            this.scale = scale;
        }

        @Override
        public void run() {
            try {
                while (!isInterrupted()) {
                    OutputStream os = new FileOutputStream(new File(cachePath, String.valueOf((int) (Math.random() * scale))));
                    os.write(data());
                    os.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    public void lruTest(final int scale) {
        if (scale <= 0)
            return;

        int max = scale * 1024;
        DiskLruCache diskLruCache = new DiskLruCache(cachePath, max);
        Assert.assertTrue(runTest(() -> {
            List<Pair<String, Integer>> sizes = new ArrayList<>();
            try {
                for (int i = 0; i < 3 * scale; ++i) {
                    byte[] data = data();
                    String key = String.valueOf(i);
                    sizes.add(new Pair<>(key, data.length));
                    OutputStream os = new FileOutputStream(diskLruCache.getFile(key,null));
                    os.write(data);
                    os.close();

                    if (Math.random() > 0.5) {
                        int index = (int) (Math.random() * i);
                        int sum = 0;
                        for (Pair<String, Integer> pair : sizes.subList(index, i + 1))
                            sum += pair.second;
                        if (sum <= max) {
                            Pair<String, Integer> pair = sizes.remove(index);
                            sizes.add(pair);
                            InputStream is = new FileInputStream(diskLruCache.getFile(pair.first,null));
                            is.close();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                result(false);
                return;
            }
            int size = 0;
            Collections.reverse(sizes);
            for (int i = 0; i < 3 * scale; ++i) {
                if (size + sizes.get(i).second > max)
                    break;
                size += sizes.get(i).second;
            }
            final int expectedSize = size;
            AtomicInteger counter = new AtomicInteger(0);
            Runnable run = new Runnable() {
                @Override
                public void run() {
                    int cacheSize = diskLruCache.size();
                    int actualSize = 0;
                    for (File file : cachePath.listFiles()) {
                        actualSize += (int) file.length();
                    }
                    if (expectedSize == cacheSize && expectedSize == actualSize) {
                        result(true);
                    } else {
                        if (counter.addAndGet(1) > 10) {
                            result(false);
                        } else {
                            Log.d(TAG, "max: " + max);
                            Log.d(TAG, "expectedSize: " + expectedSize);
                            Log.d(TAG, "cacheSize: " + cacheSize);
                            Log.d(TAG, "actualSize: " + actualSize);
                            mHandler.postDelayed(this, Math.max(scale / 3, 150));
                        }
                    }
                }
            };
            mHandler.postDelayed(run, scale / 3);
        }));
        Assert.assertTrue(runTest(() -> {
            diskLruCache.evictAll();
            int size = diskLruCache.size();
            int length = cachePath.listFiles().length;
            if (size != 0 || length != 0) {
                Log.d(TAG, "size: " + size);
                Log.d(TAG, "length: " + length);
            }
            diskLruCache.release();
            result(size == 0 && length == 0);
        }));
    }

    @NonNull
    private byte[] data() {
        return new byte[(int) (Math.random() * 1024 + 100)];
    }
}
