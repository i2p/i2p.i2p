package net.i2p.router.tunnel;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/** Exercises the real preprocessor/parser, bounded reassembly, and malformed-message logs. */
public final class TunnelFragmentRegression {
    private static int checks;
    private static int failures;
    private static final byte[] MARKER = "TUNNEL-PRIVATE-PAYLOAD-MARKER".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        QuietContext ctx = new QuietContext();
        ctx.logManager().setDefaultLimit("CRIT");
        try {
            List<I2NPMessage> delivered = new ArrayList<>();
            FragmentHandler handler = new FragmentHandler(ctx, (message, router, tunnel) -> delivered.add(message), true);
            Capture log = new Capture();
            Field logger = FragmentHandler.class.getDeclaredField("_log");
            logger.setAccessible(true);
            logger.set(handler, log);
            Field field = FragmentHandler.class.getDeclaredField("_fragmentedMessages");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, FragmentedMessage> pending = (Map<Long, FragmentedMessage>) field.get(handler);
            Preprocessor preprocessor = new Preprocessor(ctx);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int size : new int[] { 1, 29, 512 }) {
                DataMessage message = message(ctx, size);
                byte[] wire = message.toByteArray();
                byte[] packet = initial(preprocessor, 0, wire, false);
                check(handler.receiveTunnelMessage(packet, 0, packet.length), "ordinary unfragmented message " + size);
                check(Arrays.equals(wire, delivered.get(delivered.size() - 1).toByteArray()), "unfragmented bytes preserved " + size);
                digest.update(delivered.get(delivered.size() - 1).toByteArray());
            }
            // All 64 legal fragment numbers, with the last fragment arriving first.
            DataMessage many = message(ctx, 44);
            byte[] manyWire = many.toByteArray();
            check(manyWire.length == 64, "maximum-fragment fixture has 64 bytes");
            for (int number = 63; number > 0; number--) {
                byte[] packet = fragment(preprocessor, 900, number, new byte[] { manyWire[number] }, number == 63);
                check(handler.receiveTunnelMessage(packet, 0, packet.length), "reverse-order fragment " + number);
            }
            byte[] manyHead = initial(preprocessor, 900, new byte[] { manyWire[0] }, true);
            check(handler.receiveTunnelMessage(manyHead, 0, manyHead.length), "maximum-fragment message completed");
            check(delivered.size() == 4 && Arrays.equals(manyWire, delivered.get(3).toByteArray()), "all fragment bytes delivered in order");
            digest.update(delivered.get(3).toByteArray());
            System.out.println("TUNNEL_DELIVERY_SHA256=" + HexFormat.of().formatHex(digest.digest()));
            delivered.clear();

            // Each invalid new ID previously left an unscheduled, permanent map entry.
            for (int i = 0; i < 160; i++) {
                byte[] packet = fragment(preprocessor, i + 1, i % 3 == 0 ? 0 : 1, new byte[0], i % 2 == 0);
                check(!handler.receiveTunnelMessage(packet, 0, packet.length), "zero-size fragment rejected " + i);
            }
            check(pending.isEmpty(), "invalid fragments retain no reassembly slots");
            byte[] badNumber = fragment(preprocessor, 1001, 0, MARKER, true);
            check(!handler.receiveTunnelMessage(badNumber, 0, badNumber.length), "follow-on number zero rejected");
            check(pending.isEmpty(), "bad fragment number retains no reassembly slot");
            byte[] emptyInitial = initial(preprocessor, 1002, new byte[0], true);
            check(!handler.receiveTunnelMessage(emptyInitial, 0, emptyInitial.length), "zero-size initial fragment rejected");
            byte[] overInitial = initial(preprocessor, 1003, MARKER, true, 4096);
            check(!handler.receiveTunnelMessage(overInitial, 0, overInitial.length), "oversized initial fragment rejected");
            byte[] overFollow = fragment(preprocessor, 1004, 1, MARKER, true, 4096);
            check(!handler.receiveTunnelMessage(overFollow, 0, overFollow.length), "oversized follow-on fragment rejected");
            check(pending.isEmpty(), "invalid declared lengths retain no reassembly slots");

            DataMessage original = new DataMessage(ctx);
            original.setData(MARKER);
            original.setUniqueId(42);
            original.setMessageExpiration(1700000000000L);
            byte[] wire = original.toByteArray();
            int split = wire.length / 2;
            byte[] first = Arrays.copyOf(wire, split);
            byte[] last = Arrays.copyOfRange(wire, split, wire.length);
            // A valid fragmented message must still pass after the malformed flood.
            byte[] tail = fragment(preprocessor, 2001, 1, last, true);
            check(handler.receiveTunnelMessage(tail, 0, tail.length), "out-of-order tail accepted");
            byte[] head = initial(preprocessor, 2001, first, true);
            check(handler.receiveTunnelMessage(head, 0, head.length), "initial fragment accepted after flood");
            check(delivered.size() == 1 && Arrays.equals(wire, delivered.get(0).toByteArray()), "exact reassembled bytes delivered");
            check(pending.isEmpty(), "successful reassembly clears state");
            clear(pending);

