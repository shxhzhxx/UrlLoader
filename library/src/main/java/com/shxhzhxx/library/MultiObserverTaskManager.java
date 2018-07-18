package com.shxhzhxx.library;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public abstract class MultiObserverTaskManager<T> {
    protected final String TAG = this.getClass().getSimpleName();
    private Handler mMainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService mThreadPool;
    private Thread mMainThread = Looper.getMainLooper().getThread();
    private Map<String, Task> mKeyTaskMap = new HashMap<>();
    private SparseArray<Task> mIdTaskMap = new SparseArray<>();

    public interface ExecutorFactory {
        ExecutorService newExecutor();
    }

    public MultiObserverTaskManager(ExecutorFactory factory) {
        mThreadPool = factory.newExecutor();
    }

    public MultiObserverTaskManager() {
        this(Executors::newCachedThreadPool);
    }

    protected int start(String key, T observer, TaskBuilder builder) {
        if (TextUtils.isEmpty(key) || observer == null || builder == null) {
            return -1;
        }
        checkThread();
        int id = getObserverId();
        Task task = mKeyTaskMap.get(key);
        if (task == null) {
            task = builder.build();
            task.start();
        }
        task.registerObserver(id, observer);
        return id;
    }

    public boolean isRunningEx(String key) {
        checkThread();
        return !TextUtils.isEmpty(key) && mKeyTaskMap.get(key) != null;
    }

    /**
     * remove a observer marked by id,
     * removed observer will not receive callback.
     */
    public boolean cancelEx(int id) {
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
     * cancel a task by marked by key.
     * all observer may receive a callback (depend on implementation)
     */
    public boolean cancelEx(String key) {
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

    public void cancelAllEx() {
        for (String key : new HashSet<>(mKeyTaskMap.keySet())) {
            cancelEx(key);
        }
    }

    private int getObserverId() {
        for (int id = 0; ; ++id)
            if (mIdTaskMap.indexOfKey(id) < 0)
                return id;
    }

    private void checkThread() {
        if (Thread.currentThread() != mMainThread) {
            throw new IllegalThreadStateException("MultiObserverTaskManager must be called by main thread");
        }
    }

    protected abstract class TaskBuilder {
        public abstract Task build();
    }

    public abstract class Task implements Runnable {
        private SparseArray<T> mObserverMap;
        private final String mKey;
        private volatile boolean mCanceled, mStarted;
        private volatile Runnable mPostResult;
        private Future mFuture;

        public Task(String key) {
            mKey = key;
            mCanceled = false;
            mStarted = false;
            mPostResult = null;
            mObserverMap = new SparseArray<>();
        }

        private void start() {
            mKeyTaskMap.put(mKey, this);
            mFuture = mThreadPool.submit(this);
        }

        @Override
        public final void run() {
            synchronized (Task.this) {
                if (isCanceled())
                    return;
                mStarted = true;
            }
            try {
                doInBackground();
            } catch (Exception e) {
                Log.e(TAG, "Unhandled exception occurs in doInBackground :" + e.getMessage());
                setPostResult(null);
            }
            runResult();
        }

        private void runResult() {
            mMainHandler.post(() -> {
                if (!isCanceled()) //canceled task has already been cleared, redo this may lead new task with same key cleared by accident.
                    clear();
                Runnable result = mPostResult; // mPostResult can be changed by any thread.
                if (result != null) {
                    result.run();
                }
            });
        }

        protected final Set<T> getObservers() {
            Set<T> set = new HashSet<>();
            for (int i = 0; i < mObserverMap.size(); ++i)
                set.add(mObserverMap.valueAt(i));
            return set;
        }

        /**
         * although canceled status can be checked by any thread,there is no guarantee that shortly after the last observer has unregistered itself
         * or after the task has been canceled,this method will always return true unless it invoked by main thread.
         * actually, set canceled status to true is almost the last move when cancel a task. {@link #cancel()}
         * <p>
         * check this method in worker thread to get a hint ,so that you can stop background work appropriately .
         * always check this method in main thread before you return the result.
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
         * 2.   {@link #cancelEx(String)} been called, which {@link #getObservers()} return a list contains current observers.
         * <p>
         * either {@link #onCanceled()} or {@link #onCanceledBeforeStart()} will be invoked, avoid running time-consuming tasks in these methods.
         */
        private void cancel() {
            synchronized (Task.this) {
                mCanceled = true;
            }
            mFuture.cancel(true);
            if (mStarted) {
                onCanceled();
            } else {
                onCanceledBeforeStart();
            }
        }

        protected void onCanceled() {

        }

        protected void onCanceledBeforeStart() {

        }

        /**
         * if task has not started when {@link #cancel()} is called,then the task should never run.
         * in this circumstance, this method will never be invoked.
         */
        protected abstract void doInBackground();

        /**
         * pass whatever you want to execute (in main thread) after {@link #doInBackground()} has finished.
         * typically, you should call this method inside {@link #doInBackground()} as the result of task.
         * if you want to inform user when task is canceled before start ,which {@link #doInBackground()} will never been invoked (neither do the runnable you passed),
         * override {@link #onCanceledBeforeStart()} and do whatever you want.
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
            for (int i = 0; i < mObserverMap.size(); ++i)
                mIdTaskMap.remove(mObserverMap.keyAt(i));
        }

        final void registerObserver(int id, T observer) {
            mObserverMap.put(id, observer);
            mIdTaskMap.put(id, this);
        }

        final void unregisterObserver(int id) {
            mIdTaskMap.remove(id);
            mObserverMap.remove(id);
            if (mObserverMap.size() == 0) {
                mKeyTaskMap.remove(mKey);
                cancel();
            }
        }

        final void unregisterAll() {
            clear();
            cancel();
        }
    }
}
