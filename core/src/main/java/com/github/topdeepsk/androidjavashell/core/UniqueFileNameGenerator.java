package com.github.topdeepsk.androidjavashell.core;

import android.os.Process;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class UniqueFileNameGenerator {

    private final String prefix;
    private final String suffix;
    private final AtomicInteger sequence = new AtomicInteger();

    public UniqueFileNameGenerator(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    public String generate() {
        return String.format(Locale.getDefault(), "%s-%d-%d-%d.%s", prefix, Process.myPid(),
                sequence.incrementAndGet(), SystemClock.elapsedRealtime(), suffix);
    }

    /**
     * 把 {@link #generate()} 产出的文件名还原成 {@link Element}，不是本生成器的产物时返回 null。
     *
     * <p>判定是严格的：必须以 {@code prefix + "-"} 开头、以 {@code "." + suffix} 结尾，
     * 中间恰好是 pid、序号、elapsedRealtime 三段十进制数字。这样同一个目录里多种前缀的文件
     * 不会互相误判，长度截断、手改过的名字也会落成 null 而不是解析出错误的 pid。</p>
     */
    public Element parse(String filename) {
        if (filename == null) {
            return null;
        }
        var head = prefix + "-";
        var tail = "." + suffix;
        if (filename.length() <= head.length() + tail.length()
                || !filename.startsWith(head)
                || !filename.endsWith(tail)) {
            return null;
        }
        var middle = filename.substring(head.length(), filename.length() - tail.length());
        var parts = middle.split("-", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            var pid = Integer.parseInt(parts[0]);
            var sequence = Integer.parseInt(parts[1]);
            var nanoTime = Long.parseLong(parts[2]);
            return new Element(pid, sequence, nanoTime);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class Element {
        private final int pid;
        private final int sequence;
        private final long elapsedRealtime;

        public Element(int pid, int sequence, long elapsedRealtime) {
            this.pid = pid;
            this.sequence = sequence;
            this.elapsedRealtime = elapsedRealtime;
        }

        public int getPid() { return pid; }
        public int getSequence() { return sequence; }
        public long getElapsedRealtime() { return elapsedRealtime; }

        public boolean isElapsedRealtimeExpired(long maxAgeMillis) {
            long now = SystemClock.elapsedRealtime();
            return elapsedRealtime < now || elapsedRealtime > now + TimeUnit.MILLISECONDS.toMillis(maxAgeMillis);
        }
    }
}
