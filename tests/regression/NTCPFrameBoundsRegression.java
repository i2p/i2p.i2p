package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;

public final class NTCPFrameBoundsRegression implements NTCP2Payload.PayloadCallback {
    private final StringBuilder trace = new StringBuilder();
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        CTX.logManager().setDefaultLimit("CRIT");
        Random random = new Random(42);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int n = 0; n < 500; n++) {
            List<NTCP2Payload.Block> blocks = new ArrayList<>();
            blocks.add(new RawBlock(0, new byte[] {0, 0, 0, 7}));
            byte[] options = new byte[random.nextInt(65)];
            random.nextBytes(options);
            blocks.add(new NTCP2Payload.OptionsBlock(options));
            blocks.add(new RawBlock(99, new byte[] {1, 2, 3}));
            blocks.add(new RawBlock(3, new byte[] {(byte) 255, 0, 0, 0, 42, 0, 0, 0, 7}));
            if ((n & 1) == 0)
                blocks.add(new NTCP2Payload.TerminationBlock(9, 1234));
            blocks.add(new NTCP2Payload.PaddingBlock(random.nextInt(17)));
            int size = blocks.stream().mapToInt(NTCP2Payload.Block::getTotalLength).sum();
            byte[] frame = new byte[size + 23];
            Arrays.fill(frame, (byte) 0x55);
            int end = NTCP2Payload.writePayload(frame, 7, blocks);
            NTCPFrameBoundsRegression callbacks = new NTCPFrameBoundsRegression();
            int parsed = NTCP2Payload.processPayload(CTX, callbacks, frame, 7, size, false);
            check(end == 7 + size && parsed == blocks.size(), "valid frame round trip");
            digest.update(frame);
            digest.update(callbacks.trace.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("WIRE_TRACE_SHA256=" + HexFormat.of().formatHex(digest.digest()));
        signedHandshake();
        rejects("one-byte header in a larger buffer", new byte[] {99, 0, 0}, 0, 1);
        rejects("two-byte header in a larger buffer", new byte[] {99, 0, 0}, 0, 2);
        rejects("one-byte header without spare bytes", new byte[] {99}, 0, 1);
        rejects("zero-length RouterInfo", new byte[] {2, 0, 0}, 0, 3);
        rejects("slice exceeding the array", new byte[] {99, 0, 0}, 0, 4);
        rejects("negative offset", new byte[] {99, 0, 0}, -1, 3);
        rejects("negative length", new byte[] {99, 0, 0}, 0, -1);
        rejects("overflowing frame end", new byte[] {99, 0, 0}, 1, Integer.MAX_VALUE);
        System.out.println("FRAME_CHECKS=" + checks + "; FAILURES=" + failures);
        CTX.logManager().shutdown();
        if (failures != 0)
            System.exit(1);
    }

    @SuppressWarnings("deprecation")
    private static void signedHandshake() throws Exception {
        Object[] keys = KeyGenerator.getInstance().generateSigningKeypair();
        RouterIdentity identity = new RouterIdentity();
        identity.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        identity.setPublicKey(new PublicKey(new byte[256]));
        identity.setSigningPublicKey((SigningPublicKey) keys[0]);
        RouterInfo info = new RouterInfo();
        info.setIdentity(identity);
        info.setAddresses(Collections.emptyList());
        info.setPeers(Collections.emptySet());
        info.setOptions(new Properties());
        info.setPublished(1700000000000L);
        info.sign((SigningPrivateKey) keys[1]);
        List<NTCP2Payload.Block> blocks = Arrays.asList(new NTCP2Payload.RIBlock(info, false),
                new NTCP2Payload.OptionsBlock(new byte[0]), new NTCP2Payload.PaddingBlock(0));
        int length = blocks.stream().mapToInt(NTCP2Payload.Block::getTotalLength).sum();
        byte[] frame = new byte[length];
        NTCP2Payload.writePayload(frame, 0, blocks);
        NTCPFrameBoundsRegression callbacks = new NTCPFrameBoundsRegression();
        check(NTCP2Payload.processPayload(CTX, callbacks, frame, 0, length, true) == 3
              && callbacks.trace.toString().startsWith("ri=true,false;"), "signed legacy-type RI handshake");
    }

    private static void rejects(String name, byte[] frame, int offset, int length) {
        try {
            NTCP2Payload.processPayload(CTX, new NTCPFrameBoundsRegression(), frame, offset, length, false);
            check(false, name + " accepted");
        } catch (IOException expected) {
            check(true, name);
        } catch (Exception wrong) {
            check(false, name + " threw " + wrong.getClass().getSimpleName());
        }
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static final class RawBlock extends NTCP2Payload.Block {
        private final byte[] data;
        RawBlock(int type, byte[] bytes) { super(type); data = bytes; }
        public int getDataLength() { return data.length; }
        public int writeData(byte[] target, int offset) {
            System.arraycopy(data, 0, target, offset, data.length);
            return offset + data.length;
        }
    }

    public void gotDateTime(long time) { trace.append("time=").append(time).append(';'); }
    public void gotI2NP(I2NPMessage message) {
        trace.append("i2np=").append(message.getType()).append(',').append(message.getUniqueId())
             .append(',').append(message.getMessageExpiration()).append(';');
    }
    public void gotOptions(byte[] options, boolean handshake) {
        trace.append("options=").append(HexFormat.of().formatHex(options)).append(',').append(handshake).append(';');
    }
    public void gotRI(RouterInfo info, boolean handshake, boolean flood) {
        trace.append("ri=").append(handshake).append(',').append(flood).append(';');
    }
    public void gotTermination(int reason, long received) { trace.append("term=").append(reason).append(',').append(received).append(';'); }
    public void gotPadding(int length, int frameLength) { trace.append("pad=").append(length).append(',').append(frameLength).append(';'); }
    public void gotUnknown(int type, int length) { trace.append("unknown=").append(type).append(',').append(length).append(';'); }
}
