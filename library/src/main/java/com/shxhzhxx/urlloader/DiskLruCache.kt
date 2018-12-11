package com.shxhzhxx.urlloader

import android.os.FileObserver
import android.util.LruCache
import androidx.annotation.AnyThread
import androidx.annotation.IntRange
import java.io.File
import java.io.FilenameFilter
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock


open class DiskLruCacheKt(private val cachePath: File, @IntRange(from = 1) maxSize: Int) : LruCache<String, FileInfo>(maxSize), FilenameFilter {
    private val msgDigest: MessageDigest
    private val fileObserver: FileObserver

    init {
        if ((!cachePath.exists() || !cachePath.isDirectory) && !cachePath.mkdirs())
            throw IllegalArgumentException("DiskLruCache create cachePath failed")
        try {
            msgDigest = MessageDigest.getInstance("MD5")
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException("initialize DiskLruCache failed, " + e.message)
        }

        var lock: Lock? = ReentrantLock()
        lock?.lock()
        fileObserver = object : FileObserver(cachePath.absolutePath,
                OPEN or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE or CLOSE_NOWRITE) {
            /**
             * This method is invoked on a special FileObserver thread.
             * */
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !accept(cachePath, path))
                    return
                lock?.lock()
                when (event) {
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
                lock?.unlock()
                lock = null
            }
        }
        fileObserver.startWatching()

        @Suppress("LeakingThis")
        cachePath.listFiles(this).map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }.forEach {
            put(it.name, FileInfo(it, sizeOf(it)))
        }
        lock?.unlock()
    }

    override fun accept(dir: File?, name: String?) = File(dir, name).isFile

    fun release() {
        fileObserver.stopWatching()
    }

    /**
     * @param file is already checked by [accept]
     */
    @AnyThread
    @IntRange(from = 0)
    protected fun sizeOf(file: File) = file.length().toInt()

    @IntRange(from = 0)
    override fun sizeOf(key: String?, value: FileInfo?) = value?.size ?: 0

    @Synchronized
    fun md5(raw: String) = BigInteger(1, msgDigest.digest(raw.toByteArray())).toString(16).padStart(32, '0')

    fun getFile(key: String) = getFile(key, null)

    protected fun getFile(key: String, suffix: String?) = File(cachePath, "${md5(key)}${if (suffix.isNullOrBlank()) null else ".$suffix"}")
}

data class FileInfo(val file: File, @IntRange(from = 0) val size: Int)