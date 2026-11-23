package i2p.susi.webmail;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Bounded login work and failure windows; no request threads sleep during lockouts. */
final class LoginLimiter {
    static final Semaphore WORK = new Semaphore(4);
    private static final Map<String, ArrayDeque<Long>> FAILURES = new LinkedHashMap<String, ArrayDeque<Long>>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> entry) { return size() > 2048; }
    };
    private static final ArrayDeque<Long> GLOBAL = new ArrayDeque<Long>();

    static synchronized boolean allowed(String account, long now) {
        expire(GLOBAL, now);
        ArrayDeque<Long> failed = FAILURES.get(account);
        if (failed != null) expire(failed, now);
        return GLOBAL.size() < 50 && (failed == null || failed.size() < 5);
    }
    static synchronized void failed(String account, long now) {
        expire(GLOBAL, now);
        if (GLOBAL.size() < 50) GLOBAL.addLast(now);
        ArrayDeque<Long> failed = FAILURES.computeIfAbsent(account, ignored -> new ArrayDeque<Long>());
        expire(failed, now);
        if (failed.size() < 5) failed.addLast(now);
    }
    static synchronized void succeeded(String account) { FAILURES.remove(account); }
    private static void expire(ArrayDeque<Long> queue, long now) {
        while (!queue.isEmpty() && (queue.peekFirst() < now - 60000 || queue.peekFirst() > now + 60000)) queue.removeFirst();
    }
}
