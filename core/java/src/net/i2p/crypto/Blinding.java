package net.i2p.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.zip.CRC32;

import net.i2p.I2PAppContext;
import net.i2p.crypto.eddsa.EdDSABlinding;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.data.Base32;
import net.i2p.data.BlindData;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

public final class Blinding {

    private static final SigType TYPE = SigType.EdDSA_SHA512_Ed25519;
    private static final SigType TYPER = SigType.RedDSA_SHA512_Ed25519;
    private static final String INFO = "i2pblinding1";
    private static final byte[] INFO_ALPHA = "I2PGenerateAlpha".getBytes(StandardCharsets.US_ASCII);

    private static final byte FLAG_TWOBYTE = 0x01;
    private static final byte FLAG_SECRET = 0x02;
    private static final byte FLAG_AUTH = 0x04;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneOffset.UTC);
    private static final int FORMAT_LENGTH = 8;

    private Blinding() {}

    public static SigningPublicKey blind(SigningPublicKey key, SigningPrivateKey alpha) {
        if (!isValidKeyType(key.getType(), alpha.getType())) {
            throw new IllegalArgumentException("Unsupported blinding operation for provided key types");
        }
        try {
            EdDSAPublicKey publicKey = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey privateKey = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPublicKey blindedKey = EdDSABlinding.blind(publicKey, privateKey);
            return SigUtil.fromJavaKey(blindedKey, TYPER);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Blinding failed due to security exception", e);
        }
    }

    public static SigningPrivateKey blind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (!isValidKeyType(key.getType(), alpha.getType())) {
            throw new IllegalArgumentException("Unsupported blinding operation for provided key types");
        }
        try {
            EdDSAPrivateKey privateKey = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey alphaKey = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey blindedKey = EdDSABlinding.blind(privateKey, alphaKey);
            return SigUtil.fromJavaKey(blindedKey, TYPER);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Blinding failed due to security exception", e);
        }
    }

    public static SigningPrivateKey unblind(SigningPrivateKey key, SigningPrivateKey alpha) {
        if (key.getType() != TYPER || alpha.getType() != TYPER) {
            throw new IllegalArgumentException("Unsupported unblinding operation for provided key types");
        }
        try {
            EdDSAPrivateKey blindedKey = SigUtil.toJavaEdDSAKey(key);
            EdDSAPrivateKey alphaKey = SigUtil.toJavaEdDSAKey(alpha);
            EdDSAPrivateKey originalKey = EdDSABlinding.unblind(blindedKey, alphaKey);
            return SigUtil.fromJavaKey(originalKey, TYPE);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Unblinding failed due to security exception", e);
        }
    }

    public static SigningPrivateKey generateAlpha(I2PAppContext ctx, SigningPublicKey destspk, String secret) {
        long now = ctx.clock().now();
        return generateAlpha(ctx, destspk, secret, now);
    }

    public static SigningPrivateKey generateAlpha(I2PAppContext ctx, SigningPublicKey destspk, String secret, long now) {
        if (!isValidKeyType(destspk.getType(), TYPER)) {
            throw new IllegalArgumentException("Unsupported operation for provided key type");
        }

        String formattedDate = FORMATTER.format(Instant.ofEpochMilli(now));
        byte[] dateBytes = formattedDate.getBytes(StandardCharsets.US_ASCII);
        byte[] data = mergeDateAndSecret(dateBytes, secret);
        byte[] in = prepareHKDFInput(destspk, dateBytes);

        HKDF hkdf = new HKDF(ctx);
        byte[] salt = generateSalt(ctx, in);
        byte[] out = new byte[64];
        hkdf.calculate(salt, data, INFO, out, out, 32);
        byte[] reducedKey = EdDSABlinding.reduce(out);

        return new SigningPrivateKey(TYPER, reducedKey);
    }

    private static byte[] mergeDateAndSecret(byte[] dateBytes, String secret) {
        if (secret == null || secret.isEmpty()) return dateBytes;

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[dateBytes.length + secretBytes.length];
        System.arraycopy(dateBytes, 0, data, 0, dateBytes.length);
        System.arraycopy(secretBytes, 0, data, dateBytes.length, secretBytes.length);

        return data;
    }

    private static byte[] prepareHKDFInput(SigningPublicKey destspk, byte[] dateBytes) {
        int inputLength = INFO_ALPHA.length + destspk.length() + 4;
        byte[] input = new byte[inputLength];
        System.arraycopy(INFO_ALPHA, 0, input, 0, INFO_ALPHA.length);
        System.arraycopy(destspk.getData(), 0, input, INFO_ALPHA.length, destspk.length());
        DataHelper.toLong(input, INFO_ALPHA.length + destspk.length(), 2, TYPE.getCode());
        DataHelper.toLong(input, INFO_ALPHA.length + destspk.length() + 2, 2, TYPER.getCode());
        return input;
    }

    private static byte[] generateSalt(I2PAppContext ctx, byte[] in) {
        Hash hash = ctx.sha().calculateHash(in);
        return hash.getData();
    }

    private static boolean isValidKeyType(SigType keyType, SigType expectedType) {
        return keyType == TYPE || keyType == TYPER || keyType == expectedType;
    }

    public static BlindData decode(I2PAppContext ctx, String address) {
        address = address.toLowerCase(Locale.US);
        if (!address.endsWith(".b32.i2p")) throw new IllegalArgumentException("Invalid I2P address format");
        byte[] decoded = Base32.decode(address.substring(0, address.length() - 8));
        if (decoded == null || decoded.length < 35) throw new IllegalArgumentException("Invalid base32 I2P address");
        return decode(ctx, decoded);
    }

    // Additional improvements follow here...

}
