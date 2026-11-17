package net.i2p.jetty;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/** A global token bucket: I2P clients must not be grouped by loopback IP. */
public class RequestRateLimitHandler extends Handler.Wrapper {
    private double requestsPerSecond = 64;
    private int burst = 128;
    private double tokens;
    private long lastRefill;
    private final AtomicLong rejected = new AtomicLong();

    public synchronized void setRequestsPerSecond(double rate) {
        if (isRunning()) throw new IllegalStateException("Configure before starting");
        if (!(rate > 0) || !Double.isFinite(rate)) throw new IllegalArgumentException("Positive finite rate required");
        requestsPerSecond = rate;
    }

    public synchronized double getRequestsPerSecond() { return requestsPerSecond; }

    public synchronized void setBurst(int capacity) {
        if (isRunning()) throw new IllegalStateException("Configure before starting");
        if (capacity <= 0) throw new IllegalArgumentException("Positive burst required");
        burst = capacity;
    }

    public synchronized int getBurst() { return burst; }
    public long getRejectedRequests() { return rejected.get(); }
    protected long nanoTime() { return System.nanoTime(); }

    @Override
    protected void doStart() throws Exception {
        synchronized (this) {
            tokens = burst;
            lastRefill = nanoTime();
        }
        super.doStart();
    }

    private synchronized boolean admit() {
        long now = nanoTime();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(burst, tokens + elapsed * (requestsPerSecond / 1_000_000_000d));
            lastRefill = now;
        }
        if (tokens < 1)
            return false;
        tokens -= 1;
        return true;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!admit()) {
            rejected.incrementAndGet();
            response.setStatus(429);
            response.getHeaders().put("Retry-After", Long.toString(Math.max(1L, (long)Math.ceil(1 / requestsPerSecond))));
            response.getHeaders().put("Cache-Control", "no-store");
            callback.succeeded();
            return true;
        }
        return super.handle(request, response, callback);
    }
}
