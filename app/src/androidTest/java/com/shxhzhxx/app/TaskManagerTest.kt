package com.shxhzhxx.app

import android.util.Log
import com.shxhzhxx.urlloader.TaskManagerEx
import kotlinx.coroutines.*
import org.junit.Assert
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "TaskManagerTest"

class TaskManagerTest {
    @Test
    fun cancelTest() {
        runBlocking(Dispatchers.Main) {
            val threadPool = Executors.newCachedThreadPool()
            repeat(1000) {
                Log.d(TAG, "cancelTest:$it")
                val manager = MyTaskManager()
                val key = "shxhzhxx"
                var canceled = false

                val asyncAssert = async {
                    delay((100 * Math.random()).toLong())
                    return@async if (canceled) true else suspendCoroutine { result ->
                        var asyncCanceled = false
                        val id = manager.asyncLoad(key, onComplete = {
                            result.resume(!canceled && !asyncCanceled)
                        }, onCanceled = {
                            result.resume(canceled || asyncCanceled)
                        })
                        Assert.assertTrue(id >= 0)
                        Assert.assertTrue(manager.isRunning(key))

                        launch {
                            delay((100 * Math.random()).toLong())
                            asyncCanceled = true
                            manager.unregister(id)
                        }
                    }
                }

                val syncAssert = async {
                    delay((100 * Math.random()).toLong())
                    var syncCanceled = false
                    val task = threadPool.submit {
                        //同步方法的取消操作无法保证返回值一定为null，所以这里不做判断
                        //如果delay的时间够短，这里可能都不会被运行到
                        manager.syncLoad(key, { syncCanceled })
                    }
                    delay((100 * Math.random()).toLong())
                    syncCanceled = true
                    task.cancel(true)
                    return@async true
                }
                val job = launch {
                    delay((200 * Math.random()).toLong())
                    canceled = true
                    manager.cancel(key)
                }
                Assert.assertTrue(asyncAssert.await() && syncAssert.await())
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun complicateTest() {
        
    }
}

class MyCallback(
        val onComplete: ((String) -> Unit)? = null,
        val onCanceled: (() -> Unit)? = null,
        val onProgress: ((progress: Int) -> Unit)? = null
)

class MyTaskManager : TaskManagerEx<MyCallback, String>() {
    fun syncLoad(key: String, canceled: () -> Boolean, onProgress: ((Int) -> Unit)? = null) =
            syncStart(key, { MyTask(key) }, canceled, MyCallback(onProgress = onProgress))

    fun asyncLoad(key: String, tag: Any? = null, onComplete: ((String) -> Unit)? = null,
                  onCanceled: (() -> Unit)? = null,
                  onProgress: ((progress: Int) -> Unit)? = null) =
            asyncStart(key, { MyTask(key) }, tag, MyCallback(onComplete, onCanceled, onProgress))

    private inner class MyTask(private val myKey: String) : Task(myKey) {
        override fun doInBackground(): String? {
            repeat((10 * Math.random()).toInt()) { progress ->
                observers.forEach { it?.onProgress?.invoke(progress) }
                Thread.sleep((Math.random() * 20).toLong())
            }
            val result = md5(myKey)
            postResult = Runnable {
                asyncObservers.forEach {
                    it?.onComplete?.invoke(result)
                }
            }
            return result
        }

        override fun onCanceled() {
            asyncObservers.forEach { it?.onCanceled?.invoke() }
        }

        override fun onObserverUnregistered(observer: MyCallback?) {
            observer?.onCanceled?.invoke()
        }
    }
}

fun md5(raw: String) = BigInteger(1, MessageDigest.getInstance("MD5").digest(raw.toByteArray())).toString(16).padStart(32, '0')