package com.shxhzhxx.urlloader

import androidx.annotation.IntRange
import java.io.File

class UrlLoaderCacheEx(cachePath: File, @IntRange(from = 1) maxSize: Int) : DiskLruCacheEx(cachePath, maxSize) {

}