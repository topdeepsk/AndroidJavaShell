package com.github.topdeepsk.androidjavashell.core;

import android.system.Os;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class JavaShellProcess implements Closeable {

    private final Process process;

    private final String niceName;

    private final boolean rootMode;

    private final File payloadFile;

    private final File guardPipe;

    private final FileDescriptor guardHandle;

    private JavaShellListener listener;

    private BufferedReader stdoutReader;

    private BufferedReader stderrReader;

    private Thread stdoutThread;

    private Thread stderrThread;

    private boolean stdinClosed;

    private boolean resourcesReleased;

    private boolean closed;

    JavaShellProcess(Process process, String niceName, boolean rootMode, File payloadFile, File guardPipe, FileDescriptor guardHandle) {
        this.process = process;
        this.niceName = niceName;
        this.rootMode = rootMode;
        this.payloadFile = payloadFile;
        this.guardPipe = guardPipe;
        this.guardHandle = guardHandle;
    }

    public boolean isRootMode() {
        return rootMode;
    }

    public String getNiceName() {
        return niceName;
    }

    public synchronized OutputStream getStdin() {
        if (stdinClosed) {
            throw new JavaShellArgumentException("stdin has been closed");
        }
        return process.getOutputStream();
    }

    public synchronized void closeStdin() {
        if (stdinClosed) {
            return;
        }
        stdinClosed = true;
        closeQuietly(process.getOutputStream());
    }

    public synchronized JavaShellProcess setListener(JavaShellListener listener) {
        if (closed) {
            throw new JavaShellArgumentException("process has been closed");
        }
        if (this.listener != null) {
            throw new JavaShellArgumentException("callback has already been set");
        }
        this.listener = listener;
        if (listener == null) {
            return this;
        }
        stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        stdoutThread = startReaderThread(stdoutReader, true);
        stderrThread = startReaderThread(stderrReader, false);
        startExitWatcher();
        return this;
    }

    public int waitFor() throws InterruptedException {
        int code = process.waitFor();
        releaseResources();
        return code;
    }

    public boolean waitFor(long timeoutMillis) {
        long startTime = System.nanoTime();
        long rem = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            try {
                exitValue();
                releaseResources();
                return true;
            } catch(IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            rem = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }

    public boolean isAlive() {
        return ShellUtils.isAlive(process);
    }

    public int exitValue() throws IllegalThreadStateException {
        return process.exitValue();
    }

    public void destroy() {
        if (isAlive()) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
        }
        releaseResources();
        if (isAlive()) {
            waitFor(1000);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(stdoutReader);
        closeQuietly(stderrReader);
        stdoutReader = null;
        stderrReader = null;
        closeStdin();
    }

    private Thread startReaderThread(final BufferedReader reader, final boolean stdout) {
        var thread = new Thread(() -> {
            var callback = listener;
            try {
                String line;
                while ((line = readLine(reader, true)) != null) {
                    try {
                        if (stdout) {
                            callback.onStdout(line);
                        } else {
                            callback.onStderr(line);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void startExitWatcher() {
        var thread = new Thread(() -> {
            int code = -1;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                // 正常情况下这里的代码永远也不会执行，既然执行到这里了那就当作被直接中断进程处理
                Thread.currentThread().interrupt();
            }
            joinQuietly(2000, stdoutThread, stderrThread);
            releaseResources();
            try {
                listener.onExit(code);
            } catch (Throwable ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void releaseResources() {
        if (resourcesReleased) {
            return;
        }
        resourcesReleased = true;
        if (guardHandle != null) {
            try {
                Os.close(guardHandle);
            } catch (Throwable ignored) {
            }
        }
        deleteQuietly(guardPipe);
        deleteQuietly(payloadFile);
    }

    private static String readLine(BufferedReader reader, boolean keepTerminator) throws IOException {
        var builder = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            builder.append((char) value);
            if (value == '\n') {
                break;
            }
        }
        if (builder.length() == 0) {
            return null;
        }
        if (!keepTerminator) {
            int length = builder.length();
            if (length > 0 && builder.charAt(length - 1) == '\n') {
                builder.setLength(--length);
            }
            if (length > 0 && builder.charAt(length - 1) == '\r') {
                builder.setLength(length - 1);
            }
        }
        return builder.toString();
    }

    private static void joinQuietly(long timeoutMillis, Thread... threads) {
        if (timeoutMillis <= 0 || threads == null) {
            return;
        }
        try {
            long startTime = System.nanoTime();
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            for (var thread : threads) {
                if (remaining <= 0) {
                    break;
                }
                if (thread == null) {
                    continue;
                }
                thread.join(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, timeoutMillis));
                remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - startTime);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
