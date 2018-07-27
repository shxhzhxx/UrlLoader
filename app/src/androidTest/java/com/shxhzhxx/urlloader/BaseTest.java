package com.shxhzhxx.urlloader;

import android.os.Handler;
import android.os.Looper;

public abstract class BaseTest {
    protected Handler mHandler = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private Thread mTestThread;


    protected void result(boolean passed) {
        if (passed) {
            synchronized (mLock) {
                mLock.notify();
            }
        } else {
            mTestThread.interrupt();
        }
    }

    protected boolean runTest(Runnable run) {
        mTestThread = Thread.currentThread();
        mHandler.post(run);
        synchronized (mLock) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                return false;
            }
        }
        return true;
    }
}
