package i2p.susi.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Absolute response deadline as well as a socket idle timeout, including slow partial lines. */
public final class TimedResponseInputStream extends FilterInputStream {
    private final Socket socket;
    private final long deadline;
    private final int idle;
    private long adjusted;

    public TimedResponseInputStream(Socket socket, int milliseconds) throws IOException {
        super(socket.getInputStream());
        this.socket = socket;
        deadline = System.nanoTime() + milliseconds * 1000000L;
        int configured = socket.getSoTimeout();
        idle = configured > 0 ? Math.min(configured, milliseconds) : milliseconds;
        check();
    }
    private void check() throws IOException {
        long now = System.nanoTime(), remaining = deadline - now;
        if (remaining <= 0) throw new SocketTimeoutException("Mail server response timed out");
        if (adjusted == 0 || now - adjusted >= 1000000000L) {
            socket.setSoTimeout((int) Math.max(1, Math.min(idle, (remaining + 999999) / 1000000)));
            adjusted = now;
        }
    }
    @Override public int read() throws IOException { check(); int value = in.read(); check(); return value; }
    @Override public int read(byte[] bytes, int offset, int count) throws IOException { check(); int value = in.read(bytes, offset, count); check(); return value; }
}
