package com.shxhzhxx.urlloader;

import android.util.Log;
import android.util.Pair;

import com.shxhzhxx.library.MultiObserverTaskManager;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MultiObserverTaskManagerTest extends BasicTest {
    private static final String TAG = "MultiObserverTaskManage";

    @Test
    public void test() {
        for (int i = 0; i < 100; ++i) {
            Log.d(TAG, "cancelTest: " + i);
            cancelTest();
        }

        for (int i = 0; i < 100; ++i) {
            Log.d(TAG, "multiObserverTest: " + i);
            multiObserverTest();
        }
    }

    private void cancelTest() {
        Assert.assertTrue(runTest(() -> {
            TestManager manager = new TestManager();
            String key = "test";
            int id = manager.exe(key, null);
            Assert.assertTrue(id >= 0);
            Assert.assertTrue(manager.isRunning(key));
            Assert.assertTrue(manager.cancel(id));
            Assert.assertTrue(!manager.isRunning(key));

            manager.exe(key, new TestManager.TestObserver() {
                @Override
                public void onCanceled() {
                    result(true);
                }

                @Override
                public void onComplete(Object result) {
                    result(false);
                }
            });
            Assert.assertTrue(manager.cancel(key));
        }));

        Assert.assertTrue(runTest(() -> {
            TestManager manager = new TestManager();
            String key = "test";

            AtomicBoolean canceled = new AtomicBoolean(false);
            manager.exe(key, new TestManager.TestObserver() {
                @Override
                public void onCanceled() {
                    result(canceled.get());
                }

                @Override
                public void onComplete(Object result) {
                    Log.d(TAG, "onComplete: ");
                    result(!canceled.get());
                }
            });
            mHandler.postDelayed(() -> {
                canceled.set(manager.isRunning(key));
                manager.cancel(key);
            }, (long) (100 * Math.random()));
        }));
    }

    private void multiObserverTest() {
        Assert.assertTrue(runTest(() -> {
            int scale = 1000;
            AtomicReference<Object> object = new AtomicReference<>(null);
            AtomicBoolean bool = new AtomicBoolean(true);
            AtomicInteger count = new AtomicInteger(0);
            TestManager manager = new TestManager();
            String key = "test";

            List<Pair<Integer, TestManager.TestObserver>> ids = new ArrayList<>();

            TestManager.TestObserver observer = new TestManager.TestObserver() {
                @Override
                public void onComplete(Object result) {
                    if (object.get() == null) {
                        object.set(result);
                    } else if (object.get() != result) {
                        result(false);
                        return;
                    }
                    count.decrementAndGet();
                    if (bool.get()) {
                        bool.set(false);
                        mHandler.post(() -> {
                            result(count.get() == 0);
                        });
                    }
                }

                @Override
                public void onCanceled() {
                    result(false);
                }
            };
            for (int i = 0; i < scale; ++i) {
                if (Math.random() < 0.8) {
                    count.incrementAndGet();
                    ids.add(new Pair<>(manager.exe(key, observer), observer));
                } else {
                    ids.add(new Pair<>(manager.exe(key, null), null));
                }

                if (Math.random() < 0.2) {
                    Pair<Integer, TestManager.TestObserver> pair = ids.remove((int) (Math.random() * ids.size()));
                    manager.cancel(pair.first);
                    if (pair.second != null)
                        count.decrementAndGet();
                }
            }
        }));
    }

    private static class TestManager extends MultiObserverTaskManager<TestManager.TestObserver> {
        static class TestObserver {
            public void onComplete(Object result) {
            }

            public void onCanceled() {
            }
        }

        public int exe(String key, TestObserver observer) {
            return start(key, observer, new TaskBuilder() {
                @Override
                public Task build() {
                    return new TestTask(key);
                }
            });
        }

        class TestTask extends Task {
            public TestTask(String key) {
                super(key);
            }

            @Override
            protected void onCanceled() {
                for (TestObserver observer : getObservers()) {
                    if (observer != null)
                        observer.onCanceled();
                }
            }

            @Override
            protected void doInBackground() {
                while (Math.random() > 0.2) {
                    if (isCanceled())
                        return;
                    try {
                        Thread.sleep((long) (Math.random() * 20));
                    } catch (InterruptedException ignore) {
                        return;
                    }
                }
                setPostResult(() -> {
                    Object result = new Object();
                    for (TestObserver observer : getObservers()) {
                        if (observer != null)
                            observer.onComplete(result);
                    }
                });
            }
        }
    }
}
