package com.github.topdeepsk.androidjavashell.service;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.android.dx.stock.ProxyBuilder;
import com.github.topdeepsk.androidjavashell.core.JavaShell;
import com.github.topdeepsk.androidjavashell.core.JavaShellProcess;
import com.github.topdeepsk.androidjavashell.core.JavaShellArgumentException;
import com.github.topdeepsk.androidjavashell.core.UniqueFileNameGenerator;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class JavaShellService {

    private static final UniqueFileNameGenerator UNIQUE_DEX_CACHE = new UniqueFileNameGenerator("sk", "dex");

    private static final UniqueFileNameGenerator UNIQUE_SOCKET = new UniqueFileNameGenerator("sk-javashell", "svc");

    private File dexCache;

    private JavaShell javaShell;

    public JavaShellService() {
    }

    public JavaShellService(Context context) {
        setDexCache(new File(context.getCodeCacheDir(), "sk-dex-cache"));
    }

    public static JavaShellService createUseRoot(Context context) {
        return new JavaShellService(context).setJavaShell(JavaShell.createUseRoot(context));
    }

    public static JavaShellService createForceRoot(Context context) {
        return new JavaShellService(context).setJavaShell(JavaShell.createForceRoot(context));
    }

    public JavaShellService setDexCache(File dexCache) {
        this.dexCache = dexCache;
        return this;
    }

    public JavaShellService setJavaShell(JavaShell javaShell) {
        this.javaShell = javaShell;
        return this;
    }

    public <TService extends JavaShellRemoteService> JavaShellServiceProcess<TService> createService(Class<TService> targetService) {
        if (targetService == null) {
            throw new JavaShellArgumentException("targetService must not be null");
        }
        if (dexCache == null) {
            throw new JavaShellArgumentException("dexCache must be set before createService()");
        }
        if (javaShell == null) {
            throw new JavaShellArgumentException("javaShell must be set before createService()");
        }
        if (targetService.isInterface()) {
            throw new JavaShellArgumentException(
                    "targetService must be a concrete class, not an interface: " + targetService.getName());
        }
        if (Modifier.isFinal(targetService.getModifiers())) {
            // 代理是它的运行期子类，final 类无法被继承
            throw new JavaShellArgumentException(
                    "targetService must not be final: " + targetService.getName());
        }
        if (!JavaShellRemoteService.class.isAssignableFrom(targetService)) {
            // 提前把“release 包里成员被 R8 删掉或改成 final”变成构造期的明确报错
            throw new JavaShellArgumentException("targetService " + targetService.getName()
                    + " must implement " + JavaShellRemoteService.class.getName()
                    + "; otherwise R8 removes its constructor or finalizes its methods in release builds");
        }

        cleanupStaleDexCache();
        var dexCachePath = new File(dexCache, UNIQUE_DEX_CACHE.generate());
        if (!dexCachePath.isDirectory() && !dexCachePath.mkdirs()) {
            throw new JavaShellArgumentException("cannot create dex cache directory: " + dexCachePath);
        }

        var server = bind();
        JavaShellProcess process = null;
        Channel channel = null;
        var ready = false;
        try {
            process = javaShell.execute(JavaShellServiceHost.class, server.name, targetService.getName());
            channel = handshake(server.socket);
            var service = createProxy(targetService, channel, dexCachePath);
            var result = new JavaShellServiceProcess<>(service, process, channel);
            ready = true;
            return result;
        } finally {
            // socket 已经 accept 过（或这一次失败了），监听端不再需要
            closeQuietly(server.socket);
            if (!ready) {
                closeQuietly(channel);
                if (process != null) {
                    process.destroy();
                }
                // 没建成就别留下空目录，代理 dex 也不会被任何人引用
                deleteRecursively(dexCachePath);
            }
        }
    }

    /**
     * 生成代理。
     *
     * <p>{@code ProxyBuilder} 靠 dexmaker 在运行期产出一个子类的 dex，内部静态状态不是线程安全的，
     * 因此按参考实现加锁串行生成。</p>
     */
    private static <TService extends JavaShellRemoteService> TService createProxy(
            Class<TService> targetService, Channel channel, File dexCachePath) {
        try {
            synchronized (ProxyBuilder.class) {
                return ProxyBuilder.forClass(targetService)
                        .dexCache(dexCachePath)
                        .handler(new Forwarder(channel))
                        .build();
            }
        } catch (Throwable t) {
            throw new JavaShellArgumentException("failed to create proxy for " + targetService.getName()
                    + ": " + JavaShellServiceProtocol.describeRootCause(t), t);
        }
    }

    /**
     * 绑定抽象命名空间 socket。名字撞车时换一个再试——{@code LocalServerSocket} 的构造在重名时
     * 直接抛 {@code IOException}，而名字里带了 pid 与 nanoTime，实际上极少需要重试。
     */
    private static BoundServer bind() {
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var name = UNIQUE_SOCKET.generate();
            try {
                return new BoundServer(name, new LocalServerSocket(name));
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new JavaShellArgumentException(
                "failed to bind a local socket for the service: " + lastError, lastError);
    }

    /**
     * 等子进程连上来并完成握手。
     *
     * <p>两步都设超时：子进程可能因为类加载失败、被 SELinux 拒绝等原因根本连不上，不能把调用方永久挂住。</p>
     */
    private static Channel handshake(LocalServerSocket server) {
        long connectTimeoutMillis = 4 * 1000L;
        var socket = withTimeout(server::accept, connectTimeoutMillis, "waiting for the service process");
        try {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            JavaShellServiceProtocol.write(out, JavaShellServiceProtocol.encode(
                    JavaShellServiceProtocol.Message.hello()));
            var reply = withTimeout(() -> JavaShellServiceProtocol.read(in),
                    connectTimeoutMillis, "waiting for the handshake reply");
            if (reply.type == JavaShellServiceProtocol.TYPE_HELLO_FAIL) {
                throw new JavaShellArgumentException("service process failed to start: " + reply.reason
                        + (reply.failure == null ? "" : "\n" + reply.failure.describe()));
            }
            if (reply.type != JavaShellServiceProtocol.TYPE_HELLO_OK) {
                throw new JavaShellArgumentException("unexpected handshake reply type: " + reply.type);
            }
            return new Channel(socket, in, out);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new JavaShellArgumentException("failed to handshake with the service process: " + e, e);
        } catch (RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static <T> T withTimeout(Callable<T> block, long timeoutMillis, String what) {
        FutureTask<T> task = new FutureTask<>(block);
        var thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            var cause = e.getCause() == null ? e : e.getCause();
            throw new JavaShellArgumentException(what + " failed: "
                    + JavaShellServiceProtocol.describeRootCause(cause), cause);
        } catch (Throwable t) {
            task.cancel(true);
            throw new JavaShellArgumentException(what + " timed out after " + timeoutMillis + "ms", t);
        }
    }

    private void cleanupStaleDexCache() {
        if (!dexCache.isDirectory()) {
            dexCache.mkdirs();
            return;
        }
        var entries = dexCache.listFiles();
        if (entries == null) {
            return;
        }
        var expiredBefore = System.nanoTime() - TimeUnit.MINUTES.toNanos(60);
        for (var entry : entries) {
            var element = UNIQUE_DEX_CACHE.parse(entry.getName());
            if (element == null || element.getNanoTime() >= expiredBefore) {
                continue;
            }
            deleteRecursively(entry);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        var children = file.listFiles();
        if (children != null) {
            for (var child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
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

    /**
     * 已绑定但尚未 accept 的监听端：名字要留给子进程，所以和 socket 一起带出来。
     */
    private static final class BoundServer {

        final String name;

        final LocalServerSocket socket;

        BoundServer(String name, LocalServerSocket socket) {
            this.name = name;
            this.socket = socket;
        }
    }

    /**
     * 一条已经握手的服务通道：用一把锁把“发送 + 接收”包成原子操作，与子进程的单线程循环对应。
     */
    private static final class Channel implements Closeable {

        private final LocalSocket socket;

        private final DataInputStream in;

        private final DataOutputStream out;

        Channel(LocalSocket socket, DataInputStream in, DataOutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        synchronized JavaShellServiceProtocol.Message call(Method method, Object[] arguments) {
            var types = method.getParameterTypes();
            var parameterTypes = new String[types.length];
            for (var i = 0; i < types.length; i++) {
                parameterTypes[i] = types[i].getName();
            }
            try {
                JavaShellServiceProtocol.write(out, JavaShellServiceProtocol.encode(
                        JavaShellServiceProtocol.Message.call(
                                method.getName(), parameterTypes, arguments)));
                return JavaShellServiceProtocol.read(in);
            } catch (IOException e) {
                throw new JavaShellArgumentException("service channel broke down while calling "
                        + method.getName() + ": " + e, e);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 把方法调用转发到子进程。
     *
     * <p>{@code Object} 的方法在本地处理：转发过去既多一次往返，又会让集合去重、日志打印
     * 等行为依赖远端对象的状态。</p>
     */
    private static final class Forwarder implements InvocationHandler {

        private final Channel channel;

        Forwarder(Channel channel) {
            this.channel = channel;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == (args == null || args.length == 0 ? null : args[0]);
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        var parent = proxy.getClass().getSuperclass();
                        return "JavaShellService proxy for "
                                + (parent == null ? "unknown" : parent.getName());
                }
                return null;
            }

            var response = channel.call(method, args);
            if (response.error != null) {
                throwRemote(response.error, method);
                return null;
            }
            if (response.failure != null) {
                throw new JavaShellArgumentException("remote call " + method.getName()
                        + " failed with an exception that could not be serialized:\n"
                        + response.failure.describe());
            }
            return response.result;
        }

        /**
         * 远端异常按原类型抛：运行时异常与 Error 直接抛；受检异常仅当方法声明了它才直接抛，
         * 否则按 {@link java.lang.reflect.Proxy} 的惯例包成 {@link UndeclaredThrowableException}。
         */
        private static void throwRemote(Throwable error, Method method) throws Throwable {
            if (error instanceof RuntimeException || error instanceof Error) {
                throw error;
            }
            for (var declared : method.getExceptionTypes()) {
                if (declared.isInstance(error)) {
                    throw error;
                }
            }
            throw new UndeclaredThrowableException(error);
        }
    }
}
