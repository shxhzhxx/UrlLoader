package com.shxhzhxx.urlloader

import android.os.FileObserver
import android.os.FileObserver.*
import android.util.LruCache
import androidx.annotation.AnyThread
import androidx.annotation.IntRange
import java.io.File
import java.io.FilenameFilter
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "DiskLruCacheKt"

open class DiskLruCacheEx(private val cachePath: File, @IntRange(from = 1) maxSize: Int) : LruCache<String, FileInfo>(maxSize), FilenameFilter {
    private val msgDigest = MessageDigest.getInstance("MD5")
    private val events = LinkedBlockingQueue<FileEvent>()
    private val fileObserver = object : FileObserver(cachePath.absolutePath, OPEN or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE or CLOSE_NOWRITE) {
        //This method is invoked on a special FileObserver thread.
        override fun onEvent(event: Int, path: String?) {
            events.offer(FileEvent(event, path))
        }
    }
    private val thread = Thread {
        cachePath.listFiles(this).map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }.forEach {
            put(it.name, FileInfo(it, sizeOf(it)))
        }
        while (true) {
            val ev = try {
                events.take()
            } catch (e: InterruptedException) {
                break
            }
            val path = ev.path
            if (path == null || !accept(cachePath, path))
                continue
            when (ev.event) {
                MOVED_FROM, DELETE -> remove(path)
                OPEN, MOVED_TO, CLOSE_WRITE, CLOSE_NOWRITE -> {
                    val file = File(cachePath, path)
                    val info = FileInfo(file, sizeOf(file))
                    /*
                     * Read operation will not update file's lastModified.
                     * Once we update file's lastModified manually here, we need to do it for all operations to keep relative order of files,
                     * regardless of whether it is a read operation.
                     * */
                    file.setLastModified(System.currentTimeMillis())
                    put(path, info)
                }
            }
        }
    }

    override fun accept(dir: File?, name: String?) = File(dir, name).isFile

    init {
        if ((!cachePath.exists() || !cachePath.isDirectory) && !cachePath.mkdirs())
            throw IllegalArgumentException("DiskLruCache create cachePath failed")

        fileObserver.startWatching()
        thread.start()
    }

    fun release() {
        fileObserver.stopWatching()
        thread.interrupt()
    }

    /**
     * @param file is already checked by [accept]
     */
    @AnyThread
    @IntRange(from = 0)
    protected open fun sizeOf(file: File) = file.length().toInt()

    @IntRange(from = 0)
    override fun sizeOf(key: String?, value: FileInfo?) = value?.size ?: 0

    @Synchronized
    fun md5(raw: String) = BigInteger(1, msgDigest.digest(raw.toByteArray())).toString(16).padStart(32, '0')

    fun getFile(key: String) = getFile(key, null)

    protected fun getFile(key: String, suffix: String?) = File(cachePath, "${md5(key)}${if (suffix.isNullOrBlank()) "" else ".$suffix"}")

    protected fun onDelete(info: FileInfo) {
        info.file.delete()
    }

    override fun entryRemoved(evicted: Boolean, key: String?, oldValue: FileInfo?, newValue: FileInfo?) {
        if (newValue == null && oldValue != null) {
            onDelete(oldValue)
        }
    }
}

data class FileInfo(val file: File, @IntRange(from = 0) val size: Int)

data class FileEvent(val event: Int, val path: String?)
