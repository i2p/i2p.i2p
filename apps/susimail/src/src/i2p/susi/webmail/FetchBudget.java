package i2p.susi.webmail;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** Shared across sessions: bound both download attempts and actual received bytes. */
final class FetchBudget {
    static final int BATCH = 20;
    static final int MAX_MESSAGES = 10000;
    private static final ConcurrentHashMap<Path, FetchBudget> ACCOUNTS = new ConcurrentHashMap<Path, FetchBudget>();
    private long window = System.nanoTime();
    private int messages;
    private long bytes;

    static FetchBudget forAccount(Path account) { return ACCOUNTS.computeIfAbsent(account, ignored -> new FetchBudget()); }
    private void reset() {
        if (System.nanoTime() - window >= 60000000000L) { window = System.nanoTime(); messages = 0; bytes = 0; }
    }
    synchronized boolean message() { reset(); if (messages >= 100 || bytes >= 32L * 1024 * 1024) return false; messages++; return true; }
    synchronized void received(int count) throws IOException {
        reset();
        if (count > 32L * 1024 * 1024 - bytes) throw new IOException("Mail download paused at the account's per-minute limit. Try again shortly.");
        bytes += count;
    }
    OutputStream limit(OutputStream target) {
        return new FilterOutputStream(target) {
            @Override public void write(int value) throws IOException { received(1); out.write(value); }
            @Override public void write(byte[] data, int offset, int count) throws IOException { received(count); out.write(data, offset, count); }
        };
    }
}
