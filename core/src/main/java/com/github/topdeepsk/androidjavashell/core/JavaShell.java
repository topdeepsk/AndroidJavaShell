package com.github.topdeepsk.androidjavashell.core;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class JavaShell {

    private static final String APP_PROCESS = "/system/bin/app_process";

    private static final String APP_PROCESS_32 = "/system/bin/app_process32";

    private static final String APP_PROCESS_64 = "/system/bin/app_process64";

    private static final String COMMAND_FOLDER = "/system/bin";

    private static final UniqueFileNameGenerator PAYLOAD_FILE_NAME = new UniqueFileNameGenerator("sk-javashell-payload", "payload");

    private static final UniqueFileNameGenerator GUARD_PIPE_NAME = new UniqueFileNameGenerator("sk-javashell-guard", "fifo");

    private LinkedHashSet<String> classPath = new LinkedHashSet<>();

    private LinkedHashSet<String> libraryPath = new LinkedHashSet<>();

    private File cacheDir;

    private String niceName = "sk-javashell";

    private boolean terminateOnParentExit;

    private boolean is64Bit;

    private ShellMode shellMode = ShellMode.NO_ROOT;

    private String[] executableRootNames;

    public JavaShell() {
        this.is64Bit = ShellUtils.is64Bit();
        this.executableRootNames = new String[] {"su"};
    }

    public JavaShell(Context context) {
        this();
        addClassPath(context.getPackageCodePath());
        var splitSourceDirs = context.getApplicationInfo().splitSourceDirs;
        if (splitSourceDirs != null) {
            addClassPath(splitSourceDirs);
        }
        addLibraryPath(context.getApplicationInfo().nativeLibraryDir);
        setCacheDir(new File(context.getCacheDir(), "sk-javashell-cache"));
    }

    public static JavaShell createUseRoot(Context context) {
        return new JavaShell(context).setUseRoot().setTerminateOnParentExit(true);
    }

    public static JavaShell createForceRoot(Context context) {
        return createUseRoot(context).setForceRoot();
    }

    public JavaShell addClassPath(String... paths) {
        addPaths(classPath, paths);
        return this;
    }

    public JavaShell addLibraryPath(String... paths) {
        addPaths(libraryPath, paths);
        return this;
    }

    public JavaShell setCacheDir(File dir) {
        if (dir == null) {
            throw new JavaShellArgumentException("dir must not be null");
        }
        this.cacheDir = dir;
        return this;
    }

    public JavaShell setNiceName(String name) {
        if (name == null) {
            throw new JavaShellArgumentException("name must not be null");
        }
        this.niceName = name;
        return this;
    }

    public JavaShell setTerminateOnParentExit(boolean enable) {
        this.terminateOnParentExit = enable;
        return this;
    }

    public JavaShell setIs64Bit(boolean is64Bit) {
        this.is64Bit = is64Bit;
        return this;
    }

    public JavaShell setNoRoot() {
        this.shellMode = ShellMode.NO_ROOT;
        return this;
    }

    public JavaShell setUseRoot() {
        this.shellMode = ShellMode.USE_ROOT;
        return this;
    }

    public JavaShell setForceRoot() {
        this.shellMode = ShellMode.FORCE_ROOT;
        return this;
    }

    public JavaShell setExecutableRoot(String... executableRootNames) {
        this.executableRootNames = executableRootNames;
        return this;
    }

    /**
     * 启动目标类的独立进程。
     *
     * <p>目标类必须实现 {@link JavaShellEntry} 并提供无参构造函数；参数会被序列化后整体传递，
     * 因此不受命令行长度与字符集的限制（超长时自动改为临时文件传递）。</p>
     *
     * @param targetClass 目标类
     * @param args        传给入口方法的参数，可为空
     * @return 会话句柄
     * @throws JavaShellArgumentException 参数非法、目标类不合格，或强制要求 root 但设备没有 root
     * @throws JavaShellArgumentException  子进程启动失败
     */
    public JavaShellProcess execute(Class<? extends JavaShellEntry> targetClass, Object... args) {
        if (targetClass == null) {
            throw new JavaShellArgumentException("targetClass must not be null");
        }
        if (!JavaShellEntry.class.isAssignableFrom(targetClass)) {
            throw new JavaShellArgumentException("targetClass must implement ShellEntryPoint: " + targetClass.getName());
        }
        if (terminateOnParentExit && cacheDir == null) {
            throw new JavaShellArgumentException("terminateOnParentExit requires a writable cache directory; call setCacheDir(File)");
        }

        var processName = niceName;
        var classPath = buildClassPath(targetClass);
        var libraryPath = join(this.libraryPath, ":");

        var invocation = new ShellInvocation(targetClass, args);
        RootShell rootShell = null;
        File payloadFile = null;
        File guardPipe = null;
        FileDescriptor guardHandle = null;
        try {
            // root 权限只在这里申请一次：拿到的是一个已经授权的交互式 shell，
            // 探测与后续执行 Java 代码都用它，不会出现“前面给了后面拒绝”。
            rootShell = openRootShell();
            var rootMode = rootShell != null;
            if (shellMode == ShellMode.FORCE_ROOT && !rootMode) {
                throw new JavaShellArgumentException("FORCE_ROOT was requested but root access is not available on this device");
            }
            if (cacheDir != null) {
                cleanupStaleFiles(ensureCacheDir());
            }
            if (terminateOnParentExit) {
                guardPipe = createGuardPipe();
                guardHandle = openGuardPipe(guardPipe);
                invocation = invocation.withGuardPath(guardPipe.getAbsolutePath());
            }

            var payload = invocation.getPayload();
            var argument = payload;
            if (payload.length() > ShellInvocation.MAX_PAYLOAD_LENGTH) {
                payloadFile = writePayloadFile(payload);
                argument = JavaShellBootstrap.ARGUMENT_FILE_PREFIX + payloadFile.getAbsolutePath();
            }

            var command = buildCommand(classPath, libraryPath, argument, processName);
            Process process;
            if (rootMode) {
                var script = buildRootScript(command);
                writeCommand(rootShell.process, script);
                process = rootShell.process;
            } else {
                var launch = command.toArray(new String[0]);
                process = new ProcessBuilder(launch).redirectErrorStream(false).start();
            }
            var result = new JavaShellProcess(process, processName, rootMode, payloadFile, guardPipe, guardHandle);
            // 进程句柄已经交给 JavaShellProcess，失败清理时不能再把它算作待回收资源
            rootShell = null;
            return result;
        } catch (Throwable e) {
            cleanupQuietly(payloadFile, guardPipe, guardHandle, rootShell);
            if (e instanceof JavaShellArgumentException) {
                throw (JavaShellArgumentException) e;
            }
            throw new JavaShellArgumentException("failed to start app_process: " + e.getMessage(), e);
        }
    }

    private static void addPaths(Set<String> target, String[] paths) {
        if (paths == null) {
            throw new JavaShellArgumentException("paths must not be null");
        }
        for (var path : paths) {
            if (path == null || path.isEmpty()) {
                throw new JavaShellArgumentException("path must not be empty");
            }
            if (path.indexOf(':') >= 0) {
                throw new JavaShellArgumentException("path must not contain ':': " + path);
            }
            target.add(path);
        }
    }

    /**
     * 组装类路径：启动类所在的 dex 与自己注册的路径都在里面，目标类所在的 dex 排在启动类之后。
     */
    private String buildClassPath(Class<?> targetClass) {
        var paths = new LinkedHashSet<String>();
        addPathQuietly(paths, DexPathResolver.getDexPaths(JavaShellBootstrap.class));
        addPathQuietly(paths, DexPathResolver.getDexPaths(targetClass));
        for (var path : classPath) {
            addPathQuietly(paths, path);
        }
        return join(paths, ":");
    }

    private static void addPathQuietly(Set<String> target, String... paths) {
        if (paths == null) {
            return;
        }
        for (var path : paths) {
            if (path != null && !path.isEmpty() && path.indexOf(':') < 0) {
                target.add(path);
            }
        }
    }

    /**
     * 参数顺序固定为：虚拟机参数 -> 父目录 -> 进程名 -> 启动类 -> 载荷。
     *
     * <p>{@code --nice-name=} 必须放在父目录之后：放在父目录之前会被当成虚拟机参数交给 ART，
     * 进程会以 {@code Unknown argument} 直接退出。</p>
     */
    private List<String> buildCommand(String classPath, String libraryPath, String argument, String processName) {
        var command = new ArrayList<String>();
        command.add(resolveAppProcess());
        command.add("-Djava.class.path=" + classPath);
        if (libraryPath != null && !libraryPath.isEmpty()) {
            command.add("-Djava.library.path=" + libraryPath);
        }
        command.add(COMMAND_FOLDER);
        command.add("--nice-name=" + processName);
        command.add(JavaShellBootstrap.class.getName());
        command.add(argument);
        return command;
    }

    /**
     * root 模式要把整条命令压成一个字符串交给 {@code su -c}，因此每个片段都要做单引号转义。
     * 用 {@code exec} 替换掉中间那层 sh，让 su 的直接子进程就是 app_process。
     */
    /**
     * root 模式要把整条命令压成一行脚本写进已经授权的 shell，因此每个片段都要做单引号转义。
     * 用 {@code exec} 替换掉这层 shell，让 app_process 直接接管该进程，后续的
     * {@code waitFor}/{@code destroy} 与普通模式完全一致。
     */
    private static String buildRootScript(List<String> command) {
        var script = new StringBuilder("exec");
        for (var part : command) {
            script.append(' ').append(singleQuote(part));
        }
        return script.toString();
    }

    private static void writeCommand(Process process, String script) throws IOException {
        var stdin = process.getOutputStream();
        stdin.write((script + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static String singleQuote(String value) {
        var builder = new StringBuilder(value.length() + 2);
        builder.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                builder.append("'\\''");
            } else {
                builder.append(c);
            }
        }
        builder.append('\'');
        return builder.toString();
    }

    private String resolveAppProcess() {
        var preferred = is64Bit ? APP_PROCESS_64 : APP_PROCESS_32;
        if (new File(preferred).exists()) {
            return preferred;
        }
        return APP_PROCESS;
    }

    private RootShell openRootShell() {
        if (shellMode == ShellMode.NO_ROOT) {
            return null;
        }
        var executable = ShellUtils.getExecutablePath(executableRootNames);
        if (executable == null) {
            return null;
        }
        Process process;
        try {
            process = new ProcessBuilder(executable.getAbsolutePath()).start();
        } catch (Throwable t) {
            return null;
        }
        if (verifyRootShell(process)) {
            return new RootShell(process, executable.getAbsolutePath());
        }
        destroyQuietly(process);
        return null;
    }

    private static boolean verifyRootShell(Process process) {
        long askRootTimeoutMillis = 10 * 1000L;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(askRootTimeoutMillis);
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            var stdin = process.getOutputStream();
            var stdout = process.getInputStream();
            var marker = "JAVASHELL_OUTPUT_MARKER";

            // su 可能先打印欢迎语或提示符，先清掉，避免把它的内容当成命令回显
            drainQuietly(stdout);

            stdin.write(("echo " + marker + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            // 进程刚起来时输出可能还没到，可能还要等用户点授权，所以一直等到标记出现、
            // 进程退出或到期为止；中间的欢迎语、提示符一律跳过
            if (awaitLine(stdout, process, deadlineNanos, marker) == null) {
                return false;
            }

            stdin.write("id\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            // 同理：读不到任何内容就按“没有 root”处理，读到但不是 uid=0 同样不算
            var idLine = awaitLine(stdout, process, deadlineNanos, "uid=");
            return idLine != null && idLine.contains("uid=0") && idLine.contains("(root)");
        });
        var thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        try {
            // 等待 su 授权
            return task.get(askRootTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            task.cancel(true);
            return false;
        }
    }

    private static String awaitLine(InputStream stream, Process process, long deadlineNanos, String expected) throws IOException {
        while (true) {
            var line = readLine(stream, process, deadlineNanos);
            if (line == null) {
                return null;
            }
            if (line.contains(expected)) {
                return line;
            }
            // 欢迎语、提示符之类的噪声，丢掉继续读取
        }
    }

    /**
     * 逐字节读一行，不预读：验证查询的应答之后，管道里剩下的字节必须原样留给 app_process。
     *
     * <p>没有数据可读时不会无脑阻塞：进程还活着就小睡一下再试（等待它输出），
     * 进程已经退出或到了截止时间就返回已读到的内容（可能是 null 或半行）。</p>
     */
    private static String readLine(InputStream stream, Process process, long deadlineNanos) throws IOException {
        var builder = new StringBuilder();
        while (true) {
            while (stream.available() > 0) {
                var value = stream.read();
                if (value < 0) {
                    return builder.length() == 0 ? null : builder.toString();
                }
                if (value == '\n') {
                    return builder.toString();
                }
                if (value != '\r') {
                    builder.append((char) value);
                }
            }
            if (!ShellUtils.isAlive(process)) {
                return builder.length() == 0 ? null : builder.toString();
            }
            if (System.nanoTime() >= deadlineNanos) {
                return builder.length() == 0 ? null : builder.toString();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return builder.length() == 0 ? null : builder.toString();
            }
        }
    }

    private static void drainQuietly(InputStream stream) {
        try {
            while (stream.available() > 0) {
                if (stream.read() < 0) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cleanupQuietly(File payloadFile, File guardPipe, FileDescriptor guardHandle, RootShell rootShell) {
        deleteQuietly(payloadFile);
        deleteQuietly(guardPipe);
        closeQuietly(guardHandle);
        if (rootShell != null) {
            destroyQuietly(rootShell.process);
        }
    }

    private static void destroyQuietly(Process process) {
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private File createGuardPipe() {
        var directory = ensureCacheDir();
        var pipe = new File(directory, GUARD_PIPE_NAME.generate());
        deleteQuietly(pipe);
        try {
            Os.mkfifo(pipe.getAbsolutePath(), 0600);
        } catch (ErrnoException e) {
            throw new JavaShellArgumentException("failed to create guard pipe: " + e.getMessage(), e);
        }
        return pipe;
    }

    private FileDescriptor openGuardPipe(File pipe) {
        try {
            final int O_CLOEXEC = 0x80000;
            return Os.open(pipe.getAbsolutePath(), OsConstants.O_RDWR | O_CLOEXEC, 0);
        } catch (ErrnoException e) {
            throw new JavaShellArgumentException("failed to open guard pipe: " + e.getMessage(), e);
        }
    }

    private File writePayloadFile(String payload) {
        var directory = ensureCacheDir();
        var file = new File(directory, PAYLOAD_FILE_NAME.generate());
        deleteQuietly(file);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(payload.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            return file;
        } catch (IOException e) {
            deleteQuietly(file);
            throw new JavaShellArgumentException("failed to write payload file: " + e.getMessage(), e);
        }
    }

    private File ensureCacheDir() {
        if (cacheDir == null) {
            throw new JavaShellArgumentException("cacheDir must be set before execute()");
        }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new JavaShellArgumentException("cannot create cache directory: " + cacheDir);
        }
        return cacheDir;
    }

    private void cleanupStaleFiles(File directory) {
        var files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (var file : files) {
            var name = file.getName();
            var elementPayload = PAYLOAD_FILE_NAME.parse(name);
            if (elementPayload != null && elementPayload.isElapsedRealtimeExpired(60 * 60 * 1000L)) {
                deleteQuietly(file);
                continue;
            }
            var elementGuard = GUARD_PIPE_NAME.parse(name);
            if (elementGuard != null && elementGuard.isElapsedRealtimeExpired(60 * 60 * 1000L)) {
                deleteQuietly(file);
            }
        }
    }

    private static String join(Iterable<String> values, String separator) {
        var builder = new StringBuilder();
        for (var value : values) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
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

    private static void closeQuietly(FileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            Os.close(descriptor);
        } catch (Throwable ignored) {
        }
    }

    private static final class RootShell {
        final Process process;
        final String executable;

        RootShell(Process process, String executable) {
            this.process = process;
            this.executable = executable;
        }
    }
}
