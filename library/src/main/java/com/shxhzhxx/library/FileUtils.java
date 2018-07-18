package com.shxhzhxx.library;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public abstract class FileUtils {
    public static boolean copyFile(File src, File dst) {
        if (dst.exists())
            if (!dst.delete())
                return false;
        try {
            FileChannel in = new FileInputStream(src).getChannel();
            FileChannel out = new FileOutputStream(dst).getChannel();
            out.transferFrom(in, 0, in.size());
            in.close();
            out.close();
            return true;
        } catch (IOException ignore) {
            return false;
        }
    }
}
