package com.github.topdeepsk.androidjavashell.service;

import com.github.topdeepsk.androidjavashell.core.JavaShellProcess;

import java.io.Closeable;

public final class JavaShellServiceProcess<TService extends JavaShellRemoteService> implements AutoCloseable {

    private final TService service;
    private final JavaShellProcess process;
    private final Closeable channel;

    JavaShellServiceProcess(TService service, JavaShellProcess process) {
        this(service, process, null);
    }

    JavaShellServiceProcess(TService service, JavaShellProcess process, Closeable channel) {
        this.service = service;
        this.process = process;
        this.channel = channel;
    }

    public TService getService() {
        return service;
    }

    public JavaShellProcess getProcess() {
        return process;
    }

    @Override
    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (Throwable ignored) {
            }
        }
        process.close();
    }
}
