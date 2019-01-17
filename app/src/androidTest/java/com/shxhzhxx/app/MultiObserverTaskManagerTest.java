package com.shxhzhxx.app;

import android.util.Log;
import android.util.Pair;

import com.shxhzhxx.urlloader.TaskManager;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        for (int i = 0; i < 100; ++i) {
            Log.d(TAG, "tagTest: " + i);
            tagTest((int) (1000 * Math.random()));
        }
    }

    private void cancelTest() {
        Assert.assertTrue(runTest(() -> {
            TestManager manager = new TestManager();
            String key = "test";
            int id = manager.exe(key, null);
            Assert.assertTrue(id >= 0);
            Assert.assertTrue(manager.isRunning(key));
            Assert.assertTrue(manager.unregister(id));
            Assert.assertTrue(!manager.isRunning(key));

            manager.exe(key, new TestManager.TestObserver() {
                @Override
                public void onCanceled() {
                    result(true);
                }

                @Override
                public void onComplete(String key) {
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
                public void onComplete(String key) {
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
                public void onComplete(String key) {
                    if (object.get() == null) {
                        object.set(key);
                    } else if (object.get() != key) {
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
                    count.decrementAndGet();
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
                    manager.unregister(pair.first);
                    if (!manager.isRunning(key)) {
                        int finalI = i;
                        mHandler.post(() -> {
                            if(count.get()!=0){
                                Log.d(TAG,"i:"+ finalI);
                            }
                            result(count.get() == 0);
                        });
                        break;
                    }
                }
            }
        }));
    }

    private void tagTest(final int scale) {
        Assert.assertTrue(runTest(() -> {
            TestManager manager = new TestManager();
            List<String> keys = new ArrayList<>();
            List<String> tags = new ArrayList<>();
            Map<String, Set<TestManager.TestObserver>> keyOb = new HashMap<>();
            Map<String, Set<TestManager.TestObserver>> tagOb = new HashMap<>();
            Map<TestManager.TestObserver, Pair<String, String>> obKeyTag = new HashMap<>();

            for (int i = 0; i < scale; ++i) {
                //check
                for (String tag : tags) {
                    for (TestManager.TestObserver ob : tagOb.get(tag)) {
                        Pair<String, String> keyTag = obKeyTag.get(ob);
                        Assert.assertTrue(tag.equals(keyTag.second));
                        Assert.assertTrue(keys.contains(keyTag.first));
                    }
                }
                for (String key : keys) {
                    for (TestManager.TestObserver ob : keyOb.get(key)) {
                        Pair<String, String> keyTag = obKeyTag.get(ob);
                        Assert.assertTrue(key.equals(keyTag.first));
                        Assert.assertTrue(tags.contains(keyTag.second));
                    }
                }

                String key;
                if (Math.random() * keys.size() <= ((double) i / 10)) {
                    key = "key" + i;
                    keys.add(key);
                    keyOb.put(key, new HashSet<>());
                } else {
                    key = keys.get((int) (Math.random() * keys.size()));
                }
                String tag;
                if (Math.random() * tags.size() <= ((double) i / 20)) {
                    tag = "tag" + i;
                    tags.add(tag);
                    tagOb.put(tag, new HashSet<>());
                } else {
                    tag = tags.get((int) (Math.random() * tags.size()));
                }
                TestManager.TestObserver ob = new TestManager.TestObserver() {
                    @Override
                    public void onCanceled() {
                        onFinally();
                    }

                    @Override
                    public void onComplete(String key) {
                        onFinally();
                    }

                    private void onFinally() {
                        Pair<String, String> keyTag = obKeyTag.remove(this);

                        String key = keyTag.first;
                        Set<TestManager.TestObserver> obs = keyOb.get(key);
                        obs.remove(this);
                        if (obs.isEmpty()) {
                            keyOb.remove(key);
                            keys.remove(key);
                        }

                        String tag = keyTag.second;
                        obs = tagOb.get(tag);
                        obs.remove(this);
                        if (obs.isEmpty()) {
                            tagOb.remove(tag);
                            tags.remove(tag);
                        }
                        check();
                    }

                    private void check() {
                        if (obKeyTag.isEmpty()) {
                            if (keys.isEmpty() && tags.isEmpty() && tagOb.isEmpty()) {
                                result(true);
                            } else {
                                Log.d(TAG, "keys: " + keys.size());
                                Log.d(TAG, "tags: " + tags.size());
                                Log.d(TAG, "tagOb: " + tagOb.size());
                                result(false);
                            }
                        }
                    }
                };
                tagOb.get(tag).add(ob);
                keyOb.get(key).add(ob);
                obKeyTag.put(ob, new Pair<>(key, tag));
                manager.exe(key, tag, ob);

                if (Math.random() * keys.size() > 5) {
                    int index = (int) (Math.random() * keys.size());
                    key = keys.get(index);
                    Assert.assertTrue(manager.cancel(key));
                }
                if (Math.random() * tags.size() > 3) {
                    int index = (int) (Math.random() * tags.size());
                    tag = tags.get(index);
                    Assert.assertTrue(manager.unregisterByTag(tag));
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }));
    }

    private static class TestManager extends TaskManager<TestManager.TestObserver> {
        static class TestObserver {
            public void onComplete(String key) {
            }

            public void onCanceled() {
            }
        }

        public int exe(String key, String tag, TestObserver observer) {
            return start(key, () -> new TestTask(key), tag, observer);
        }

        public int exe(String key, TestObserver observer) {
            return exe(key, null, observer);
        }

        class TestTask extends Task {
            String mKey;

            TestTask(String key) {
                super(key);
                mKey = key;
            }

            @Override
            protected void onCanceled() {
                for (TestObserver observer : getObservers()) {
                    if (observer != null) {
                        observer.onCanceled();
                    }
                }
            }

            @Override
            protected void onObserverUnregistered(TestObserver observer) {
                if (observer != null) {
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
                    for (TestObserver observer : getObservers()) {
                        if (observer != null)
                            observer.onComplete(mKey);
                    }
                });
            }
        }
    }
}
