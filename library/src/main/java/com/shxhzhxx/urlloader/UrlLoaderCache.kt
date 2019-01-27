package com.shxhzhxx.urlloader

import androidx.annotation.IntRange
import java.io.File

class UrlLoaderCache(cachePath: File, @IntRange(from = 1) maxSize: Int, seed: String = cachePath.absolutePath) : FileLruCache(cachePath, maxSize) {
    private val suffixData = md5("UrlLoaderCache.suffixData$seed").takeLast(8)
    private val suffixHeader = md5("UrlLoaderCache.suffixHeader$seed").takeLast(8)
    override fun accept(dir: File?, name: String?) = name != null && name.endsWith(suffixData)
    override fun sizeOf(file: File) = (file.length() + findHeaderCache(file).length()).toInt()
    override fun onDelete(info: FileInfo) {
        findHeaderCache(info.file).delete()
        info.file.delete()
    }

    fun getHeaderCache(url: String) = getFile(url, suffixHeader)
    fun getDataCache(url: String) = getFile(url, suffixData)
    fun clearCache(url: String): Boolean {
        val cache = getDataCache(url)
        val bool = findHeaderCache(cache).delete()
        return remove(cache.name) != null || bool
    }

    fun findHeaderCache(dataCache: File) = File(dataCache.absolutePath.dropLast(suffixData.length) + suffixHeader)
}