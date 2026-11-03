package com.github.topdeepsk.androidjavashell.core;

/**
 * 子进程输出与退出事件的回调。
 *
 * <p>设置回调后由框架在后台线程读走标准输出与标准错误，调用方不需要自己起线程，
 * 也不会因为管道缓冲写满而导致子进程阻塞。</p>
 *
 * <p>三个方法都在框架的后台线程上执行，不要在其中做长时间阻塞的操作；
 * {@link #onExit(int)} 保证一次会话只回调一次，且排在所有输出回调之后。</p>
 */
public class JavaShellListener {

    /**
     * 子进程标准输出产生一行时回调。
     *
     * <p>参数是<b>包含换行符的完整一行</b>（读到 {@code \n} 为止，换行符原样保留），
     * 因此直接拼接进日志不会丢掉行边界；末尾没有换行符的最后一行按实际情况原样给出。</p>
     *
     * @param line 输出内容，含行尾换行符
     */
    public void onStdout(String line) {
    }

    /**
     * 子进程标准错误产生一行时回调。
     *
     * <p>与 {@link #onStdout(String)} 一致，参数是包含换行符的完整一行。</p>
     *
     * @param line 输出内容，含行尾换行符
     */
    public void onStderr(String line) {
    }

    /**
     * 子进程退出时回调，只会回调一次。
     *
     * @param exitCode 进程退出码
     */
    public void onExit(int exitCode) {
    }
}
