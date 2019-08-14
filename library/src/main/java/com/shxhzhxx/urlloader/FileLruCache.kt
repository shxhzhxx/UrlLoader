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
import java.util.concurrent.TimeUnit

/**
 * Note:
 * Since the file event is passed in a specific thread,
 * 1.[size] is not guaranteed to be real-time accurate.
 * 2.Avoid file operations in [cachePath] during initialization.
 *
 * If a FileLruCache is garbage collected, it
 * will stop control cache size.  To ensure you keep controlling cache size, you must
 * keep a reference to the FileLruCache instance from some other live object.
 */
open class FileLruCache(private val cachePath: File, @IntRange(from = 1) maxSize: Int) : LruCache<String, FileInfo>(maxSize), FilenameFilter {
    private val msgDigest = MessageDigest.getInstance("MD5")
    private val events = LinkedBlockingQueue<FileEvent>()
    private val fileObserver = object : FileObserver(cachePath.absolutePath, OPEN or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE or CLOSE_NOWRITE) {
        //This method is invoked on a special FileObserver thread.
        override fun onEvent(event: Int, path: String?) {
            events.offer(FileEvent(event, path))
        }
    }
    private val thread = Thread {
        val init = LinkedHashMap<String, FileInfo>()
        cachePath.listFiles(this).map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }.forEach {
            init.remove(it.name)
            init[it.name] = FileInfo(it, sizeOf(it))
        }
        while (true) {
            val ev = try {
                //best effort to handle all pending events
                events.poll(100, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return@Thread
            } ?: break
            val path = ev.path
            if (path == null || !accept(cachePath, path))
                continue
            init.remove(path)
            init[path] = File(cachePath, path).let { file ->
                file.setLastModified(System.currentTimeMillis())
                FileInfo(file, sizeOf(file))
            }
        }
        /*
        * We expect that all events occurred while listing and
        * sorting existing files are passed and handled here.
        * but it's iffy. File events are passed in a specific thread,
        * and there is no guarantee that our hypothesis is valid.
        * */


        /*
        * Assuming put all files from init is atomic, which is apparently not the truth.
        * */
        init.values.forEach {
            put(it.file.name, it)
        }
        while (true) {
            val ev = try {
                events.take()
            } catch (e: InterruptedException) {
                return@Thread
            }
            val path = ev.path
            if (!accept(cachePath, path))
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

    override fun accept(dir: File?, name: String?) = name != null && File(dir, name).isFile

    init {
        if ((!cachePath.exists() || !cachePath.isDirectory) && !cachePath.mkdirs())
            throw IllegalArgumentException("FileLruCache create cachePath failed")
    }

    fun prepare() {
        fileObserver.startWatching()
        thread.start()
    }

    fun release() {
        fileObserver.stopWatching()
        thread.interrupt()
    }

    protected fun finalize() {
        release()
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

    open fun getFile(key: String, suffix: String? = null) = File(cachePath, md5(key) + (suffix
            ?: ""))

    protected open fun onDelete(info: FileInfo) {
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