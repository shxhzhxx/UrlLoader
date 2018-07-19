package com.shxhzhxx.library;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class UrlLoader extends MultiObserverTaskManager<UrlLoader.ProgressObserver> {
    private static final long UPDATE_TIME_INTERVAL = 500;
    private static final int MAX_BUF_SIZE = 8192;
    private static final int MIN_BUF_SIZE = 512;

    public abstract static class ProgressObserver {
        // For internal use
        private File mFile;

        /**
         * @param file not writable. If you need to modify it ,make a copy.
         */
        public void onComplete(File file) {
        }

        public void onFailed() {
        }

        public void onCanceled() {
        }

        /**
         * @param total   total length of mOutput in byte, -1 if unknown.
         * @param current downloaded length in byte
         * @param speed   download speed, unit: byte per second
         */
        public void onProgressUpdate(long total, long current, long speed) {
        }
    }

    private static UrlLoader mInstance;

    public static void init(File cachePath) {
        /*
         * default params to init UrlLoader, especially suit for image cache.
         * 50M is enough for image cache. For other circumstance like file download, set a bigger cache size.
         * */
        init(cachePath, 50 * 1024 * 1024, 5);
    }

    /**
     * @param cachePath       root directory for cache file
     * @param maxCacheSize    max disk cache size in bytes
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     */
    public static synchronized void init(File cachePath, int maxCacheSize, int maximumPoolSize) {
        if (mInstance != null)
            return;
        mInstance = new UrlLoader(cachePath, maxCacheSize, maximumPoolSize);
    }

    /**
     * load network resource.
     * specify output file(especially big file) could slow down main thread performance,
     * thus you should use load(String, ProgressObserver) instead.
     *
     * @param url    mUrl
     * @param output the mOutput used to store data.
     *               if null , UrlLoader will create a file which name is url's md5 hash
     * @return non-negative download id , or -1 if failed
     */
    @Deprecated
    public static int load(final String url, File output, ProgressObserver observer) {
        return mInstance.loadEx(url, output, observer);
    }

    public static int load(String url, ProgressObserver observer) {
        return load(url, null, observer);
    }

    public static int load(String url) {
        return load(url, null);
    }

    public static boolean deleteCacheFile(String url) {
        return mInstance.deleteCacheFileEx(url);
    }

    /**
     * discard everything after "?" and "#"
     */
    @Deprecated
    public static String rawUrl(String url) {
        int last = url.length();
        if (url.contains("?")) {
            last = url.indexOf("?");
        }
        if (url.contains("#")) {
            last = Math.min(last, url.indexOf("#"));
        }
        return url.substring(0, last).replace("\\", "");
    }

    public static boolean copyFile(File src, File dst) {
        if (dst.exists())
            if (!dst.delete())
                return false;
        try {
            FileChannel in = new FileInputStream(src).getChannel();
            FileChannel out = new FileOutputStream(dst).getChannel();
            out.transferFrom(in, 0, in.size());
            in.close();
            out.close();
            return true;
        } catch (IOException ignore) {
            return false;
        }
    }

    /**
     * Returns download file size or cache size.
     */
    public static long cacheSize(String url) {
        return mInstance.getDataCache(url).length();
    }

    /**
     * Returns if download is finished.
     */
    public static boolean checkDownload(String url) {
        return mInstance.checkDownloadEx(url);
    }

    /**
     * clear download file or cache file.
     */
    public static boolean clearCache(String url) {
        return mInstance.clearCacheEx(url);
    }

    public static long cacheSize() {
        return mInstance.cacheSizeEx();
    }

    public static void clearCache() {
        mInstance.clearCacheEx();
    }

    public static String md5(String raw) {
        return mInstance.md5Ex(raw);
    }

    public static boolean isRunning(String key) {
        return mInstance.isRunningEx(key);
    }

    public static boolean cancel(int id) {
        return mInstance.cancelEx(id);
    }

    public static boolean cancel(String url) {
        return mInstance.cancelEx(url);
    }

    public static void cancelAll() {
        mInstance.cancelAllEx();
    }

    private Handler mMainHandler = new Handler(Looper.getMainLooper());
    private UrlLoaderCache mCache;
    private OkHttpClient mOkHttpClient;

    private UrlLoader(File cachePath, int maxCacheSize, final int maximumPoolSize) {
        super(new ExecutorFactory() {
            @Override
            public ExecutorService newExecutor() {
                ThreadPoolExecutor executor = new ThreadPoolExecutor(maximumPoolSize, maximumPoolSize, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
                executor.allowCoreThreadTimeOut(true);
                return executor;
            }
        });
        mCache = new UrlLoaderCache(cachePath, maxCacheSize);
        mOkHttpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    private int loadEx(final String url, File output, ProgressObserver observer) {
        if (TextUtils.isEmpty(url)) {
            Log.e(TAG, TAG + ".load: invalid params");
            return -1;
        }
        if (observer == null)
            observer = new ProgressObserver() {
            };
        observer.mFile = output != null ? output : getDataCache(url);
        return start(url, observer, new TaskBuilder() {
            @Override
            public Task build() {
                return new WorkThread(url);
            }
        });
    }

    private boolean deleteCacheFileEx(String url) {
        if (isRunningEx(url))
            return false;
        File headerCache = getHeaderCache(url), dataCache = getDataCache(url);
        return (!headerCache.exists() || headerCache.delete()) && (!dataCache.exists() || dataCache.delete());
    }

    private File getHeaderCache(String url) {
        return mCache.getHeaderCache(url == null ? "" : url);
    }

    private File getDataCache(String url) {
        return mCache.getDataCache(url == null ? "" : url);
    }

    private boolean checkDownloadEx(String url) {
        if (TextUtils.isEmpty(url) || isRunningEx(url))
            return false;
        File headerCache = mCache.getHeaderCache(url);
        File dataCache = mCache.getDataCache(url);
        if (!headerCache.exists() || !dataCache.exists())
            return false;
        Headers.Builder builder = new Headers.Builder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(headerCache));
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    builder.add(line);
                } catch (IllegalArgumentException ignore) {
                    //skip headers with format exception
                }
            }
        } catch (IOException e) {
            return false;
        }
        Headers headers = builder.build();
        try {
            if (dataCache.length() != Long.valueOf(headers.get("Content-Length")))
                return false;
        } catch (NumberFormatException e) {
            return false;
        }

        CacheControl control = CacheControl.parse(headers);
        return System.currentTimeMillis() < headerCache.lastModified() + control.maxAgeSeconds() * 1000;
    }

    private boolean clearCacheEx(String url) {
        File headerCache = getHeaderCache(url);
        File dataCache = getDataCache(url);
        return !isRunningEx(url) && (!headerCache.exists() || headerCache.delete()) && (!dataCache.exists() || dataCache.delete());
    }

    private long cacheSizeEx() {
        return mCache.size();
    }

    private void clearCacheEx() {
        mCache.evictAll();
    }

    private String md5Ex(String raw) {
        return mCache.md5(raw);
    }

    private class WorkThread extends Task {
        private String mUrl;
        private File mHeaderCache, mDataCache;
        private long mContentLength, mDownloadContentLength, mPreDownloadContentLength, mBytePerSec;

        private ScheduledFuture<?> mScheduleFuture;
        private Runnable mDownloadSpeedUpdate;

        WorkThread(String url) {
            super(url);
            mUrl = url;
            mHeaderCache = getHeaderCache(url);
            mDataCache = getDataCache(url);
        }

        @Override
        protected void onCanceledBeforeStart() {
            for (ProgressObserver observer : getObservers()) {
                observer.onCanceled();
            }
        }

        @Override
        protected void doInBackground() {
            if (!isCanceled() && mainFunc() && (!mDataCache.canWrite() || mDataCache.setWritable(false))) {
                onComplete();
            } else {
                onFailed();
            }
        }

        private void onComplete() {
            setPostResult(new Runnable() {
                @Override
                public void run() {
                    if (isCanceled()) {
                        for (ProgressObserver observer : getObservers()) {
                            observer.onCanceled();
                        }
                    } else {
                        for (ProgressObserver observer : getObservers()) {
                            if (!mDataCache.exists() || !observer.mFile.equals(mDataCache)) {
                                // Copying big file may take a lot of time, so you should avoid specifying the output file.
                                if (!mDataCache.exists() || !copyFile(mDataCache, observer.mFile)) {
                                    Log.e(TAG, "could not find cache file for url:" + mUrl);
                                    observer.onFailed();
                                    continue;
                                }
                            }
                            observer.onComplete(observer.mFile);
                        }
                    }
                }
            });
        }

        private void onFailed() {
            setPostResult(new Runnable() {
                @Override
                public void run() {
                    if (isCanceled()) {
                        for (ProgressObserver observer : getObservers()) {
                            observer.onCanceled();
                        }
                    } else {
                        for (ProgressObserver observer : getObservers()) {
                            observer.onFailed();
                        }
                    }
                }
            });
        }

        private void stopDownloadSpeedUpdate() {
            if (mScheduleFuture != null) {
                mScheduleFuture.cancel(false);
            }
        }

        private void scheduleDownloadSpeedUpdate() {
            stopDownloadSpeedUpdate();
            mPreDownloadContentLength = mDownloadContentLength;
            mDownloadSpeedUpdate = new Runnable() {
                @Override
                public void run() {
                    if (isCanceled())
                        return;
                    for (ProgressObserver observer : getObservers()) {
                        observer.onProgressUpdate(mContentLength, mDownloadContentLength, mBytePerSec);
                    }
                }
            };
            mScheduleFuture = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                    new Runnable() {
                        @Override
                        public void run() {
                            mBytePerSec = (mDownloadContentLength - mPreDownloadContentLength) * 1000 / UPDATE_TIME_INTERVAL;
                            mPreDownloadContentLength = mDownloadContentLength;
                            mMainHandler.post(mDownloadSpeedUpdate);
                        }
                    }, UPDATE_TIME_INTERVAL,
                    UPDATE_TIME_INTERVAL, TimeUnit.MILLISECONDS);
        }

        /**
         * https://www.rfc-editor.org/rfc/rfc2068.txt
         * https://developer.mozilla.org/en-US/docs/Web/HTTP/Conditional_requests
         */
        private boolean mainFunc() {
            if (!mHeaderCache.exists() || !mDataCache.exists())
                return download();
            Headers.Builder builder = new Headers.Builder();
            try {
                BufferedReader br = new BufferedReader(new FileReader(mHeaderCache));
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        builder.add(line);
                    } catch (IllegalArgumentException ignore) {
                        //skip headers with format exception
                    }
                }
            } catch (IOException e) {
                return download();
            }
            Headers headers = builder.build();
            try {
                if (mDataCache.length() != Long.valueOf(headers.get("Content-Length")))
                    return resumeDownload(headers);
            } catch (NumberFormatException e) {
                return download();
            }

            CacheControl control = CacheControl.parse(headers);
            if (System.currentTimeMillis() < mHeaderCache.lastModified() + control.maxAgeSeconds() * 1000) {
                //cache still fresh
                return true;
            }
            String lastModified = headers.get("Last-Modified");
            String eTag = headers.get("ETag");
            if (lastModified == null && eTag == null)
                return download();
            Request.Builder request;
            try {
                request = new Request.Builder().url(mUrl);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, String.format(Locale.CHINA, "\"%s\" is not a valid HTTP or HTTPS URL", mUrl));
                return false;
            }
            if (lastModified != null)
                request.header("If-Modified-Since", lastModified);
            if (eTag != null)
                request.header("If-None-Match", eTag);
            Call call = mOkHttpClient.newCall(request.build());
            Response response;
            try {
                response = call.execute();
            } catch (IOException e) {
                Log.e(TAG, "execute IOException:" + e.getMessage());
                return false;
            }
            ResponseBody body = response.body();
            assert body != null;
            if (response.code() == 304 /*not modified*/) {
                String cacheControl = response.headers().get("Cache-Control");
                if (cacheControl != null) {
                    headers.newBuilder().set("Cache-Control", cacheControl);
                    try {
                        //update header cache file.
                        new FileOutputStream(mHeaderCache).write(headers.toString().getBytes());
                    } catch (IOException ignored) {
                    }
                }
                body.close();
                return true;
            } else if (response.isSuccessful()) {
                return resetCache() && readResponse(response);
            } else {
                Log.e(TAG, "http code:" + response.code());
                body.close();
                return false;
            }
        }

        private boolean resetCache() {
            try {
                if (((mHeaderCache.exists() && !mHeaderCache.delete()) || !mHeaderCache.createNewFile())
                        || ((mDataCache.exists() && !mDataCache.delete()) || !mDataCache.createNewFile())) {
                    Log.e(TAG, "failed to create download cache");
                    return false;
                }
            } catch (IOException e) {
                Log.e(TAG, "failed to create download cache,  IOException: " + e.getMessage());
                return false;
            }
            return true;
        }

        private boolean download() {
            if (!resetCache())
                return false;
            Request.Builder request;
            try {
                request = new Request.Builder().url(mUrl);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, String.format(Locale.CHINA, "\"%s\" is not a valid HTTP or HTTPS URL", mUrl));
                return false;
            }
            Call call = mOkHttpClient.newCall(request.build());
            Response response;
            try {
                response = call.execute();
            } catch (IOException e) {
                Log.e(TAG, "execute IOException:" + e.getMessage());
                return false;
            }
            ResponseBody body = response.body();
            assert body != null;
            if (isCanceled()) {
                body.close();
                return false;
            }
            if (!response.isSuccessful()) {
                Log.e(TAG, "http code:" + response.code());
                body.close();
                return false;
            }
            return readResponse(response);
        }

        private boolean resumeDownload(Headers headers) {
            if (!"bytes".equals(headers.get("Accept-Ranges"))) //server does not support resume download for byte unit.
                return download();
            String eTag, lastModified = null;
            if (null == (eTag = headers.get("ETag")) && null == (lastModified = headers.get("Last-Modified")))
                return download();
            Request.Builder request;
            try {
                request = new Request.Builder().url(mUrl);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, String.format(Locale.CHINA, "\"%s\" is not a valid HTTP or HTTPS URL", mUrl));
                return false;
            }
            long initFileLength = mDataCache.length();
            request.header("Range", "bytes=" + initFileLength + "-");
            request.header("If-Range", eTag != null ? eTag : lastModified);
            Call call = mOkHttpClient.newCall(request.build());
            Response response;
            try {
                response = call.execute();
            } catch (IOException e) {
                Log.e(TAG, "execute IOException:" + e.getMessage());
                return false;
            }
            ResponseBody body = response.body();
            assert body != null;
            if (response.code() == 206 /*Partial Content*/) {
                String cacheControl = response.headers().get("Cache-Control");
                if (cacheControl != null) {
                    headers.newBuilder().set("Cache-Control", cacheControl);
                    try {
                        //update header cache file.
                        new FileOutputStream(mHeaderCache).write(headers.toString().getBytes());
                    } catch (IOException ignored) {
                    }
                }
                return readBody(body, initFileLength);
            } else if (response.isSuccessful()) {
                return resetCache() && readResponse(response);
            } else {
                Log.e(TAG, "http code:" + response.code());
                body.close();
                return false;
            }
        }

        private boolean readResponse(Response response) {
            ResponseBody body = response.body();
            assert body != null;
            try {
                new FileOutputStream(mHeaderCache).write(response.headers().toString().getBytes());
            } catch (IOException e) {
                body.close();
                return false;
            }
            return readBody(body, 0);
        }

        private boolean readBody(ResponseBody body, long initFileLength) {
            long contentLength = body.contentLength();
            mContentLength = contentLength == -1 ? -1 : contentLength + initFileLength;
            mDownloadContentLength = initFileLength;
            int buf_siz = contentLength == -1 ?
                    MIN_BUF_SIZE :
                    (int) Math.min(MAX_BUF_SIZE, (mContentLength - initFileLength) / 5 + 1);
            byte buff[] = new byte[buf_siz];
            InputStream is = body.byteStream();
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(mDataCache, true);
                scheduleDownloadSpeedUpdate();
                int n;
                while ((n = is.read(buff, 0, buf_siz)) >= 0) {
                    if (isCanceled()) {
                        return false;
                    }
                    fos.write(buff, 0, n);
                    mDownloadContentLength += n;
                }
                fos.close();
                fos = null;
                return true;
            } catch (IOException e) {
                Log.e(TAG, "read IOException: " + e.getMessage());
                return false;
            } finally {
                stopDownloadSpeedUpdate();
                body.close();
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ignore) {
                    }
                }
            }
        }
    }
}
