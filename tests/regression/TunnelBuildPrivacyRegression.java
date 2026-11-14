package net.i2p.util;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.ChaCha20;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortEncryptedBuildRecord;
import net.i2p.data.i2np.ShortTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.router.tunnel.HopConfig;

/** Disposable keys, real record encryption/decryption, and actual diagnostic calls. */
public final class TunnelBuildPrivacyRegression {
    private static int checks;
    private static int failures;
    private static final String OPTION = "TUNNEL-PRIVATE-OPTION-MARKER";

    private static final class Capture extends LogManager {
        private final StringBuilder messages = new StringBuilder();
        Capture(I2PAppContext ctx) { super(ctx); }
        @Override synchronized void addRecord(LogRecord record) {
            if (messages != null && (record.getSource() == BuildRequestRecord.class
                    || record.getSource().getName().equals("net.i2p.router.tunnel.pool.BuildReplyHandler"))) {
                messages.append(record.getMessage()).append('\n');
                if (record.getThrowable() != null)
                    messages.append(record.getThrowable()).append('\n');
            }
        }
        synchronized String text() { return messages.toString(); }
        synchronized void clear() { messages.setLength(0); }
    }

    public static void main(String[] args) throws Exception {
        QuietContext ctx = new QuietContext();
        Capture capture = new Capture(ctx);
        ctx.setLogManager(capture);
        capture.setDefaultLimit("DEBUG");
        ctx.comm = new CryptoComm(ctx);
        try {
            for (int mode = 0; mode < 4; mode++) {
                KeyPair pair = ctx.keyGenerator().generatePKIKeys(mode == 0 ? EncType.ELGAMAL_2048 : EncType.ECIES_X25519);
                Hash peer = new Hash(bytes(32, 81));
                Hash next = new Hash(bytes(32, 82));
                SessionKey layer = new SessionKey(bytes(32, 83));
                SessionKey ivKey = new SessionKey(bytes(32, 84));
                SessionKey reply = new SessionKey(bytes(32, 85));
                byte[] iv = bytes(16, 86);
                Properties options = new Properties();
                options.setProperty("review.marker", OPTION);
                BuildRequestRecord record;
                if (mode == 0)
                    record = new BuildRequestRecord(ctx, 11, peer, 12, next, 13, layer, ivKey, reply, iv, false, false);
                else if (mode == 1)
                    record = new BuildRequestRecord(ctx, 11, 12, next, 13, layer, ivKey, reply, iv, false, false, options);
                else
                    record = new BuildRequestRecord(ctx, 11, 12, next, 13, mode == 2, mode == 3, options);
                byte[] before = record.getData().clone();
                EncryptedBuildRecord encrypted = mode == 0 ? record.encryptRecord(ctx, pair.getPublic(), peer)
                                                           : record.encryptECIESRecord(ctx, pair.getPublic(), peer);
                BuildRequestRecord decoded = new BuildRequestRecord(ctx, pair.getPrivate(), encrypted);
                check(Arrays.equals(before, decoded.getData()), "plaintext round trip, mode " + mode);
                check(decoded.readReceiveTunnelId() == 11 && decoded.readNextTunnelId() == 12
                      && decoded.readNextIdentity().equals(next) && decoded.readReplyMessageId() == 13,
                      "routing fields preserved, mode " + mode);
                check(Arrays.equals(record.readLayerKey().getData(), decoded.readLayerKey().getData())
                      && Arrays.equals(record.readIVKey().getData(), decoded.readIVKey().getData()),
                      "layer keys preserved, mode " + mode);
                if (mode != 0) {
                    check(OPTION.equals(decoded.readOptions().getProperty("review.marker")), "wire options preserved, mode " + mode);
                    check(record.getChaChaReplyKey().equals(decoded.getChaChaReplyKey())
                          && Arrays.equals(record.getChaChaReplyAD(), decoded.getChaChaReplyAD()),
                          "reply derivation preserved, mode " + mode);
                }
                if (mode == 3)
                    check(record.readGarlicKeys().key.equals(decoded.readGarlicKeys().key)
                          && record.readGarlicKeys().rtag.equals(decoded.readGarlicKeys().rtag), "garlic keys preserved");
                String diagnostics = record.toString() + decoded.toString();
                check(Arrays.equals(before, record.getData()) && Arrays.equals(before, decoded.getData()),
                      "diagnostics preserve bytes, mode " + mode);
                check(!diagnostics.contains(decoded.readLayerKey().toBase64()), "layer key omitted, mode " + mode);
                check(!diagnostics.contains(decoded.readIVKey().toBase64()), "IV key omitted, mode " + mode);
                if (mode < 2) {
                    check(!diagnostics.contains(reply.toBase64()), "AES reply key omitted, mode " + mode);
                    check(!diagnostics.contains(Base64.encode(iv)), "reply IV omitted, mode " + mode);
                }
                if (mode != 0) {
                    check(!diagnostics.contains(decoded.getChaChaReplyKey().toBase64()), "ChaCha key omitted, mode " + mode);
                    check(!diagnostics.contains(Base64.encode(decoded.getChaChaReplyAD())), "reply AD omitted, mode " + mode);
                    check(!diagnostics.contains(OPTION), "option values omitted, mode " + mode);
                }
                if (mode == 3) {
                    check(!diagnostics.contains(decoded.readGarlicKeys().key.toBase64()), "garlic key omitted");
                    check(!diagnostics.contains(decoded.readGarlicKeys().rtag.toString()), "garlic tag omitted");
                }
                check(diagnostics.contains("BRR") && diagnostics.contains("reply msg id: 13"),
                      "stage and correlation diagnostics retained, mode " + mode);
                TunnelCreatorConfig config = config(ctx, 1, false, false);
                HopConfig hop = config.getConfig(0);
                hop.setLayerKey(decoded.readLayerKey());
                hop.setIVKey(decoded.readIVKey());
                if (mode == 3) config.setGarlicReplyKeys(decoded.readGarlicKeys());
                String full = config.toStringFull();
                check(!full.contains(decoded.readLayerKey().toBase64()) && !full.contains(decoded.readIVKey().toBase64()),
                      "hop/config layer keys omitted, mode " + mode);
                if (mode == 3)
                    check(!full.contains(decoded.readGarlicKeys().key.toBase64())
                          && !full.contains(decoded.readGarlicKeys().rtag.toString()), "config garlic keys omitted");
                if (mode != 0) {
                    byte[] corrupt = encrypted.getData().clone();
                    corrupt[corrupt.length - 1] ^= 1;
                    try {
                        new BuildRequestRecord(ctx, pair.getPrivate(), mode == 1 ? new EncryptedBuildRecord(corrupt)
                                : new net.i2p.data.i2np.ShortEncryptedBuildRecord(corrupt));
                        check(false, "tampering rejected, mode " + mode);
                    } catch (DataFormatException expected) {
                        check(expected.getMessage().equals("ChaCha decrypt fail"), "same failure type, mode " + mode);
                    }
                    String logs = capture.text();
                    check(logs.contains("ECIES BRR decrypt failure"), "actual decrypt diagnostic captured, mode " + mode);
                    check(!logs.contains(pair.getPrivate().toBase64()) && !logs.contains("state at failure")
                          && !logs.contains("Chaining Key") && !logs.contains(OPTION), "handshake state omitted, mode " + mode);
                    Field offset = BuildRequestRecord.class.getDeclaredField(mode == 1 ? "OFF_OPTIONS" : "OFF_OPTIONS_SHORT");
                    offset.setAccessible(true);
                    int start = offset.getInt(null);
                    decoded.getData()[start] = (byte) 255;
                    decoded.getData()[start + 1] = (byte) 255;
                    check(decoded.readOptions() == null, "malformed option fixture, mode " + mode);
                    check(decoded.toString().contains("BRR"), "malformed options do not break diagnostics, mode " + mode);
                }
            }
            replies(ctx, capture);
            System.out.println("TUNNEL_BUILD_PRIVACY_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("tunnel build diagnostic disclosure");
        } finally {
            ctx.comm.keys.shutdown();
            capture.shutdown();
            ctx.elGamalEngine().shutdown();
        }
    }

    private static void replies(QuietContext ctx, Capture capture) throws Exception {
        Class<?> type = Class.forName("net.i2p.router.tunnel.pool.BuildReplyHandler");
        Constructor<?> constructor = type.getDeclaredConstructor(I2PAppContext.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(ctx);
        Method decrypt = type.getDeclaredMethod("decrypt", TunnelBuildReplyMessage.class, TunnelCreatorConfig.class, List.class);
        decrypt.setAccessible(true);
        for (int mode = 0; mode < 3; mode++) {
            for (boolean inbound : new boolean[] { false, true }) {
                capture.clear();
                TunnelCreatorConfig cfg = config(ctx, 3, inbound, mode != 0);
                TunnelBuildReplyMessage reply = mode == 2
                        ? (inbound ? new ShortTunnelBuildReplyMessage(ctx, 3) : new OutboundTunnelBuildReplyMessage(ctx, 3))
                        : new VariableTunnelBuildReplyMessage(ctx, 3);
                int start = inbound ? 0 : 1;
                int end = inbound ? 1 : 2;
                int blank = inbound ? 2 : 0;
                EncryptedBuildRecord empty = mode == 2 ? new ShortEncryptedBuildRecord(new byte[218])
                                                      : new EncryptedBuildRecord(new byte[528]);
                reply.setRecord(blank, empty);
                if (inbound) cfg.setBlankHash(ctx.sha().calculateHash(empty.getData()));
                for (int hop = start; hop <= end; hop++) {
                    int status = hop == start ? 0 : 30;
                    Properties options = new Properties();
                    options.setProperty("review.marker", OPTION);
                    EncryptedBuildRecord record;
                    if (mode == 0)
                        record = BuildResponseRecord.create(ctx, status, cfg.getAESReplyKey(hop), cfg.getAESReplyIV(hop), 12345L);
                    else if (mode == 1)
                        record = BuildResponseRecord.create(ctx, status, cfg.getChaChaReplyKey(hop), cfg.getChaChaReplyAD(hop), options);
                    else
                        record = BuildResponseRecord.createShort(ctx, status, cfg.getChaChaReplyKey(hop), cfg.getChaChaReplyAD(hop), options, hop);
                    if (hop == start) {
                        byte[] data = record.getData();
                        if (mode == 2) {
                            byte[] iv = new byte[12];
                            iv[4] = (byte) hop;
                            ChaCha20.encrypt(cfg.getChaChaReplyKey(end).getData(), iv, data, 0, data, 0, data.length);
                        } else {
                            ctx.aes().encrypt(data, 0, data, 0, cfg.getAESReplyKey(end), cfg.getAESReplyIV(end), data.length);
                        }
                    }
                    reply.setRecord(hop, record);
                }
                reply.setUniqueId(12345);
                reply.setMessageExpiration(1700000000000L);
                TunnelBuildReplyMessage parsed;
                if (mode == 2 && inbound) {
                    // An inbound short reply arrives as an STBM; the reply view is internal.
                    ShortTunnelBuildMessage request = new ShortTunnelBuildMessage(ctx, 3);
                    for (int slot = 0; slot < 3; slot++) request.setRecord(slot, reply.getRecord(slot));
                    request.setUniqueId(12345);
                    request.setMessageExpiration(1700000000000L);
                    byte[] wire = request.toByteArray();
                    ShortTunnelBuildMessage received = (ShortTunnelBuildMessage) new I2NPMessageHandler(ctx).readMessage(wire);
                    check(Arrays.equals(wire, received.toByteArray()), "inbound short serialization round trip");
                    parsed = new ShortTunnelBuildReplyMessage(ctx, 3);
                    for (int slot = 0; slot < 3; slot++) parsed.setRecord(slot, received.getRecord(slot));
                    parsed.setUniqueId(12345);
                } else {
                    byte[] wire = reply.toByteArray();
                    parsed = (TunnelBuildReplyMessage) new I2NPMessageHandler(ctx).readMessage(wire);
                    check(Arrays.equals(wire, parsed.toByteArray()), "reply serialization round trip, mode " + mode + ", inbound " + inbound);
                }
                Object[] statuses = (Object[]) decrypt.invoke(handler, parsed, cfg, Arrays.asList(0, 1, 2));
                check(statuses != null, "layered replies decrypt, mode " + mode + ", inbound " + inbound);
                if (statuses != null) {
                    Field code = statuses[start].getClass().getField("code");
                    code.setAccessible(true);
                    check(code.getInt(statuses[start]) == 0 && code.getInt(statuses[end]) == 30,
                          "accept/reject statuses preserved, mode " + mode + ", inbound " + inbound);
                    if (mode != 0) {
                        Field props = statuses[start].getClass().getField("props");
                        props.setAccessible(true);
                        check(OPTION.equals(((Properties) props.get(statuses[start])).getProperty("review.marker")),
                              "reply options preserved, mode " + mode + ", inbound " + inbound);
                    }
                }
                String logs = capture.text();
                check(logs.contains("Decrypting"), "actual reply diagnostics captured, mode " + mode);
                for (int hop = start; hop <= end; hop++) {
                    check(!logs.contains(cfg.getAESReplyKey(hop).toBase64()) && !logs.contains(Base64.encode(cfg.getAESReplyIV(hop))),
                          "AES reply material omitted, mode " + mode + ", hop " + hop);
                    if (mode != 0)
                        check(!logs.contains(cfg.getChaChaReplyKey(hop).toBase64()) && !logs.contains(Base64.encode(cfg.getChaChaReplyAD(hop))),
                              "ChaCha reply material omitted, mode " + mode + ", hop " + hop);
                }
            }
        }
        capture.clear();
        TunnelCreatorConfig cfg = config(ctx, 2, false, false);
        TunnelBuildReplyMessage invalid = new VariableTunnelBuildReplyMessage(ctx, 2);
        invalid.setRecord(0, new EncryptedBuildRecord(new byte[528]));
        byte[] plaintext = bytes(528, 97);
        String privatePayload = Base64.encode(plaintext, 32, plaintext.length - 32);
        ctx.aes().encrypt(plaintext, 0, plaintext, 0, cfg.getAESReplyKey(1), cfg.getAESReplyIV(1), plaintext.length);
        invalid.setRecord(1, new EncryptedBuildRecord(plaintext));
        check(decrypt.invoke(handler, invalid, cfg, Arrays.asList(0, 1)) == null, "invalid legacy reply rejected");
        check(capture.text().contains("sha256 reply verify fail"), "legacy verification diagnostic captured");
        check(!capture.text().contains(privatePayload), "invalid decrypted reply omitted from warning");
    }

    private static TunnelCreatorConfig config(RouterContext ctx, int length, boolean inbound, boolean ec) {
        TunnelCreatorConfig result = new TunnelCreatorConfig(ctx, length, inbound) { };
        for (int hop = 0; hop < length; hop++) {
            result.setPeer(hop, new Hash(bytes(32, hop + 1)));
            result.setAESReplyKeys(hop, new SessionKey(bytes(32, hop + 31)), bytes(16, hop + 41));
            if (ec) result.setChaChaReplyKeys(hop, new SessionKey(bytes(32, hop + 51)), bytes(32, hop + 61));
        }
        return result;
    }

    private static byte[] bytes(int size, int value) { byte[] result = new byte[size]; Arrays.fill(result, (byte) value); return result; }
    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + name); }
    }

    private static final class QuietContext extends RouterContext {
        CryptoComm comm;
        QuietContext() { super(null, new Properties()); }
        @Override protected void initializeClock() { _clock = new RouterClock(this); _clockInitialized = true; }
        @Override public boolean isRouterContext() { return false; }
        @Override public CommSystemFacade commSystem() { return comm; }
    }
    private static final class CryptoComm extends CommSystemFacade {
        final X25519KeyFactory keys;
        CryptoComm(RouterContext ctx) { keys = new X25519KeyFactory(ctx); }
        @Override public X25519KeyFactory getXDHFactory() { return keys; }
        public void processMessage(OutNetMessage message) { throw new AssertionError("network access"); }
        public int countActivePeers() { return 1; }
        public int countActiveSendPeers() { return 1; }
        public boolean isEstablished(Hash peer) { return true; }
        public List<Hash> getEstablished() { return Collections.emptyList(); }
        public void startup() { throw new AssertionError("network startup"); }
        public void restart() { throw new AssertionError("network restart"); }
        public void shutdown() { }
    }
}
