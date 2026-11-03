# AndroidJavaShell

[![Release](https://jitpack.io/v/topdeepsk/AndroidJavaShell.svg)](https://jitpack.io/#topdeepsk/AndroidJavaShell)

**English** | [简体中文](README.zh-CN.md)

Run Java code in a separate Android process, optionally as `root`, and call it from your app.

Useful when work is blocking or needs privileges that your app process should not hold: run it in a child
process, stream its output back, or expose a class as a proxy and call its methods directly.

## Installation

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
implementation("com.github.topdeepsk.AndroidJavaShell:core:1.0.0")     // run code in a child process
implementation("com.github.topdeepsk.AndroidJavaShell:service:1.0.0")  // + call a class through a proxy
```

## Usage

### Run code in a child process

Implement `JavaShellEntry` and hand the class to `JavaShell`:

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

Arguments are passed as a serialized payload, so they are not limited by command-line length:

```java
JavaShellProcess process = shell.execute(MyEntry.class, "job-42", 1000, new String[] {"a", "b"});
```

### Stream the output

```java
JavaShellProcess process = shell.execute(MyEntry.class);

process.setListener(new JavaShellListener() {
    @Override
    public void onStdout(String line) {
        Log.d(TAG, line.trim());          // line keeps its trailing newline
    }

    @Override
    public void onStderr(String line) {
        Log.w(TAG, line.trim());
    }

    @Override
    public void onExit(int exitCode) {
        Log.d(TAG, "exit " + exitCode);   // called exactly once
    }
});

process.waitFor();
```

Set the listener right after `execute()`: the output is drained on background threads, so a chatty child
never blocks on a full pipe.

### Run as root

```java
JavaShell shell = JavaShell.createUseRoot(context);    // falls back to a normal process without root
JavaShell shell = JavaShell.createForceRoot(context);  // throws JavaShellArgumentException without root
```

`createUseRoot()` also enables `terminateOnParentExit`, so the child dies with your app. When configuring a
shell manually, pair `setUseRoot()` with `setTerminateOnParentExit(true)`.

### Call a class in another process

```java
public class Calculator implements JavaShellRemoteService {
    public int add(int a, int b) {   // runs in the child process
        return a + b;
    }
}

JavaShellService service = new JavaShellService(context)
        .setJavaShell(new JavaShell(context));

try (JavaShellServiceProcess<Calculator> session = service.createService(Calculator.class)) {
    int sum = session.getService().add(1, 2);
}
```

The class must be concrete, non-final and implement `JavaShellRemoteService`. Exceptions from the child
process are rethrown to the caller with their original type.

## Requirements

- `minSdk` 21, Java 11
- ProGuard/R8 rules are bundled in the AARs

## License

[Apache License 2.0](LICENSE)
