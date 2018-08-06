package com.shxhzhxx.urlloader;

import android.os.Handler;
import android.os.Looper;

public abstract class BasicTest {
    protected Handler mHandler = new Handler(Looper.getMainLooper());
    private Thread mTestThread;


    protected synchronized void result(boolean passed) {
        if (passed) {
            notify();
        } else {
            mTestThread.interrupt();
        }
    }

    protected synchronized boolean runTest(Runnable run) {
        mTestThread = Thread.currentThread();
        mHandler.post(run);
        try {
            wait();
        } catch (InterruptedException e) {
            return false;
        }
        return true;
    }
}
