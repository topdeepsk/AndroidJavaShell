# AndroidJavaShell

[![Release](https://jitpack.io/v/topdeepsk/AndroidJavaShell.svg)](https://jitpack.io/#topdeepsk/AndroidJavaShell)

[English](README.md) | **简体中文**

在独立的 Android 进程中运行 Java 代码（可选 `root`），并从应用进程里调用它。

适合不适合放在应用进程里做的事：阻塞任务、需要特权的操作。把它放进子进程运行、把输出实时回传，
或者把一个类包装成代理，在应用进程里直接调它的方法。

## 安装

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
implementation("com.github.topdeepsk.AndroidJavaShell:core:1.0.0")     // 在子进程中运行代码
implementation("com.github.topdeepsk.AndroidJavaShell:service:1.0.0")  // 额外支持跨进程调用类
```

## 用法

### 在子进程中运行代码

实现 `JavaShellEntry`，把类交给 `JavaShell`：

```java
public final class MyEntry implements JavaShellEntry {
    @Override
    public void main(Object[] args) {
        System.out.println("pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid());
    }
}

JavaShell shell = new JavaShell(context);
JavaShellProcess process = shell.execute(MyEntry.class);
int exitCode = process.waitFor();
```

参数以序列化载荷传递，不受命令行长度限制：

```java
JavaShellProcess process = shell.execute(MyEntry.class, "job-42", 1000, new String[] {"a", "b"});
```

### 读取输出

```java
JavaShellProcess process = shell.execute(MyEntry.class);

process.setListener(new JavaShellListener() {
    @Override
    public void onStdout(String line) {
        Log.d(TAG, line.trim());          // line 保留行尾换行符
    }

    @Override
    public void onStderr(String line) {
        Log.w(TAG, line.trim());
    }

    @Override
    public void onExit(int exitCode) {
        Log.d(TAG, "exit " + exitCode);   // 只会回调一次
    }
});

process.waitFor();
```

监听器要在 `execute()` 之后立刻设置：输出由后台线程读走，不会因为管道写满而卡住子进程。

### 以 root 运行

```java
JavaShell shell = JavaShell.createUseRoot(context);    // 没有 root 时回退为普通进程
JavaShell shell = JavaShell.createForceRoot(context);  // 没有 root 时抛 JavaShellArgumentException
```

`createUseRoot()` 同时会开启 `terminateOnParentExit`，应用结束时子进程一起退出。自己组装 `JavaShell` 时，
请把 `setUseRoot()` 和 `setTerminateOnParentExit(true)` 一起用。

### 调用另一个进程里的类

```java
public class Calculator implements JavaShellRemoteService {
    public int add(int a, int b) {   // 在子进程中执行
        return a + b;
    }
}

JavaShellService service = new JavaShellService(context)
        .setJavaShell(new JavaShell(context));

try (JavaShellServiceProcess<Calculator> session = service.createService(Calculator.class)) {
    int sum = session.getService().add(1, 2);
}
```

该类必须是具体类、非 `final`，并实现 `JavaShellRemoteService`。子进程抛出的异常会按原类型抛回调用方。

## 环境要求

- `minSdk` 21，Java 11
- 混淆规则已随 AAR 打包

## 许可证

[Apache License 2.0](LICENSE)
