package com.shxhzhxx.app

import android.util.Log
import androidx.test.InstrumentationRegistry
import com.shxhzhxx.urlloader.FileLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "FileLruCacheTest"

class FileLruCacheTest {
    private val cachePath by lazy { InstrumentationRegistry.getTargetContext().cacheDir }
    private fun deletePath(path: File): Boolean {
        if (path.isFile)
            return path.delete()
        for (child in path.listFiles()) {
            if (!deletePath(child))
                return false
        }
        return true
    }

    private fun writeFile(name: String) = writeFile(File(cachePath, name))

    private fun writeFile(file: File): Int {
        val len = (Math.random() * 1024 + 100).toInt()
        FileOutputStream(file).apply {
            write(ByteArray(len))
            close()
        }
        return len
    }

    @Test
    fun initTest() {
        runBlocking(Dispatchers.Main) {
            repeat(100) outer@{ repeat ->
                Log.d(TAG, "initTest: $repeat")
                deletePath(cachePath)
                val scale = (2000 * Math.random()).toInt()
                repeat(scale) { writeFile(it.toString()) }
                val addThread = object : Thread() {
                    override fun run() {
                        for (i in scale..scale * 2) {
                            if (isInterrupted)
                                return
                            writeFile(i.toString())
                        }
                    }
                }
                val editThread = object : Thread() {
                    override fun run() {
                        while (!isInterrupted) {
                            writeFile((scale * Math.random()).toInt().toString())
                        }
                    }
                }
                addThread.start()
                editThread.start()

                val fileLruCache = FileLruCache(cachePath, 30 * 1024 * 1024)
                fileLruCache.prepare()
                addThread.interrupt()
                editThread.interrupt()

                repeat(10) {
                    delay(Math.max((scale / 5).toLong(), 100))
                    val size = cachePath.listFiles().sumBy { it.length().toInt() }
                    val cacheSize = fileLruCache.size()
                    if (size == cacheSize) {
                        fileLruCache.release()
                        Assert.assertTrue(true)
                        return@outer
                    }
                    Log.d(TAG, "scale: $scale")
                    Log.d(TAG, "size: $size")
                    Log.d(TAG, "cacheSize: $cacheSize")
                }
                Assert.assertTrue(false)
            }
        }
    }

    @Test
    fun lruTest() {
        runBlocking(Dispatchers.Main) {
            repeat(100) outer@{ repeat ->
                Log.d(TAG, "lruTest:$repeat")
                deletePath(cachePath)
                val scale = (2000 * Math.random()).toInt() + 1
                val max = scale * 1024
                val fileLruCache = FileLruCache(cachePath, max)
                fileLruCache.prepare()

                val sizes = mutableListOf<Pair<String, Int>>()
                repeat(scale * 3) { i ->
                    sizes.add(i.toString() to writeFile(fileLruCache.getFile(i.toString())))
                    if (Math.random() > 0.5) {
                        val index = (Math.random() * i).toInt()
                        if (sizes.drop(index).sumBy { it.second } <= max) {
                            val pair = sizes.removeAt(index)
                            sizes.add(pair)
                            FileInputStream(fileLruCache.getFile(pair.first)).close()
                        }
                    }
                }

                var expectedSize = 0
                for (pair in sizes.asReversed()) {
                    if (expectedSize + pair.second > max)
                        break
                    expectedSize += pair.second
                }
                repeat(10) {
                    delay(Math.max((scale / 3).toLong(), 200))
                    val cacheSize = fileLruCache.size()
                    val actualSize = cachePath.listFiles().sumBy { it.length().toInt() }
                    if (expectedSize == cacheSize && expectedSize == actualSize) {
                        fileLruCache.evictAll()
                        val size = fileLruCache.size()
                        val files = cachePath.listFiles().size
                        if (size != 0 || files != 0) {
                            Log.d(TAG, "size: $size")
                            Log.d(TAG, "files: $files")
                            Assert.assertTrue(false)
                        } else {
                            fileLruCache.release()
                            Assert.assertTrue(true)
                        }
                        return@outer
                    }
                    Log.d(TAG, "max: $max")
                    Log.d(TAG, "expectedSize: $expectedSize")
                    Log.d(TAG, "cacheSize: $cacheSize")
                    Log.d(TAG, "actualSize: $actualSize")
                }
                Assert.assertTrue(false)
            }
        }
    }
}