package com.shxhzhxx.urlloader

import android.os.Handler
import android.util.Log
import java.util.concurrent.*

private val CORES = Runtime.getRuntime().availableProcessors()
private const val TAG = "TaskManager"

open class TaskManagerEx<T, V>(maxPoolSize: Int = CORES) {
    var maximumPoolSize = if (maxPoolSize <= 0) CORES else maxPoolSize
        set(value) {
            field = if (value <= 0) CORES else value
            threadPoolExecutor.corePoolSize = field
            threadPoolExecutor.maximumPoolSize = field
        }
    private val threadPoolExecutor = ThreadPoolExecutor(maximumPoolSize, maximumPoolSize, 60L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    val handler = Handler()
    private val keyTaskMap = HashMap<Any, Task>()
    private val idTaskMap = HashMap<Int, Task>()
    private val tagIdsMap = HashMap<Any, MutableSet<Int>>()
    private val idTagMap = HashMap<Int, Any>()

    fun cancelAll() {
        keyTaskMap.map { it.key }.forEach { key -> cancel(key) }
    }

    /**
     * cancel a task marked by key.
     * all observer will remain in [Task.observers], and [Task.onObserverUnregistered] won't be invoked for these observers.
     */
    fun cancel(key: Any) = keyTaskMap[key]?.unregisterAll() != null

    /**
     * unregister a observer marked by id,
     * Contrary to [cancel], removed observer will not remain in [Task.observers],
     * and [Task.onObserverUnregistered] will been invoked for this observer.
     */
    fun unregister(id: Int) = idTaskMap[id]?.unregisterObserver(id) != null

    fun unregisterByTag(tag: Any) = tagIdsMap[tag]?.toList()?.forEach { id -> unregister(id) } != null
    fun isRunning(key: Any) = keyTaskMap.containsKey(key)

    /**
     * join a task or start a new task.
     *
     * @param key      identification of task
     * @param builder  builder
     * @param tag      if not null, group requests with same tag so that you can easily cancel them by [unregisterByTag]
     * @param observer may be null. Identical instance can be passed multi times.
     * @return non-negative task id. this id may be reused after task is finished or canceled or observer is unregistered.
     */
    protected fun start(key: Any, builder: () -> Task, tag: Any? = null, observer: T? = null): Int {
        val id = kotlin.run {
            var i = 0
            while (idTaskMap.containsKey(i)) {
                ++i
            }
            return@run i
        }
        val task = keyTaskMap[key] ?: builder.invoke().also { t ->
            keyTaskMap[key] = t
            t.future = threadPoolExecutor.submit(t) as RunnableFuture<V?>
        }
        task.registerObserver(id, observer)
        if (tag != null) {
            idTagMap[id] = tag
            val ids = tagIdsMap[tag]
            if (ids != null) {
                ids.add(id)
            } else {
                tagIdsMap[tag] = HashSet(listOf(id))
            }
        }
        return id
    }

    protected fun syncStart(key: Any, builder: () -> Task, tag: Any? = null): V? {
        val task = synchronized(this) {
            return@synchronized keyTaskMap[key] ?: builder.invoke().also { t ->
                keyTaskMap[key] = t
                t.future = FutureTask(t)
            }
        }
        try {
            task.future.run()
        } catch (e: InterruptedException) {
            if (!task.isCanceled) {
                task.future = FutureTask(task)
            }
        }
        return task.future.get()
    }

    protected fun finalize() {
        threadPoolExecutor.shutdownNow()
    }

    private fun removeId(id: Int) {
        idTaskMap.remove(id)
        val tag = idTagMap.remove(id) ?: return
        val ids = tagIdsMap[tag]!!
        ids.remove(id)
        if (ids.isEmpty())
            tagIdsMap.remove(tag)
    }

    abstract inner class Task(val key: Any) : Callable<V?> {

        /**
         * get()
         * although canceled status can be checked by any thread,there is no guarantee that shortly after the last observer has unregistered itself
         * or after the task has been canceled,this method will always return true unless it invoked by main thread. (cancel is not a atomic operation)
         * actually, set canceled status to true is almost the last move when cancel a task.
         * <p>
         * check this method in worker thread to get a hint ,so that you can stop background work appropriately .
         *
         * private set()
         * setter called in two case:
         * 1.   all observer unregistered ,which [observers] return an empty list;
         * 2.   [cancel] been called, which [observers] return a list contains current observers.
         * <p>
         * [onCanceled] will be invoked, avoid running time-consuming tasks in these methods.
         */
        @Volatile
        var isCanceled = false
            private set(value) {
                if (!field && value) {//Cancel operation can only be performed once
                    field = true
                    future.cancel(true)
                    onCanceled()
                }
            }

        /**
         * might contains identical instance multi times.
         */
        val observers: Iterable<T?> get() = observerMap.map { it.value }

        /**
         * pass whatever you want to doInBackground (in main thread) after [doInBackground] has successfully finished (without cancel nor exception).
         * typically, you should call this method inside [doInBackground] as the result of task.
         * <p>
         * if this task is canceled, the runnable will not been invoked,
         * in this case, override [onCanceled] instead.
         * <p>
         * the reason that why you should pass result by this method instead of doing it yourself is,
         * you never know when is safe to return result to observers.
         * maybe an observer register itself after you return result to other observers and before the task has been cleared.
         * the new comer will die alone without any callback been invoked.
         */
        protected var postResult: Runnable? = null
        private val observerMap = HashMap<Int, T?>()
        lateinit var future: RunnableFuture<V?>

        final override fun call(): V? {
            if (isCanceled)
                return null
            return try {
                val result = doInBackground()
                runResult(postResult)
                result
            } catch (e: InterruptedException) {//外部取消
                if (!isCanceled) {

                }
                null
            } catch (e: Throwable) {
                Log.e(TAG, "Unhandled exception occurs in doInBackground: ${e.message}")
                runResult(null)
                null
            }
        }

        /**
         * if task has not start when [isCanceled] is set to true,then the task should never run.
         * in this circumstance, this method will never be invoked.
         */
        @Throws(InterruptedException::class)
        protected abstract fun doInBackground(): V

        /**
         * invoked when this task is canceled, in main thread.
         */
        protected open fun onCanceled() {
        }

        /**
         * Note: task which is canceled by [cancel] or [cancelAll],
         * technically its observers haven't been unregistered, and this func will not been invoked.
         *
         * @param observer ob canceled by [unregister] and [unregisterByTag]
         */
        protected open fun onObserverUnregistered(observer: T?) {

        }

        internal fun unregisterObserver(id: Int) {
            removeId(id)
            if (observerMap.containsKey(id)) {
                val observer = observerMap.remove(id)
                if (observerMap.isEmpty()) {
                    keyTaskMap.remove(key)
                    isCanceled = true
                }
                onObserverUnregistered(observer)
            }
        }

        internal fun unregisterAll() {
            clear()
            isCanceled = true
        }

        /**
         * @param id       task id
         * @param observer [observers] will return whatever this param is.
         */
        internal fun registerObserver(id: Int, observer: T?) {
            observerMap[id] = observer
            idTaskMap[id] = this
        }

        private fun runResult(result: Runnable?) {
            handler.post {
                if (!isCanceled) {
                    clear()
                    result?.run()
                }
            }
        }

        /**
         * remove this task's information from [keyTaskMap] and [idTaskMap]
         * keep the information of [observerMap] , so that you can return results to observers.
         */
        private fun clear() {
            keyTaskMap.remove(key)
            observerMap.map { it.key }.forEach { id -> removeId(id) }
        }

    }
}