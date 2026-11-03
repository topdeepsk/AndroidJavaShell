package com.github.topdeepsk.androidjavashell.service;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.github.topdeepsk.androidjavashell.core.JavaShellArgumentException;
import com.github.topdeepsk.androidjavashell.core.JavaShellEntry;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 子进程里的服务宿主：连回 App 进程的 socket，实例化服务类并串行处理调用。
 *
 * <p>参数由 {@link com.github.topdeepsk.androidjavashell.core.JavaShell} 通过载荷传入：
 * {@code [socket 名, 实现类名]}。</p>
 *
 * <p><b>为什么串行</b>：本进程是单线程循环“读帧 → 调用 → 写帧”，App 侧用一把锁把“发送 + 接收”
 * 包成原子操作，因此不需要在协议里做请求编号与乱序匹配。</p>
 */
final class JavaShellServiceHost implements JavaShellEntry {

    @Override
    public void main(Object[] args) throws Exception {
        if (args == null || args.length < 2
                || !(args[0] instanceof String) || !(args[1] instanceof String)) {
            throw new JavaShellArgumentException(
                    "JavaShellServiceHost expects two string arguments: socket name and implementation class");
        }
        var socketName = (String) args[0];
        var implementationName = (String) args[1];
        var socket = connect(socketName);
        try {
            serve(socket, implementationName);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 每次重试都新建一个 {@link LocalSocket}：连接失败后 socket 对象不能复用，复用会把真正的原因
     * 盖成“已经关闭”之类的错误。
     */
    private static LocalSocket connect(String socketName) throws IOException {
        IOException lastError = null;
        // 连接重试：正常情况下 App 进程在启动本进程之前就已完成 bind，这里只兜住极端时序。
        for (var attempt = 0; attempt < 20; attempt++) {
            var socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(socketName));
                return socket;
            } catch (IOException e) {
                lastError = e;
                closeQuietly(socket);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("failed to connect to abstract socket " + socketName + ": " + lastError, lastError);
    }

    /**
     * 握手 + 服务循环。
     *
     * <p>握手由 App 侧先发：本进程先完成实例化，再回 {@code HELLO_OK}。这样“实现类不存在”
     * 这类错误在服务构造阶段就能返回给调用方，而不是等到第一次方法调用才暴露成一次超时。</p>
     */
    private static void serve(LocalSocket socket, String implementationName) throws Exception {
        var in = new DataInputStream(socket.getInputStream());
        var out = new DataOutputStream(socket.getOutputStream());

        var hello = JavaShellServiceProtocol.read(in);
        if (hello.type != JavaShellServiceProtocol.TYPE_HELLO) {
            throw new IOException("expected a handshake frame, received type " + hello.type);
        }
        if (hello.version != JavaShellServiceProtocol.VERSION) {
            JavaShellServiceProtocol.write(out, JavaShellServiceProtocol.encode(
                    JavaShellServiceProtocol.Message.helloFail(
                            "protocol version mismatch: app=" + hello.version
                                    + ", shell=" + JavaShellServiceProtocol.VERSION, null)));
            return;
        }

        Class<?> implementationClass;
        Object instance;
        try {
            implementationClass = Class.forName(implementationName, true,
                    JavaShellServiceHost.class.getClassLoader());
            var constructor = implementationClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
        } catch (Throwable t) {
            // 实例化失败也要回一帧：调用方据此立即失败，不必等到连接超时
            JavaShellServiceProtocol.write(out, JavaShellServiceProtocol.encode(
                    JavaShellServiceProtocol.Message.helloFail(
                            "failed to instantiate " + implementationName + ": "
                                    + JavaShellServiceProtocol.describeRootCause(t),
                            JavaShellServiceProtocol.Failure.of(t))));
            System.err.println("JavaShellServiceHost: failed to instantiate "
                    + implementationName + ": " + t);
            return;
        }

        JavaShellServiceProtocol.write(out, JavaShellServiceProtocol.encode(
                JavaShellServiceProtocol.Message.helloOk(android.os.Process.myPid())));

        dispatch(in, out, implementationClass, instance);
    }

    /**
     * 串行接收请求并回传结果，直到对端关闭连接。
     */
    private static void dispatch(DataInputStream in, DataOutputStream out,
                                 Class<?> implementationClass, Object instance) throws IOException {
        var resolved = new HashMap<String, Method>();
        while (true) {
            JavaShellServiceProtocol.Message request;
            try {
                request = JavaShellServiceProtocol.read(in);
            } catch (IOException eof) {
                // App 进程关闭连接或消失：正常收尾
                return;
            } catch (JavaShellArgumentException malformed) {
                // 帧长度非法或内容无法反序列化：这条连接已经不可信
                System.err.println("JavaShellServiceHost: malformed frame, closing: "
                        + malformed.getMessage());
                return;
            }
            if (request.type != JavaShellServiceProtocol.TYPE_CALL) {
                System.err.println("JavaShellServiceHost: unexpected frame type "
                        + request.type + ", closing");
                return;
            }

            JavaShellServiceProtocol.Message response;
            try {
                var method = resolve(implementationClass, request.methodName, request.parameterTypes, resolved);
                method.setAccessible(true);
                response = JavaShellServiceProtocol.Message.result(method.invoke(instance, request.arguments));
            } catch (InvocationTargetException e) {
                // 实现类自己抛的异常：原样回传，让调用方按原类型捕获
                response = JavaShellServiceProtocol.Message.error(e.getCause() == null ? e : e.getCause());
            } catch (Throwable t) {
                response = JavaShellServiceProtocol.Message.error(t);
            }

            byte[] frame;
            try {
                frame = JavaShellServiceProtocol.encode(response);
            } catch (JavaShellArgumentException notSerializable) {
                frame = JavaShellServiceProtocol.encode(fallbackOf(request, response, notSerializable));
            }
            JavaShellServiceProtocol.write(out, frame);
        }
    }

    /**
     * 返回值或异常无法序列化时的降级响应：只带文本，保证连接继续可用。
     */
    private static JavaShellServiceProtocol.Message fallbackOf(
            JavaShellServiceProtocol.Message request,
            JavaShellServiceProtocol.Message response,
            JavaShellArgumentException cause) {
        if (response.error != null) {
            var failure = JavaShellServiceProtocol.Failure.of(response.error);
            return JavaShellServiceProtocol.Message.ofFailure(new JavaShellServiceProtocol.Failure(
                    failure.className,
                    "the exception could not be serialized, only its text form is available: "
                            + failure.message,
                    failure.stackTrace));
        }
        return JavaShellServiceProtocol.Message.ofFailure(new JavaShellServiceProtocol.Failure(
                JavaShellArgumentException.class.getName(),
                "method " + request.methodName + " returned a value that cannot be serialized: "
                        + cause.getMessage(),
                ""));
    }

    /**
     * 按“方法名 + 参数类型名”定位方法。
     *
     * <p>不能序列化 {@link Method}：它绑定在具体类加载器上，跨进程没有意义。也不能只用方法名，
     * 重载会命中错误的那一个。参数类型用 {@link Class#getName()} 比较，两端是同一个 APK 里的
     * 同一批类，名字必然一致（基本类型得到 {@code int}，数组得到 {@code [B} 这类形式）。</p>
     */
    private static Method resolve(Class<?> implementationClass, String name, String[] parameterTypes,
                                  Map<String, Method> cache) throws NoSuchMethodException {
        var key = keyOf(name, parameterTypes);
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        for (var current = implementationClass; current != null; current = current.getSuperclass()) {
            for (var method : current.getDeclaredMethods()) {
                if (matches(method, name, parameterTypes)) {
                    cache.put(key, method);
                    return method;
                }
            }
        }
        assert implementationClass != null;
        for (var method : implementationClass.getMethods()) {
            if (matches(method, name, parameterTypes)) {
                cache.put(key, method);
                return method;
            }
        }
        throw new NoSuchMethodException("no method " + key + " on " + implementationClass.getName());
    }

    private static String keyOf(String name, String[] parameterTypes) {
        var builder = new StringBuilder(name).append('(');
        if (parameterTypes != null) {
            for (var i = 0; i < parameterTypes.length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(parameterTypes[i]);
            }
        }
        return builder.append(')').toString();
    }

    private static boolean matches(Method method, String name, String[] parameterTypes) {
        if (!method.getName().equals(name)) {
            return false;
        }
        var actual = method.getParameterTypes();
        var expected = parameterTypes == null ? 0 : parameterTypes.length;
        if (actual.length != expected) {
            return false;
        }
        for (var i = 0; i < actual.length; i++) {
            if (!actual[i].getName().equals(parameterTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(LocalSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
