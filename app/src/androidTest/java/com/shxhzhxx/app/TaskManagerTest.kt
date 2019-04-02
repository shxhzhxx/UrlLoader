package com.shxhzhxx.app

import android.util.Log
import com.shxhzhxx.urlloader.TaskManager
import kotlinx.coroutines.*
import org.junit.Assert
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.HashMap
import kotlin.collections.HashSet

private const val TAG = "TaskManagerTest"

class TaskManagerTest {
    @Test
    fun simulation() {
        val thread = Thread.currentThread()
        fun testFailed() {
            thread.uncaughtExceptionHandler.uncaughtException(thread, RuntimeException("test failed"))
        }
        runBlocking(Dispatchers.Main) {
            repeat(10000) outer@{ loop ->
                Log.d(TAG, "complicateTest:$loop")
                val manager = MySuperTaskManager()
                val keys = mutableSetOf<String>()
                fun getKey() = if (Math.random() * keys.size > 10) {
                    keys.random()
                } else {
                    UUID.randomUUID().toString().also { keys.add(it) }
                }

                val tasks = HashMap<String, MutableSet<Int>>()
                val tags = HashMap<String, MutableSet<Int>>()
                var cntCompleted = 0
                var cntCanceled = 0
                var cntCancel = 0
                var cntTotal = 0
                val job = launch {
                    while (isActive) {
                        delay((50 * Math.random()).toLong())
                        cntTotal++
                        val key = getKey()
                        val tag = if (Math.random() * tags.size > 5) {
                            tags.keys.random()
                        } else {
                            UUID.randomUUID().toString()
                        }
                        val id = AtomicInteger()
                        id.set(manager.asyncLoad(key, onLoad = {
                            debugLog("onLoad:$id    $key   $tag")
                            cntCompleted++
                            val taskIds = tasks[key]
                            val tagIds = tags[tag]
                            if (taskIds == null || tagIds == null || !taskIds.contains(id.get()) || !tagIds.contains(id.get())) {
                                testFailed()
                                return@asyncLoad
                            }
                            taskIds.remove(id.get())
                            if (taskIds.isEmpty()) {
                                tasks.remove(key)
                            }
                            tagIds.remove(id.get())
                            if (tagIds.isEmpty()) {
                                tags.remove(tag)
                            }
                        }, onCancel = {
                            debugLog("onCancel:$id    $key   $tag")
                            cntCanceled++
                            if (tasks[key]?.contains(id.get()) == true && tags[tag]?.contains(id.get()) == true) {
                                testFailed()
                            }
                            tasks[key]?.let {
                                it.remove(id.get())
                                if (it.isEmpty())
                                    tasks.remove(key)
                            }
                            tags[tag]?.let {
                                it.remove(id.get())
                                if (it.isEmpty())
                                    tags.remove(tag)
                            }
                        }, onFailure = {
                            //inner cancel always combined with outer cancel
                            testFailed()
                        }, tag = tag))
                        debugLog("start:$id    $key   $tag")
                        (tasks[key] ?: HashSet<Int>().also { tasks[key] = it }).add(id.get())
                        (tags[tag] ?: HashSet<Int>().also { tags[tag] = it }).add(id.get())
                    }
                }

                val innerTasks = HashMap<String, MutableSet<Int>>()
                val innerTags = HashMap<String, MutableSet<Int>>()
                var cntInnerCompleted = 0
                var cntInnerCanceled = 0
                var cntInnerCancel = 0
                var cntInnerTotal = 0
                val innerJob = launch {
                    while (isActive) {
                        delay((50 * Math.random()).toLong())
                        cntInnerTotal++
                        val key = getKey()
                        val tag = if (Math.random() * innerTags.size > 5) {
                            innerTags.keys.random()
                        } else {
                            UUID.randomUUID().toString()
                        }
                        val id = AtomicInteger()
                        id.set(manager.innerTaskManager.asyncLoad(key, onLoad = {
                            debugLog("inner onLoad:$id    $key   $tag")
                            cntInnerCompleted++
                            val taskIds = innerTasks[key]
                            val tagIds = innerTags[tag]
                            if (taskIds == null || tagIds == null || !taskIds.contains(id.get()) || !tagIds.contains(id.get())) {
                                debugLog("key:$key")
                                debugLog("tag:$tag")
                                debugLog("id:${id.get()}")
                                if (taskIds == null)
                                    debugLog("taskIds==null")
                                if (tagIds == null)
                                    debugLog("tagIds==null")
                                testFailed()
                                return@asyncLoad
                            }
                            taskIds.remove(id.get())
                            if (taskIds.isEmpty()) {
                                innerTasks.remove(key)
                            }
                            tagIds.remove(id.get())
                            if (tagIds.isEmpty()) {
                                innerTags.remove(tag)
                            }
                        }, onCancel = {
                            debugLog("inner onCancel:$id    $key   $tag")
                            cntInnerCanceled++
                            if (innerTasks[key]?.contains(id.get()) == true && innerTags[tag]?.contains(id.get()) == true) {
                                testFailed()
                            }
                            innerTasks[key]?.let {
                                it.remove(id.get())
                                if (it.isEmpty())
                                    innerTasks.remove(key)
                            }
                            innerTags[tag]?.let {
                                it.remove(id.get())
                                if (it.isEmpty())
                                    innerTags.remove(tag)
                            }
                        }, tag = tag))
                        debugLog("inner start:$id    $key   $tag")
                        (innerTasks[key]
                                ?: HashSet<Int>().also { innerTasks[key] = it }).add(id.get())
                        (innerTags[tag]
                                ?: HashSet<Int>().also { innerTags[tag] = it }).add(id.get())
                    }
                }

                val job3 = launch {
                    val actions = listOf(
                            {
                                val key = if (innerTasks.isEmpty()) return@listOf else innerTasks.keys.random()
                                val taskIds = innerTasks[key]
                                if (taskIds.isNullOrEmpty()) {
                                    testFailed()
                                    return@listOf
                                }
                                val id = taskIds.random()
                                debugLog("unregister inner:   $id  $key")
                                taskIds.remove(id)
                                if (taskIds.isEmpty())
                                    innerTasks.remove(key)
                                manager.innerTaskManager.unregister(id)
                                cntInnerCancel++
                            },
                            {
                                val key = if (tasks.isEmpty()) return@listOf else tasks.keys.random()
                                val taskIds = tasks[key]
                                if (taskIds.isNullOrEmpty()) {
                                    testFailed()
                                    return@listOf
                                }
                                val id = taskIds.random()
                                debugLog("unregister:   $id  $key")
                                taskIds.remove(id)
                                if (taskIds.isEmpty())
                                    tasks.remove(key)
                                manager.unregister(id)
                                cntCancel++
                            },
                            {
                                val key = if (keys.isEmpty()) return@listOf else keys.random()
                                debugLog("cancel:$key")
                                tasks.remove(key)?.also {
                                    cntCancel += it.size
                                    manager.cancel(key)
                                }
                                innerTasks.remove(key)?.also {
                                    cntInnerCancel += it.size
                                    manager.innerTaskManager.cancel(key)
                                }
                            },
                            {
                                val tag = if (tags.isEmpty()) return@listOf else tags.keys.random()
                                val tagIds = tags.remove(tag)
                                if (tagIds.isNullOrEmpty()) {
                                    testFailed()
                                    return@listOf
                                }
                                debugLog("unregister tag:$tag")
                                manager.unregisterByTag(tag)
                                cntCancel += tagIds.size
                            },
                            {
                                val tag = if (innerTags.isEmpty()) return@listOf else innerTags.keys.random()
                                val tagIds = innerTags.remove(tag)
                                if (tagIds.isNullOrEmpty()) {
                                    testFailed()
                                    return@listOf
                                }
                                debugLog("unregister tag inner:$tag")
                                manager.innerTaskManager.unregisterByTag(tag)
                                cntInnerCancel += tagIds.size
                            }
                    )
                    while (isActive) {
                        delay((50 * Math.random()).toLong())
                        actions.random().invoke()
                    }
                }
                delay((10000 * Math.random()).toLong())
                job.cancel()
                innerJob.cancel()
                job3.cancel()

                repeat(10) {
                    delay(100)
                    if (cntCancel == cntCanceled &&
                            cntCanceled + cntCompleted == cntTotal &&
                            cntInnerCancel == cntInnerCanceled &&
                            cntInnerCanceled + cntInnerCompleted == cntInnerTotal) {
                        return@outer
                    }
                    Log.d(TAG, "cntCancel:$cntCancel")
                    Log.d(TAG, "cntCanceled:$cntCanceled")
                    Log.d(TAG, "cntCompleted:$cntCompleted")
                    Log.d(TAG, "cntTotal:$cntTotal")
                    Log.d(TAG, "cntInnerCancel:$cntInnerCancel")
                    Log.d(TAG, "cntInnerCanceled:$cntInnerCanceled")
                    Log.d(TAG, "cntInnerCompleted:$cntInnerCompleted")
                    Log.d(TAG, "cntInnerTotal:$cntInnerTotal")
                }
                Assert.assertTrue(false)
            }
        }
    }
}

