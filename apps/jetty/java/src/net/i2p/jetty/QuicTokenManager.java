package net.i2p.jetty;

import java.net.InetSocketAddress;
import java.net.Inet6Address;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless, address-bound QUIC Retry tokens authenticated with HMAC-SHA256. */
public final class QuicTokenManager {
    private static final byte VERSION = 1;
    private static final int TAG_BYTES = 16; // 128-bit authentication, within Quiche's 48-byte token buffer.
    private static final int PREFIX_BYTES = 10;
    private static final long LIFETIME_MILLIS = 60000;
    private static final byte[] DOMAIN = "I2P QUIC Retry v1".getBytes(StandardCharsets.US_ASCII);
    public static final int MAX_TOKEN_BYTES = PREFIX_BYTES + 20 + TAG_BYTES;
    private final LongSupplier clock;
    private final ThreadLocal<Mac> authenticators;

    public QuicTokenManager() { this(randomKey(), System::currentTimeMillis); }

    QuicTokenManager(byte[] key, LongSupplier clock) {
        if (key.length < 32) throw new IllegalArgumentException("A 256-bit token key is required");
        this.clock = clock;
        SecretKeySpec secret = new SecretKeySpec(key, "HmacSHA256");
        authenticators = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(secret);
                return mac;
            } catch (GeneralSecurityException unavailable) {
                throw new IllegalStateException("HMAC-SHA256 is required for QUIC Retry", unavailable);
            }
        });
        authenticators.get(); // Fail during setup, before accepting datagrams.
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public byte[] mint(InetSocketAddress remote, byte[] originalConnectionId, int length) {
        if (!validAddress(remote) || originalConnectionId == null || length <= 0 || length > 20 || length > originalConnectionId.length)
            throw new IllegalArgumentException("Invalid QUIC address or connection ID");
        byte[] token = new byte[PREFIX_BYTES + length + TAG_BYTES];
        ByteBuffer.wrap(token).put(VERSION).putLong(clock.getAsLong() + LIFETIME_MILLIS)
                .put((byte)length).put(originalConnectionId, 0, length);
        byte[] tag = authenticate(remote, token, PREFIX_BYTES + length);
        System.arraycopy(tag, 0, token, PREFIX_BYTES + length, TAG_BYTES);
        return token;
    }

    /** Invalid, modified, cross-address and expired tokens have the same result. */
    public byte[] validate(InetSocketAddress remote, byte[] token, int length) {
        if (!validAddress(remote) || token == null || length < PREFIX_BYTES + 1 + TAG_BYTES ||
            length > MAX_TOKEN_BYTES || length > token.length || token[0] != VERSION)
            return null;
        int connectionIdLength = token[PREFIX_BYTES - 1] & 0xff;
        if (connectionIdLength <= 0 || connectionIdLength > 20 || length != PREFIX_BYTES + connectionIdLength + TAG_BYTES)
            return null;
        long expires = ByteBuffer.wrap(token, 1, Long.BYTES).getLong();
        long now = clock.getAsLong();
        if (expires < now || expires - now > LIFETIME_MILLIS)
            return null;
        int bodyLength = length - TAG_BYTES;
        byte[] expected = Arrays.copyOf(authenticate(remote, token, bodyLength), TAG_BYTES);
        byte[] actual = Arrays.copyOfRange(token, bodyLength, length);
        if (!MessageDigest.isEqual(expected, actual))
            return null;
        return Arrays.copyOfRange(token, PREFIX_BYTES, bodyLength);
    }

    private static boolean validAddress(InetSocketAddress remote) {
        return remote != null && remote.getAddress() != null && remote.getPort() > 0;
    }

    private byte[] authenticate(InetSocketAddress remote, byte[] body, int length) {
        Mac mac = authenticators.get();
        mac.reset();
        byte[] address = remote.getAddress().getAddress();
        mac.update(DOMAIN);
        mac.update((byte)address.length);
        mac.update(address);
        int scope = remote.getAddress() instanceof Inet6Address ipv6 ? ipv6.getScopeId() : 0;
        mac.update(ByteBuffer.allocate(4).putInt(scope).array());
        mac.update((byte)(remote.getPort() >>> 8));
        mac.update((byte)remote.getPort());
        mac.update(body, 0, length);
        return mac.doFinal();
    }
}
