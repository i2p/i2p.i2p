package net.i2p.router.transport.ntcp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.util.TryCache;

/** Exercises the actual NTCP reader and buffer pool without opening sockets. */
public final class NTCPReadBufferRegression {
    private static int checks;
    private static int failures;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("time.disabled", "true");
        RouterContext context = new RouterContext(null, properties);
        context.logManager().setDefaultLimit("CRIT");
        try {
            // The private base constructor avoids creating a handshake engine,
            // transport threads, or sockets. All receive/release code is real.
            Constructor<NTCPConnection> constructor = NTCPConnection.class.getDeclaredConstructor(
                    RouterContext.class, NTCPTransport.class, RouterAddress.class, boolean.class);
            constructor.setAccessible(true);
            Field stateField = field(NTCPConnection.class, "_establishState");
            Field queueField = field(NTCPConnection.class, "_readBufs");
            Field closedField = field(NTCPConnection.class, "_closed");
            TryCache<ByteBuffer> cache = (TryCache<ByteBuffer>) field(EventPumper.class, "_bufferCache").get(null);
            int size = field(EventPumper.class, "BUF_SIZE").getInt(null);
            Method process = Reader.class.getDeclaredMethod("processRead", NTCPConnection.class);
            process.setAccessible(true);
            Reader reader = new Reader(context);

            for (boolean established : new boolean[] { false, true }) {
                cache.clear();
                NTCPConnection connection = constructor.newInstance(context, null, null, true);
                // FAILED.receive() throws during a handshake. VERIFIED with no
                // read state throws in recvEncryptedI2NP(), as a close race can.
                stateField.set(connection, established ? EstablishBase.VERIFIED : EstablishBase.FAILED);
                ByteBuffer buffer = buffer(size);
                ((Queue<ByteBuffer>) queueField.get(connection)).offer(buffer);
                String phase = established ? "data phase failure" : "handshake failure";
                try {
                    process.invoke(reader, connection);
                    check(false, phase + " must propagate the receive exception");
                } catch (InvocationTargetException failure) {
                    check(failure.getCause() instanceof IllegalStateException,
                          phase + " preserves the receive exception type");
                }
                returnedOnce(cache, buffer, phase);
            }

            cache.clear();
            NTCPConnection closed = constructor.newInstance(context, null, null, true);
            ((AtomicBoolean) closedField.get(closed)).set(true);
            ByteBuffer discarded = buffer(size);
            closed.recv(discarded);
            returnedOnce(cache, discarded, "recv on a closed connection");

            cache.clear();
            NTCPConnection normal = constructor.newInstance(context, null, null, true);
            NormalEstablishState state = new NormalEstablishState();
            stateField.set(normal, state);
            ByteBuffer received = buffer(size);
            ((Queue<ByteBuffer>) queueField.get(normal)).offer(received);
            process.invoke(reader, normal);
            check(state.receives == 1, "normal receive processes the queued buffer exactly once");
            returnedOnce(cache, received, "normal receive");
            cache.clear();
        } finally {
            context.logManager().shutdown();
        }
        System.out.println("NTCP_READ_BUFFER_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0)
            System.exit(1);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ByteBuffer buffer(int size) {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.position(7);
        buffer.limit(12);
        return buffer;
    }

    private static void returnedOnce(TryCache<ByteBuffer> cache, ByteBuffer buffer, String name) {
        ByteBuffer first = cache.acquire();
        ByteBuffer second = cache.acquire();
        check(first == buffer, name + " returns the original buffer to the pool");
        check(buffer.position() == 0 && buffer.limit() == buffer.capacity(),
              name + " clears the returned buffer's position and limit");
        check(second != buffer, name + " returns the buffer only once");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    /** Minimal successful consumer; scheduling, queue access, and release stay real. */
    private static final class NormalEstablishState implements EstablishState {
        private int receives;
        public void receive(ByteBuffer buffer) {
            receives++;
            buffer.position(buffer.limit());
        }
        public void prepareOutbound() {}
        public boolean isCorrupt() { return false; }
        public boolean isComplete() { return false; }
        public int getVersion() { return 2; }
        public void close(String reason, Exception failure) {}
    }
}
