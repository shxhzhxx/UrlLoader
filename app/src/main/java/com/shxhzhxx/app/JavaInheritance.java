package com.shxhzhxx.app;

import com.shxhzhxx.urlloader.TaskManager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlin.jvm.functions.Function0;

public class JavaInheritance extends TaskManager<JavaInheritance.MyCallback> {
    void func() {
        Object key = new Object();
        start(key, (Function0<MyTask>) () -> new MyTask(key), null, null);
    }

    static class MyCallback {
        void onCallback() {
        }
    }

    class MyTask extends TaskManager<JavaInheritance.MyCallback>.Task {
        public MyTask(@NotNull Object $outer) {
            super($outer);
        }

        @Override
        protected void onObserverUnregistered(@Nullable MyCallback observer) {
            if (observer != null)
                observer.onCallback();
        }

        @Override
        protected void onCanceled() {
            for (MyCallback observer : getObservers()) {
                if (observer != null)
                    observer.onCallback();
            }
        }

        @Override
        protected void doInBackground() {
            try {
                Thread.sleep(10000);
                setPostResult(() -> {
                    for (MyCallback observer : getObservers()) {
                        if (observer != null)
                            observer.onCallback();
                    }
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
                setPostResult(() -> {
                    for (MyCallback observer : getObservers()) {
                        if (observer != null)
                            observer.onCallback();
                    }
                });
            }
        }
    }
}