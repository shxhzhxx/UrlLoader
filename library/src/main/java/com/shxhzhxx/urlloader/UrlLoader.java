package com.shxhzhxx.urlloader;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class UrlLoader extends MultiObserverTaskManager<UrlLoader.ProgressObserver> {
    private final String TAG = this.getClass().getSimpleName();
    private static final long UPDATE_TIME_INTERVAL = 500;
    private static final int MAX_BUF_SIZE = 8192;
    private static final int MIN_BUF_SIZE = 512;

    public abstract static class ProgressObserver {
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
         * @param speed   current download speed,  unit: byte per second
         */
        public void onProgressUpdate(long total, long current, long speed) {
        }
    }

    private Handler mMainHandler = new Handler(Looper.getMainLooper());
    private UrlLoaderCache mCache;
    private OkHttpClient mOkHttpClient;

    /**
     * @param cachePath       directory for cache file
     * @param maxCacheSize    max disk cache size in bytes
     * @param maximumPoolSize the maximum number of threads to allow in the pool
     */
    public UrlLoader(@NonNull File cachePath, @IntRange(from = 1) int maxCacheSize, @IntRange(from = 1) final int maximumPoolSize) {
        super(maximumPoolSize);
        mCache = new UrlLoaderCache(cachePath, maxCacheSize);
        mOkHttpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .build();
    }


    /**
     * load network resource.
     *
     * @param url      Uniform Resource Locator
     * @param observer observer for download task, nullable.
     * @return non-negative download id , or -1 if failed
     */
    public int load(final String url, @Nullable ProgressObserver observer) {
        return load(url, null, observer);
    }

    public int load(final String url, String tag, @Nullable ProgressObserver observer) {
        int id = start(url, tag, observer, new TaskBuilder() {
            @Override
            public Task build() {
                return new WorkThread(url);
            }
        });
        if (-1 == id && observer != null) {
            observer.onFailed();
        }
        return id;
    }

    public int load(String url) {
        return load(url, null);
    }

    public File getHeaderCache(String url) {
        return mCache.getHeaderCache(url == null ? "" : url);
    }

    public File getDataCache(String url) {
        return mCache.getDataCache(url == null ? "" : url);
    }

    public boolean checkDownload(String url) {
        if (TextUtils.isEmpty(url) || isRunning(url))
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

    public boolean clearCache(String url) {
        return !isRunning(url) && mCache.clearCache(url);
    }

    public long cacheSize() {
        return mCache.size();
    }

    public void clearCache() {
        mCache.evictAll();
    }

    public String md5(String raw) {
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
        protected void onCanceled() {
            for (ProgressObserver observer : getObservers()) {
                if (observer != null)
                    observer.onCanceled();
            }
        }

        @Override
        protected void onObserverUnregistered(ProgressObserver observer) {
            if (observer != null)
                observer.onCanceled();
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
                    for (ProgressObserver observer : getObservers()) {
                        if (observer != null)
                            observer.onComplete(mDataCache);
                    }
                }
            });
        }

        private void onFailed() {
            setPostResult(new Runnable() {
                @Override
                public void run() {
                    for (ProgressObserver observer : getObservers()) {
                        if (observer != null)
                            observer.onFailed();
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
                        if (observer != null)
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
                Log.e(TAG, "execute IOException: " + e.getMessage());
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
                Log.e(TAG, "http code: " + response.code());
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
                Log.e(TAG, "execute IOException: " + e.getMessage());
                return false;
            }
            if (isCanceled() || !response.isSuccessful()) {
                if (!response.isSuccessful())
                    Log.e(TAG, "http code: " + response.code());
                ResponseBody body = response.body();
                assert body != null;
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
                Log.e(TAG, "execute IOException: " + e.getMessage());
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
                Log.e(TAG, "http code: " + response.code());
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

        private boolean readBody(@NonNull ResponseBody body, long initFileLength) {
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