private fun debugLog(msg: String) {
//    Log.d(TAG, msg)
}

class MyCallback(
        val onLoad: ((String) -> Unit)? = null,
        val onCancel: (() -> Unit)? = null,
        val onProgress: ((progress: Int) -> Unit)? = null,
        val onFailure: (() -> Unit)? = null
)

class MyTaskManager : TaskManager<MyCallback, String>() {
    fun syncLoad(key: String, canceled: () -> Boolean, onProgress: ((Int) -> Unit)? = null) =
            syncStart(key, { MyTask(key) }, canceled, MyCallback(onProgress = onProgress))

    fun asyncLoad(key: String, tag: Any? = null, onLoad: ((String) -> Unit)? = null,
                  onCancel: (() -> Unit)? = null,
                  onProgress: ((progress: Int) -> Unit)? = null,
                  onFailure: (() -> Unit)? = null) =
            asyncStart(key, { MyTask(key) }, tag, MyCallback(onLoad, onCancel, onProgress, onFailure))

    private inner class MyTask(private val myKey: String) : Task(myKey) {
        override fun doInBackground(): String? {
            repeat((10 * Math.random()).toInt()) { progress ->
                handler.post { observers.forEach { it?.onProgress?.invoke(progress) } }
                Thread.sleep((Math.random() * 20).toLong())
            }
            val result = md5(myKey)
            postResult = Runnable {
                asyncObservers.forEach {
                    it?.onLoad?.invoke(result)
                }
            }
            return result
        }

        override fun onCancel() {
            asyncObservers.forEach { it?.onCancel?.invoke() }
        }

        override fun onObserverUnregistered(observer: MyCallback?) {
            observer?.onCancel?.invoke()
        }
    }
}

