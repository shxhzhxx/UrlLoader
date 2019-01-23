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
        val init = LinkedHashMap<String, File>()
        cachePath.listFiles(this).map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }.forEach {
            init.remove(it.name)
            init[it.name] = it
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
            init[path] = File(cachePath, path)
        }
        /*
        * We expect that all events occurred while listing and
        * sorting existing files are passed and handled here.
        * but it's iffy. File events are passed in a specific thread,
        * and there is no guarantee that our hypothesis is valid.
        * */
        init.values.forEach {
            put(it.name, FileInfo(it, sizeOf(it)))
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

    fun prepare(){
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

/*
 * 关于多线程文件操作环境下实例正确初始化的分析：
 *
 * 先定义“正确”的标准：主线程初始化实例完成且事件回调线程里的所有事件都处理完毕后，
 *                       ·记录了指定目录下所有符合条件的文件；
 *                       ·记录的文件大小与真实大小相符；
 *                      ·记录的文件顺序是按最后修改时间从小到大排序的。
 *
 * FileObserver基于linux的inotify，查看inotify的man-pages得到如下有用的信息：
 *   ·未读的同样事件会被底层合并（连续？）
 *   ·事件队列有上限，超出后事件会被丢弃（忽略）
 *   ·事件顺序有保证
 *
 * 主线程的执行顺序是 上锁->注册监听->读取目录当前状态（细分为先读取文件列表files，获取files里每个文件的最后修改时间，
 *                                                       根据时间排序files，获取files里每个文件的大小。  注意这些操作都不是原子性的）->释放锁
 *
 *
 * 证明：记录了指定目录下所有符合条件的文件
 *       这个命题可以分割成针对每个文件的独立子命题。 子命题：该文件如果符合条件，那么一定被记录在内存中。
 *       如果所有子命题成立，则这个命题成立。
 *       在主线程完成初始化并释放锁后，所有被挂起的事件会按序执行，如果文件在主线程上锁后有被修改，那么最后一个事件决定了内存中的记录状态（每次处理事件都会覆盖之前的状态），
 *       同时由于事件按序传递，最后一个事件也代表了文件的实际状态。
 *       如果文件在主线程上锁后没有被修改，那么上锁后读取的文件状态就是文件的实际状态。
 *       命题成立。
 *
 * 证明：记录的文件大小与真实大小相符
 *       证明同上。
 *       命题成立。
 *
 * 证明：记录的文件顺序是按最后修改时间从小到大排序的
 *       将所有文件分为两类，从注册监听开始到所有事件处理完毕为止没有被修改的文件为集合A，有被修改的为集合B。
 *       对于集合A，主线程读取的最后修改时间有效，最后排序结果是正确的相对顺序。
 *       对于集合B，所有文件都至少有一个相对应的事件等待处理，其中最后一个事件决定了该文件在LRU队列中的位置，
 *       同时由于事件按序传递，最后一个事件也代表了文件的实际顺序。集合B的文件（在LRU队列中的位置）全都在集合A的后面。
 *
 *
 * 从上面的证明可以看出，对任意文件，只需要处理队列中与其相关的最后一个事件，其他的都可以忽略。
 *
 * */

