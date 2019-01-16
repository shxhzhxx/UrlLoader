package com.shxhzhxx.urlloader

import android.os.Handler
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

open class MOTM<T, V>(maxPoolSize: Int = 0) {
    var maximumPoolSize = maxPoolSize
        set(value) {
            field = if (value <= 0) Integer.MAX_VALUE else value
            threadPoolExecutor.corePoolSize = field
            threadPoolExecutor.maximumPoolSize = field
        }
    private val threadPoolExecutor = ThreadPoolExecutor(maximumPoolSize, maximumPoolSize, 60L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val handler = Handler()
    private val keyTaskMap = HashMap<String, Task>()
    private val idTaskMap = HashMap<Int, Task>()
    private val tagIdsMap = HashMap<String, Set<Int>>()
    private val idTagMap = HashMap<Int, String>()

    protected fun start(key: String, builder: () -> Task, tag: String? = null, observer: T? = null): Int {
        val id = kotlin.run {
            var i = 0
            while (idTaskMap.containsKey(i++)) {
            }
            return@run i
        }
        val task = keyTaskMap[key] ?: builder.invoke().also { t ->
            t.executeInBackground()
        }
        return id
    }


    abstract inner class Task(val key: String) : Runnable {
        @Volatile
        var isCanceled = false
            set(value) {
                if (!field && value) {//Cancel operation can only be performed once
                    field = true
                    future?.cancel(true)
                    onCanceled()
                }
            }

        private var future: Future<*>? = null
        protected var postResult: Runnable? = null

        internal fun executeInBackground() {
            if (!isCanceled && future == null) {
                keyTaskMap[key] = this
                future = threadPoolExecutor.submit(this)
            }
        }

        override fun run() {

        }

        @Throws(Throwable::class)
        abstract fun execute(): V

        protected open fun onCanceled() {}
    }
}


class Observer {}
class MOTMA : MOTM<Observer, File>() {
    fun test() {
    }

    inner class TaskA(key: String) : Task(key) {
        override fun execute(): File {
            return File("")
        }

        override fun onCanceled() {

        }
    }
}