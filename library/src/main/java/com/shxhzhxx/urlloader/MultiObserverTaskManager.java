package com.shxhzhxx.urlloader;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class MultiObserverTaskManager<T> {
    private final String TAG = this.getClass().getSimpleName();
    private Handler mMainHandler = new Handler(Looper.getMainLooper());
    private ThreadPoolExecutor mThreadPoolExecutor;
    private Thread mMainThread = Looper.getMainLooper().getThread();
    private Map<String, Task> mKeyTaskMap = new HashMap<>();
    private SparseArray<Task> mIdTaskMap = new SparseArray<>();
    private Map<String, Set<Integer>> mTagIdsMap = new HashMap<>();
    private SparseArray<String> mIdTagMap = new SparseArray<>();

    public MultiObserverTaskManager() {
        mThreadPoolExecutor = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    public MultiObserverTaskManager(@IntRange(from = 1) int maximumPoolSize) {
        mThreadPoolExecutor = new ThreadPoolExecutor(maximumPoolSize, maximumPoolSize, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        mThreadPoolExecutor.allowCoreThreadTimeOut(true);
    }

    /**
     * Only when this instance is constructed by {@link #MultiObserverTaskManager(int)} could use this func to change thread pool size.
     */
    public void setMaximumPoolSize(@IntRange(from = 1) int size) {
        if (mThreadPoolExecutor.getCorePoolSize() == 0)
            return;
        mThreadPoolExecutor.setCorePoolSize(size);
        mThreadPoolExecutor.setMaximumPoolSize(size);
    }

    public abstract class TaskBuilder {
        public abstract Task build();
    }

    /**
     * join a task or start a new task.
     *
     * @param key      should not be null nor empty string
     * @param tag      may be null. group the requests so that you can easily cancel them by {@link #cancelByTag(String)}
     * @param observer may be null. Identical instance can be passed multi times.
     * @param builder  builder
     * @return non-negative task id , or -1 if failed. this id may be reused after task is finished or canceled or observer is unregistered.
     */
    protected final int start(String key, @Nullable String tag, @Nullable T observer, TaskBuilder builder) {
        if (TextUtils.isEmpty(key) || builder == null) {
            return -1;
        }
        checkThread();
        int id = generateObserverId();
        Task task = mKeyTaskMap.get(key);
        if (task == null) {
            task = builder.build();
            task.start();
        }
        task.registerObserver(id, observer);

        if (!TextUtils.isEmpty(tag)) {
            mIdTagMap.put(id, tag);
            Set<Integer> ids = mTagIdsMap.get(tag);
            if (ids == null) {
                ids = new HashSet<>();
                mTagIdsMap.put(tag, ids);
            }
            ids.add(id);
        }
        return id;
    }


    public final boolean cancelByTag(String tag) {
        checkThread();
        Set<Integer> ids = mTagIdsMap.get(tag);
        if (ids == null)
            return false;
        boolean result = true;
        ids = new HashSet<>(ids);//clone
        for (int id : ids) {
            result = cancel(id) && result;
        }
        return result;
    }

    public final boolean isRunning(String key) {
        checkThread();
        return !TextUtils.isEmpty(key) && mKeyTaskMap.get(key) != null;
    }

    /**
     * remove a observer marked by id,
     * removed observer will not receive callback.
     */
    public final boolean cancel(int id) {
        checkThread();
        Task task = mIdTaskMap.get(id);
        if (task != null) {
            task.unregisterObserver(id);
            return true;
        } else {
            return false;
        }
    }

    /**
     * cancel a task marked by key.
     * all observer may receive a callback (depend on implementation)
     */
    public final boolean cancel(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        checkThread();
        Task task = mKeyTaskMap.get(key);
        if (task != null) {
            task.unregisterAll();
            return true;
        }
        return false;
    }

    public final void cancelAll() {
        for (String key : new HashSet<>(mKeyTaskMap.keySet())) {
            cancel(key);
        }
    }

    private int generateObserverId() {
        for (int id = 0; ; ++id)
            if (mIdTaskMap.indexOfKey(id) < 0)
                return id;
    }

    private void checkThread() {
        if (Thread.currentThread() != mMainThread) {
            throw new IllegalThreadStateException("MultiObserverTaskManager must be called by main thread");
        }
    }

    public abstract class Task implements Runnable {
        private SparseArray<T> mObserverMap;
        private final String mKey;
        private volatile boolean mCanceled;
        private volatile Runnable mPostResult;
        private Future mFuture;

        public Task(String key) {
            mKey = key;
            mCanceled = false;
            mPostResult = null;
            mObserverMap = new SparseArray<>();
        }

        private void start() {
            mKeyTaskMap.put(mKey, this);
            mFuture = mThreadPoolExecutor.submit(this);
        }

        @Override
        public final void run() {
            if (isCanceled())
                return;
            try {
                doInBackground();
                runResult(mPostResult);
            } catch (Exception e) {
                Log.e(TAG, "Unhandled exception occurs in doInBackground: " + e.getMessage());
                runResult(null);
            }
        }

        private void runResult(final Runnable result) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isCanceled()) //canceled task has already been cleared, redo clear() may lead new task with same key cleared by accident.
                        return;
                    clear();
                    if (result != null) {
                        result.run();
                    }
                }
            });
        }

        /**
         * observer maybe null. may contains identical instance multi times.
         */
        protected final Iterable<T> getObservers() {
            List<T> list = new ArrayList<>();
            for (int i = 0; i < mObserverMap.size(); ++i)
                list.add(mObserverMap.valueAt(i));
            return list;
        }

        /**
         * although canceled status can be checked by any thread,there is no guarantee that shortly after the last observer has unregistered itself
         * or after the task has been canceled,this method will always return true unless it invoked by main thread. (cancel is not a atomic operation)
         * actually, set canceled status to true is almost the last move when cancel a task. {@link #cancel()}
         * <p>
         * check this method in worker thread to get a hint ,so that you can stop background work appropriately .
         */
        protected boolean isCanceled() {
            return mCanceled;
        }

        protected String getKey() {
            return mKey;
        }

        /**
         * cancel executed in main thread.
         * cancel called in two case:
         * 1.   all observer unregistered ,which {@link #getObservers()} return an empty list;
         * 2.   {@link #cancel(String)} been called, which {@link #getObservers()} return a list contains current observers.
         * <p>
         * {@link #onCanceled()} will be invoked, avoid running time-consuming tasks in these methods.
         */
        private void cancel() {
            mCanceled = true;
            mFuture.cancel(true);
            onCanceled();
        }

        /**
         * invoked when this task is canceled, in main thread.
         */
        protected void onCanceled() {

        }

        /**
         * if task has not started when {@link #cancel()} is called,then the task should never run.
         * in this circumstance, this method will never be invoked.
         */
        protected abstract void doInBackground();

        /**
         * pass whatever you want to execute (in main thread) after {@link #doInBackground()} has successfully finished (without cancel nor exception).
         * typically, you should call this method inside {@link #doInBackground()} as the result of task.
         * <p>
         * if this task is canceled, the runnable will not been invoked,
         * override {@link #onCanceled()} instead in this case.
         * <p>
         * the reason that why you should pass result by this method instead of doing it yourself is,
         * you never know when is safe to return result to observers.
         * maybe an observer register itself after you return result to other observers and before the task has been cleared.
         * the new comer will die alone without any callback been invoked.
         */
        protected void setPostResult(Runnable run) {
            mPostResult = run;
        }

        /**
         * remove this task's information from {@link #mKeyTaskMap} and {@link #mIdTaskMap}
         * keep the information of {@link #mObserverMap} , so that you can return results to observers.
         */
        private void clear() {
            mKeyTaskMap.remove(mKey);
            for (int i = 0; i < mObserverMap.size(); ++i) {
                mIdTaskMap.remove(mObserverMap.keyAt(i));
                removeTagId(mObserverMap.keyAt(i));
            }
        }

        /**
         * @param id       task id
         * @param observer {@link #getObservers()} will return whatever this param is.
         */
        final void registerObserver(int id, @Nullable T observer) {
            mObserverMap.put(id, observer);
            mIdTaskMap.put(id, this);
        }

        /**
         * Note: task which is canceled by {@link #cancel(String)} or {@link #cancelAll()},
         * technically its observers haven't been unregistered, and this func will not been invoked.
         *
         * @param observer ob canceled by {@link #cancel(int)} and {@link #cancelByTag(String)}
         */
        protected void onObserverUnregistered(T observer) {

        }

        protected void unregisterObserver(int id) {
            removeTagId(id);
            mIdTaskMap.remove(id);
            int index = mObserverMap.indexOfKey(id);
            if (index >= 0) {
                onObserverUnregistered(mObserverMap.valueAt(index));
                mObserverMap.removeAt(index);
            }
            if (mObserverMap.size() == 0) {
                mKeyTaskMap.remove(mKey);
                cancel();
            }
        }

        final void unregisterAll() {
            clear();
            cancel();
        }

        private void removeTagId(int id) {
            String tag = mIdTagMap.get(id);
            if (tag == null)
                return;
            mIdTagMap.remove(id);
            Set<Integer> ids = mTagIdsMap.get(tag);
            assert ids!=null;
            ids.remove(id);
            if (ids.isEmpty()) {
                mTagIdsMap.remove(tag);
            }
        }
    }
}