class MySuperTaskManager : TaskManager<MyCallback, String>() {

    val innerTaskManager = MyTaskManager()

    fun syncLoad(key: String, canceled: () -> Boolean, onProgress: ((Int) -> Unit)? = null) =
            syncStart(key, { MySuperTask(key) }, canceled, MyCallback(onProgress = onProgress))

    fun asyncLoad(key: String, tag: Any? = null, onLoad: ((String) -> Unit)? = null,
                  onCancel: (() -> Unit)? = null,
                  onProgress: ((progress: Int) -> Unit)? = null,
                  onFailure: (() -> Unit)? = null) =
            asyncStart(key, { MySuperTask(key) }, tag, MyCallback(onLoad, onCancel, onProgress, onFailure))


    private inner class MySuperTask(private val myKey: String) : Task(myKey) {

        override fun doInBackground(): String? {
            val result = innerTaskManager.syncLoad(myKey, { isCancelled })
            postResult = if (result == null) Runnable {
                asyncObservers.forEach {
                    it?.onFailure?.invoke()
                }
            } else Runnable {
                asyncObservers.forEach {
                    it?.onLoad?.invoke(result)
                }
            }
            return result
        }

        override fun onCancel() {
            asyncObservers.forEach { it?.onCancel?.invoke() }
        }

        override fun onObserverUnregistered(observer: MyCallback?) {
            observer?.onCancel?.invoke()
        }
    }
}

fun md5(raw: String) = BigInteger(1, MessageDigest.getInstance("MD5").digest(raw.toByteArray())).toString(16).padStart(32, '0')