            // These cover payload-dumping WARN/INFO paths with real parser errors.
            log.messages.setLength(0);
            byte[] corrupt = wire.clone();
            corrupt[16] = 0x7f; // Impossible DataMessage length; retain the synthetic payload.
            byte[] single = initial(preprocessor, 0, corrupt, false);
            handler.receiveTunnelMessage(single, 0, single.length);
            check(log.messages.toString().contains("Error receiving unfragmented message"), "actual unfragmented failure diagnostic captured");
            check(!log.messages.toString().contains(Base64.encode(corrupt)), "unfragmented payload omitted from logs");
            check(!log.messages.toString().contains(new String(MARKER, StandardCharsets.US_ASCII)), "unfragmented text omitted from logs");

            log.messages.setLength(0);
            byte[] brokenFirst = initial(preprocessor, 2002, Arrays.copyOf(corrupt, split), true);
            byte[] brokenLast = fragment(preprocessor, 2002, 1, Arrays.copyOfRange(corrupt, split, corrupt.length), true);
            handler.receiveTunnelMessage(brokenFirst, 0, brokenFirst.length);
            handler.receiveTunnelMessage(brokenLast, 0, brokenLast.length);
            check(log.messages.toString().contains("Error receiving fragmented message"), "actual reassembled failure diagnostic captured");
            check(!log.messages.toString().contains(Base64.encode(corrupt)), "reassembled payload omitted from logs");
            check(!log.messages.toString().contains(new String(MARKER, StandardCharsets.US_ASCII)), "reassembled text omitted from logs");

            log.messages.setLength(0);
            byte[] badChecksum = initial(preprocessor, 0, wire, false);
            badChecksum[16] ^= 1;
            String checksumPayload = Base64.encode(badChecksum);
            check(!handler.receiveTunnelMessage(badChecksum, 0, badChecksum.length), "bad checksum rejected");
            check(!log.messages.toString().contains(checksumPayload), "checksum failure payload omitted from logs");

            log.messages.setLength(0);
            byte[] noTerminator = new byte[1024];
            Arrays.fill(noTerminator, (byte) 'Q');
            String paddingPayload = Base64.encode(noTerminator);
            check(!handler.receiveTunnelMessage(noTerminator, 0, noTerminator.length), "unterminated padding rejected");
            check(!log.messages.toString().contains(paddingPayload), "padding failure payload omitted from logs");
            check(delivered.size() == 1, "malformed inner messages never delivered");
            // Clean up original-code retained state as well, so the before run is bounded.
            clear(pending);
            System.out.println("TUNNEL_FRAGMENT_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("tunnel fragment admission/privacy failure");
        } finally {
            ctx.simpleTimer2().stop();
            ctx.logManager().shutdown();
        }
    }

    private static byte[] initial(Preprocessor prep, long id, byte[] payload, boolean fragmented) {
        return initial(prep, id, payload, fragmented, payload.length);
    }
    private static byte[] initial(Preprocessor prep, long id, byte[] payload, boolean fragmented, int declaredSize) {
        byte[] packet = new byte[1024];
        packet[0] = fragmented ? (byte) 8 : 0;
        int offset = 1;
        if (fragmented) { DataHelper.toLong(packet, offset, 4, id); offset += 4; }
        DataHelper.toLong(packet, offset, 2, declaredSize); offset += 2;
        System.arraycopy(payload, 0, packet, offset, payload.length);
        prep.wrap(packet, offset + payload.length);
        return packet;
    }
    private static byte[] fragment(Preprocessor prep, long id, int number, byte[] payload, boolean last) {
        return fragment(prep, id, number, payload, last, payload.length);
    }
    private static byte[] fragment(Preprocessor prep, long id, int number, byte[] payload, boolean last, int declaredSize) {
        byte[] packet = new byte[1024];
        packet[0] = (byte) (128 | (number << 1) | (last ? 1 : 0));
        DataHelper.toLong(packet, 1, 4, id);
        DataHelper.toLong(packet, 5, 2, declaredSize);
        System.arraycopy(payload, 0, packet, 7, payload.length);
        prep.wrap(packet, 7 + payload.length);
        return packet;
    }
    private static void check(boolean ok, String name) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + name); } }
    private static DataMessage message(RouterContext ctx, int size) {
        DataMessage result = new DataMessage(ctx);
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 17 + 3);
        result.setData(bytes);
        result.setUniqueId(42);
        result.setMessageExpiration(1700000000000L);
        return result;
    }
    private static void clear(Map<Long, FragmentedMessage> pending) {
        synchronized (pending) {
            for (FragmentedMessage message : pending.values()) {
                if (message.getExpireEvent() != null) message.getExpireEvent().cancel();
                message.failed();
            }
            pending.clear();
        }
    }
    private static final class Preprocessor extends TrivialPreprocessor {
        Preprocessor(RouterContext ctx) { super(ctx); }
        void wrap(byte[] packet, int size) { preprocess(packet, size); }
    }
    private static final class Capture extends Log {
        final StringBuilder messages = new StringBuilder();
        Capture() { super(TunnelFragmentRegression.class); }
        @Override public boolean shouldLog(int level) { return true; }
        @Override public boolean shouldInfo() { return true; }
        @Override public boolean shouldWarn() { return true; }
        @Override public void debug(String value) { messages.append(value).append('\n'); }
        @Override public void info(String value) { messages.append(value).append('\n'); }
        @Override public void warn(String value) { messages.append(value).append('\n'); }
        @Override public void warn(String value, Throwable error) { messages.append(value).append('\n').append(error).append('\n'); }
    }
    private static final class QuietContext extends RouterContext {
        QuietContext() { super(null, new Properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
    }
}
