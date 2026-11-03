package com.github.topdeepsk.androidjavashell.core;

import android.os.Build;

import java.io.File;

/**
 * 与子进程无关的零碎工具：位宽判断、可执行文件查找。
 */
public final class ShellUtils {
    private ShellUtils() {
    }

    public static boolean is64Bit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.os.Process.is64Bit();
        }
        var arch = System.getProperty("os.arch");
        return arch != null && arch.contains("64");
    }

    public static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    public static File getExecutablePath(String... executableNames) {
        if (executableNames == null) {
            return null;
        }
        var path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return null;
        }
        var directorys = path.split(":");
        for (var name : executableNames) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (name.indexOf('/') >= 0) {
                var file = new File(name);
                if (isExecutable(file)) {
                    return file;
                }
                continue;
            }
            for (var directory : directorys) {
                var candidate = new File(directory, name);
                if (isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isExecutable(File file) {
        return file.isFile() && file.canExecute();
    }
}
