package com.github.topdeepsk.androidjavashell.service;

import com.github.topdeepsk.androidjavashell.core.JavaShellArgumentException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;

/**
 * 跨进程调用的线协议：帧格式、消息结构、序列化编解码。
 *
 * <p>帧格式是 {@code 4 字节大端长度 + Java 序列化字节}。{@code LocalSocket} 是字节流，本身没有消息
 * 边界，只有自己加长度前缀才能把一次调用和一次响应分开；不用 {@code writeUTF} 是因为它按 64KB
 * 计数，装不下稍大的返回值。</p>
 *
 * <p>参数与返回值统一走 Java 序列化：两端加载的是同一个 APK 里的同一批类，字段与
 * {@code serialVersionUID} 完全一致，不必再维护一套自定义编码。</p>
 */
final class JavaShellServiceProtocol {

    static final int VERSION = 1;

    static final int MAX_FRAME_LENGTH = 128 * 1024 * 1024;

    static final int TYPE_HELLO = 1;

    static final int TYPE_HELLO_OK = 2;

    static final int TYPE_HELLO_FAIL = 3;

    static final int TYPE_CALL = 4;

    static final int TYPE_RESULT = 5;

    /**
     * 反序列化用的类加载器：子进程里“最近一个用户自定义加载器”可能是 null，默认逻辑会退化到
     * bootstrap 加载器，加载不到本模块与业务类。
     */
    private static final ClassLoader LOADER = JavaShellServiceProtocol.class.getClassLoader();

    private JavaShellServiceProtocol() {
    }

    /**
     * 先把消息整体写进内存再返回，不直接写 socket：序列化中途失败时（例如参数不可序列化），
     * 连接上一个字节都还没写，不会停在“写了一半”的不可恢复状态。
     */
    static byte[] encode(Message message) {
        var buffer = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(buffer)) {
            stream.writeObject(message);
            stream.flush();
        } catch (IOException e) {
            throw new JavaShellArgumentException(
                    "failed to serialize service message: " + describeRootCause(e), e);
        }
        var data = buffer.toByteArray();
        if (data.length > MAX_FRAME_LENGTH) {
            throw new JavaShellArgumentException("service message is too large: " + data.length
                    + " bytes, the limit is " + MAX_FRAME_LENGTH);
        }
        return data;
    }

    static void write(DataOutputStream out, byte[] frame) throws IOException {
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    static Message read(DataInputStream in) throws IOException {
        var length = in.readInt();
        if (length <= 0 || length > MAX_FRAME_LENGTH) {
            throw new JavaShellArgumentException("invalid frame length: " + length);
        }
        var data = new byte[length];
        in.readFully(data);
        return decode(data);
    }

    private static Message decode(byte[] data) {
        try {
            try (var stream = new ObjectInputStream(new ByteArrayInputStream(data)) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass descriptor)
                        throws IOException, ClassNotFoundException {
                    try {
                        return Class.forName(descriptor.getName(), false, LOADER);
                    } catch (ClassNotFoundException ignored) {
                        return super.resolveClass(descriptor);
                    }
                }
            }) {
                var decoded = stream.readObject();
                if (!(decoded instanceof Message)) {
                    throw new JavaShellArgumentException("service frame does not contain a Message");
                }
                return (Message) decoded;
            }
        } catch (IOException e) {
            throw new JavaShellArgumentException(
                    "failed to decode service frame: " + describeRootCause(e), e);
        } catch (ClassNotFoundException e) {
            throw new JavaShellArgumentException("service frame refers to an unknown class: " + e, e);
        }
    }

    /**
     * 取出最内层原因的文字说明，便于把 {@code NotSerializableException} 的类名带到最外层消息里。
     */
    static String describeRootCause(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        var message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getName()
                : current.getClass().getName() + ": " + message;
    }

    /**
     * 一帧消息。请求与响应共用同一个结构，靠 {@link #type} 区分，减少两端的类型判断代码。
     */
    static final class Message implements Serializable {

        private static final long serialVersionUID = 1L;

        final int type;

        final int version;

        final int pid;

        final String methodName;

        final String reason;

        final String[] parameterTypes;

        final Object[] arguments;

        final Object result;

        final Throwable error;

        final Failure failure;

        private Message(int type, int version, int pid, String methodName, String reason,
                        String[] parameterTypes, Object[] arguments, Object result,
                        Throwable error, Failure failure) {
            this.type = type;
            this.version = version;
            this.pid = pid;
            this.methodName = methodName;
            this.reason = reason;
            this.parameterTypes = parameterTypes;
            this.arguments = arguments;
            this.result = result;
            this.error = error;
            this.failure = failure;
        }

        static Message hello() {
            return new Message(TYPE_HELLO, VERSION, 0, null, null, null, null, null, null, null);
        }

        static Message helloOk(int pid) {
            return new Message(TYPE_HELLO_OK, VERSION, pid, null, null, null, null, null, null, null);
        }

        static Message helloFail(String reason, Failure failure) {
            return new Message(TYPE_HELLO_FAIL, VERSION, 0, null, reason, null, null, null, null, failure);
        }

        static Message call(String methodName, String[] parameterTypes, Object[] arguments) {
            return new Message(TYPE_CALL, VERSION, 0, methodName, null, parameterTypes, arguments,
                    null, null, null);
        }

        static Message result(Object value) {
            return new Message(TYPE_RESULT, VERSION, 0, null, null, null, null, value, null, null);
        }

        static Message error(Throwable throwable) {
            return new Message(TYPE_RESULT, VERSION, 0, null, null, null, null, null, throwable, null);
        }

        static Message ofFailure(Failure failure) {
            return new Message(TYPE_RESULT, VERSION, 0, null, null, null, null, null, null, failure);
        }
    }

    /**
     * 异常的文本载体：类名 + 消息 + 栈帧。异常对象本身不可序列化时用它兜底，保证一次调用失败
     * 不会把整条连接打挂。
     */
    static final class Failure implements Serializable {

        private static final long serialVersionUID = 1L;

        final String className;

        final String message;

        final String stackTrace;

        Failure(String className, String message, String stackTrace) {
            this.className = className;
            this.message = message;
            this.stackTrace = stackTrace;
        }

        static Failure of(Throwable throwable) {
            var writer = new StringWriter();
            try (var printer = new PrintWriter(writer)) {
                throwable.printStackTrace(printer);
                printer.flush();
            }
            return new Failure(throwable.getClass().getName(), throwable.getMessage(), writer.toString());
        }

        String describe() {
            var builder = new StringBuilder(className);
            if (message != null && !message.isEmpty()) {
                builder.append(": ").append(message);
            }
            if (stackTrace != null && !stackTrace.isEmpty()) {
                builder.append('\n').append(stackTrace);
            }
            return builder.toString();
        }
    }
}
