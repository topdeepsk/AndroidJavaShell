package com.github.topdeepsk.androidjavashell.core;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Modifier;

/**
 * 一次调用的完整描述：目标类、入口方法名、参数，以及可选的守护管道路径。
 *
 * <p>只在本包内部使用。载荷用 Java 序列化 + Base64 编码成一段不含空格与换行的文本，
 * 这样它既能直接作为 app_process 的一个命令行参数，也能经过 {@code su -c} 的 shell 解析而不被破坏。</p>
 */
final class ShellInvocation {

    /**
     * 单个命令行参数的安全上限。
     *
     * <p>Linux 对单个 argv 元素的长度限制是 128KB（MAX_ARG_STRLEN），这里取 96KB 留余量；
     * 超过这个长度时由 {@link JavaShell} 改为写入临时文件传递。</p>
     */
    public static final int MAX_PAYLOAD_LENGTH = 96 * 1024;

    private final Class<? extends JavaShellEntry> targetClass;

    private final Payload body;

    private final String payload;

    public ShellInvocation(Class<? extends JavaShellEntry> targetClass, Object... args) {
        if (targetClass == null) {
            throw new JavaShellArgumentException("targetClass must not be null");
        }
        this.targetClass = targetClass;
        this.body = new Payload(
                targetClass.getName(),
                resolveEntryMethodName(),
                args == null ? new Object[0] : args,
                null);
        this.payload = encode(this.body);
    }

    private ShellInvocation(Class<? extends JavaShellEntry> targetClass, Payload body) {
        this.targetClass = targetClass;
        this.body = body;
        this.payload = encode(body);
    }

    /**
     * 生成带守护管道路径的副本：{@link JavaShell} 先建好 FIFO，再把路径塞进载荷。
     */
    ShellInvocation withGuardPath(String guardPath) {
        if (guardPath == null || guardPath.equals(body.guardPath)) {
            return this;
        }
        return new ShellInvocation(targetClass, new Payload(
                body.className, body.methodName, body.args, guardPath));
    }

    private static String resolveEntryMethodName() {
        var methods = JavaShellEntry.class.getDeclaredMethods();
        if (methods.length != 1 || !Modifier.isAbstract(methods[0].getModifiers())) {
            throw new JavaShellArgumentException(
                    "ShellEntryPoint must declare exactly one abstract method, found " + methods.length);
        }
        return methods[0].getName();
    }

    private static String encode(Payload body) {
        var bytes = new ByteArrayOutputStream();
        try {
            ObjectOutputStream stream = new ObjectOutputStream(bytes);
            stream.writeObject(body);
            stream.close();
        } catch (Throwable t) {
            throw new JavaShellArgumentException("failed to serialize invocation: " + t, t);
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    static Payload decode(String text) {
        if (text == null || text.isEmpty()) {
            throw new JavaShellArgumentException("payload must not be empty");
        }
        try {
            var raw = Base64.decode(text, Base64.NO_WRAP);
            var stream = new ObjectInputStream(new ByteArrayInputStream(raw));
            var object = stream.readObject();
            stream.close();
            if (!(object instanceof Payload)) {
                throw new JavaShellArgumentException("unexpected payload type: " + object);
            }
            return (Payload) object;
        } catch (JavaShellArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new JavaShellArgumentException("failed to decode invocation: " + t, t);
        }
    }

    public String getPayload() {
        return payload;
    }

    static final class Payload implements Serializable {

        private static final long serialVersionUID = 1L;

        final String className;

        final String methodName;

        final Object[] args;

        final String guardPath;

        Payload(String className, String methodName, Object[] args, String guardPath) {
            this.className = className;
            this.methodName = methodName;
            this.args = args;
            this.guardPath = guardPath;
        }
    }
}
