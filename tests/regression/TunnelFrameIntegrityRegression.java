package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;

/** Checks frame slice boundaries, real tunnel checksums and replay validation. */
public final class TunnelFrameIntegrityRegression {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        QuietContext ctx = new QuietContext();
        ctx.logManager().setDefaultLimit("CRIT");
        List<I2NPMessage> delivered = new ArrayList<>();
        FragmentHandler handler = new FragmentHandler(ctx, (m, r, t) -> delivered.add(m), true);
        Preprocessor prep = new Preprocessor(ctx);
        BloomFilterIVValidator replay = new BloomFilterIVValidator(ctx, 0);
        try {
            for (int size : new int[] {1, 29, 512}) {
                DataMessage message = new DataMessage(ctx);
                byte[] body = new byte[size]; Arrays.fill(body, (byte) 7);
                message.setData(body); message.setUniqueId(17); message.setMessageExpiration(1700000000000L);
                byte[] wire = message.toByteArray();
                for (int offset : new int[] {0, 1, 17, 1024}) {
                    byte[] packet = packet(prep, wire);
                    byte[] wrapped = wrap(packet, offset);
                    int before = delivered.size();
                    boolean accepted;
                    try { accepted = handler.receiveTunnelMessage(wrapped, offset, packet.length); }
                    catch (RuntimeException e) { accepted = false; }
                    check(accepted, "valid frame at offset " + offset);
                    check(delivered.size() == before + 1 && Arrays.equals(wire, delivered.get(before).toByteArray()),
                          "exact message bytes at offset " + offset);
                }
            }
            DataMessage message = new DataMessage(ctx); message.setData(new byte[32]);
            message.setUniqueId(19); message.setMessageExpiration(1700000000000L);
            byte[] wire = message.toByteArray();
            for (int offset : new int[] {0, 1, 17, 1024}) {
                // Every IV/checksum byte and the final eight payload bytes.
                for (int byteIndex = 0; byteIndex < 28; byteIndex++) {
                    int index = byteIndex < 20 ? byteIndex : 1024 - 8 + byteIndex - 20;
                    byte[] packet = packet(prep, wire); packet[index] ^= 1;
                    int before = delivered.size();
                    check(rejected(handler, wrap(packet, offset), offset, 1024), "tamper rejected: " + offset + "/" + index);
                    check(delivered.size() == before, "tamper never delivers");
                }
            }
            for (int[] slice : new int[][] {{-1,1024}, {0,-1}, {0,0}, {0,20}, {0,1023}, {0,1025},
                                          {1025,1024}, {Integer.MAX_VALUE,1024}, {1,Integer.MAX_VALUE}}) {
                check(rejected(handler, new byte[1024], slice[0], slice[1]), "invalid slice rejected without throwing");
            }
            check(rejected(handler, null, 0, 1024), "null frame rejected without throwing");
            for (int i = 0; i < 64; i++) {
                byte[] iv = new byte[16]; DataHelper.toLong(iv, 0, 4, i + 1);
                byte[] payload = new byte[16]; payload[8] = 9;
                check(replay.receiveIV(iv, 0, payload, 0), "fresh replay token accepted");
                check(!replay.receiveIV(iv, 0, payload, 0), "repeated token rejected");
            }
            System.out.println("TUNNEL_FRAME_INTEGRITY_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("tunnel frame integrity");
        } finally {
            replay.destroy(); ctx.simpleTimer2().stop(); ctx.logManager().shutdown();
        }
    }

    private static boolean rejected(FragmentHandler h, byte[] data, int offset, int length) {
        try { return !h.receiveTunnelMessage(data, offset, length); }
        catch (RuntimeException e) { return false; }
    }
    private static byte[] packet(Preprocessor prep, byte[] wire) {
        byte[] frame = new byte[1024];
        DataHelper.toLong(frame, 1, 2, wire.length);
        System.arraycopy(wire, 0, frame, 3, wire.length);
        prep.wrap(frame, 3 + wire.length);
        return frame;
    }
    private static byte[] wrap(byte[] frame, int offset) {
        byte[] wrapped = new byte[offset + frame.length + 9];
        Arrays.fill(wrapped, (byte) 0x7f);
        System.arraycopy(frame, 0, wrapped, offset, frame.length);
        return wrapped;
    }
    private static void check(boolean ok, String name) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + name); } }
    private static final class Preprocessor extends TrivialPreprocessor {
        Preprocessor(RouterContext ctx) { super(ctx); }
        void wrap(byte[] frame, int size) { preprocess(frame, size); }
    }
    private static final class QuietContext extends RouterContext {
        QuietContext() { super(null, new Properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
    }
}
