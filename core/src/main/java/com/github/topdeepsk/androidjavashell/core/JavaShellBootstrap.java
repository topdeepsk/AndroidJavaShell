package com.github.topdeepsk.androidjavashell.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 子进程里真正被执行的入口类。
 *
 * <p>app_process 只负责把参数原样交给它，真正的目标类、方法名、参数全部藏在载荷里：
 * 这样参数类型不受命令行限制，命令行上也不会出现业务参数。</p>
 */
public final class JavaShellBootstrap {

    private static final int EXIT_NO_ARGUMENT = 10;

    private static final int EXIT_INVALID_PAYLOAD = 11;

    private static final int EXIT_CLASS_NOT_FOUND = 12;

    private static final int EXIT_NOT_ENTRY_POINT = 13;

    private static final int EXIT_NO_INSTANCE = 14;

    private static final int EXIT_NO_ENTRY_METHOD = 15;

    private static final int EXIT_ENTRY_FAILED = 16;

    /**
     * 载荷以文件传递时的前缀，用来与 Base64 载荷区分：Base64 字符集里不含 {@code @}。
     */
    static final String ARGUMENT_FILE_PREFIX = "@";

    private JavaShellBootstrap() {
    }

    public static void main(String[] args) {
        installUtf8Streams();
        try {
            run(args);
        } catch (ExitException e) {
            System.err.println("javashell: " + e.getMessage());
            var cause = e.getCause();
            if (cause != null) {
                cause.printStackTrace(System.err);
            }
            System.exit(e.code);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(EXIT_ENTRY_FAILED);
        }
    }

    /**
     * 把标准输出与标准错误钉死在 UTF-8。
     */
    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static void installUtf8Streams() {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        } catch (Throwable ignored) {
        }
        try {
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private static void run(String[] args) throws ExitException {
        if (args == null || args.length == 0 || args[0] == null || args[0].isEmpty()) {
            throw new ExitException(EXIT_NO_ARGUMENT, "no invocation payload");
        }
        var text = args[0];
        if (text.startsWith(ARGUMENT_FILE_PREFIX)) {
            text = readPayloadFile(text.substring(ARGUMENT_FILE_PREFIX.length()));
        }
        ShellInvocation.Payload payload;
        try {
            payload = ShellInvocation.decode(text);
        } catch (Throwable t) {
            throw new ExitException(EXIT_INVALID_PAYLOAD, "invalid invocation payload: " + t, t);
        }

        if (payload.guardPath != null) {
            startGuard(payload.guardPath);
        }

        Class<?> target;
        try {
            target = Class.forName(payload.className, true, JavaShellBootstrap.class.getClassLoader());
        } catch (Throwable t) {
            throw new ExitException(EXIT_CLASS_NOT_FOUND, "class not found: " + payload.className, t);
        }
        if (!JavaShellEntry.class.isAssignableFrom(target)) {
            throw new ExitException(EXIT_NOT_ENTRY_POINT, "class does not implement ShellEntryPoint: " + payload.className);
        }

        Object instance;
        try {
            var constructor = target.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
        } catch (Throwable t) {
            throw new ExitException(EXIT_NO_INSTANCE, "cannot instantiate: " + payload.className, t);
        }

        Method method;
        try {
            method = target.getMethod(payload.methodName, Object[].class);
            method.setAccessible(true);
        } catch (Throwable t) {
            throw new ExitException(EXIT_NO_ENTRY_METHOD,
                    "entry method not found: " + payload.className + "#" + payload.methodName, t);
        }

        var arguments = payload.args == null ? new Object[0] : payload.args;
        try {
            method.invoke(instance, (Object) arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ExitException(EXIT_ENTRY_FAILED, "entry method failed: " + cause, cause);
        } catch (Throwable t) {
            throw new ExitException(EXIT_ENTRY_FAILED, "entry method failed: " + t, t);
        }
    }

    /**
     * 守护管道：父进程持有写端，本进程阻塞读；父进程死亡时写端关闭，这里读到 EOF 立即自杀。
     * 用 {@code halt} 而不是 {@code exit}，避免目标代码注册的 shutdown hook 把退出拖长。
     */
    private static void startGuard(final String guardPath) {
        var thread = new Thread(() -> {
            InputStream stream = null;
            try {
                stream = new FileInputStream(guardPath);
                byte[] buffer = new byte[64];
                while (stream.read(buffer) >= 0) {
                    // 父进程活着时读操作一直阻塞，这里拿不到数据
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(stream);
            }
            Runtime.getRuntime().halt(0);
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static String readPayloadFile(String path) throws ExitException {
        var file = new File(path);
        InputStream stream = null;
        try {
            stream = new FileInputStream(file);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new ExitException(EXIT_INVALID_PAYLOAD, "cannot read payload file: " + path, t);
        } finally {
            closeQuietly(stream);
            file.delete();
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    private static final class ExitException extends RuntimeException {
        final int code;

        ExitException(int code, String message) {
            super(message);
            this.code = code;
        }

        ExitException(int code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }
}
