package com.shxhzhxx.app

import android.util.Log
import com.shxhzhxx.urlloader.TaskManagerEx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "TaskManagerTest"

class TaskManagerTest {
    @Test
    fun cancelTest() {
        repeat(100) {
            runBlocking(Dispatchers.Main) {
                val manager = MyTaskManager()
                val key = "shxhzhxx"
                val id = manager.asyncLoad(key)
                Assert.assertTrue(id >= 0)
                Assert.assertTrue(manager.isRunning(key))
                Assert.assertTrue(manager.unregister(id))
                Assert.assertTrue(!manager.isRunning(key))

                Assert.assertTrue(suspendCoroutine { result ->
                    var canceled = false
                    manager.asyncLoad(key, onComplete = {
                        result.resume(!canceled)
                    }, onCanceled = {
                        result.resume(canceled)
                    })
                    launch {
                        delay((100 * Math.random()).toLong())
                        canceled = true
                        manager.cancel(key)
                    }
                })
            }
        }
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
                asyncObservers.forEach { it?.onComplete?.invoke(result) }
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