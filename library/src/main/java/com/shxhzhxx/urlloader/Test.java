package com.shxhzhxx.urlloader;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlin.jvm.functions.Function0;

public class Test extends TaskManager<Test.MyCallback> {

    void test(){
        start("", new Function0<MyTask>() {
            @Override
            public MyTask invoke() {
                return new MyTask("");
            }
        },"",null);
    }

    static class MyCallback {
    }

    class MyTask extends TaskManager<Test.MyCallback>.Task {
        public MyTask(@NotNull Object $outer) {
            super($outer);
        }

        @Override
        protected void onObserverUnregistered(@Nullable MyCallback observer) {
            super.onObserverUnregistered(observer);
        }

        @Override
        protected void doInBackground() {

        }
    }
}
