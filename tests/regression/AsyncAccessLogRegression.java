package net.i2p.jetty;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppState;
import net.i2p.router.RouterContext;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.servlet.filters.XI2PLocationFilter;

/** Controlled slow I/O and HTTP lifecycle checks, without network sockets. */
public class AsyncAccessLogRegression {
    private static int checks, failures;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + message); }
    }

    private static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError(label);
    }

    private static int status(String response) { return Integer.parseInt(response.substring(9, 12)); }
    private static String wire(String path, String headers) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost:7658\r\nConnection: close\r\n" + headers + "\r\n";
    }

    private static Handler successful() {
        return new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                response.write(true, BufferUtil.toBuffer("ok"), callback);
                return true;
            }
        };
    }

    private static class SlowWriter extends Writer {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final StringBuilder records = new StringBuilder();
        boolean closed;
        public void write(char[] chars, int offset, int length) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test gate timeout");
            } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            records.append(chars, offset, length);
        }
        public void flush() {}
        public void close() { closed = true; }
    }

    private static void logging() throws Exception {
        SlowWriter sink = new SlowWriter();
        AsyncLogWriter writer = new AsyncLogWriter(sink, true, 2);
        writer.start();
        try {
            writer.write("first\n");
            check(sink.entered.await(2, TimeUnit.SECONDS), "slow log sink is active");
            long before = System.nanoTime();
            for (int i = 0; i < 10; i++) writer.write("queued" + i + "\n");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500, "a stalled log disk does not block request submission");
            check(writer.getQueuedRecords() == 2 && writer.getDroppedRecords() == 8, "log queue capacity and dropped records are bounded and visible");
        } finally { sink.release.countDown(); writer.stop(); }
        check(sink.closed && sink.records.toString().equals("first\nqueued0\nqueued1\n"), "shutdown drains accepted log records in order and closes the sink");
        writer.write("after stop\n");
        check(writer.getDroppedRecords() == 9, "late log submission cannot race past shutdown");

    }

    public static void main(String[] args) throws Exception {
        logging();
        System.out.println("CHANGE_CHECKS=" + checks + "; FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
