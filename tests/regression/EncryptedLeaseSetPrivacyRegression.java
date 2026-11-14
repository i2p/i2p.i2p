package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.Lease2;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.TunnelId;

/** Captures real diagnostic calls while exercising generated, disposable keys. */
public final class EncryptedLeaseSetPrivacyRegression {
    private static final String SECRET = "ELS-SECRET-PLACEHOLDER";
    private static final String NEW_SECRET = "ELS-NEW-SECRET-PLACEHOLDER";
    private static final String INNER_MARKER = "ELS-PRIVATE-PAYLOAD-PLACEHOLDER";
    private static int checks;
    private static int failures;

    private static final class Capture extends LogManager {
        private final StringBuilder messages = new StringBuilder();

        Capture(I2PAppContext ctx) {
            super(ctx);
        }

        @Override
        synchronized void addRecord(LogRecord record) {
            // The superclass constructor may log before this field is initialized.
            if (messages != null && record.getSource() == EncryptedLeaseSet.class) {
                messages.append(record.getMessage()).append('\n');
                if (record.getThrowable() != null)
                    messages.append(record.getThrowable().toString()).append('\n');
            }
        }

        synchronized String text() {
            return messages.toString();
        }

        synchronized void clear() {
            messages.setLength(0);
        }
    }

    public static void main(String[] args) throws Exception {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        LogManager previous = ctx.logManager();
        Capture capture = new Capture(ctx);
        ctx.setLogManager(capture);
        try {
            capture.setDefaultLimit("DEBUG");
            SimpleDataStructure[] signing = KeyGenerator.getInstance().generateSigningKeys(SigType.EdDSA_SHA512_Ed25519);
            SigningPublicKey pub = (SigningPublicKey) signing[0];
            SigningPrivateKey priv = (SigningPrivateKey) signing[1];
            KeyPair encryption = KeyGenerator.getInstance().generatePKIKeys(EncType.ECIES_X25519);
            KeyPair client = KeyGenerator.getInstance().generatePKIKeys(EncType.ECIES_X25519);
            Destination dest = new Destination();
            dest.setPublicKey(encryption.getPublic());
            dest.setSigningPublicKey(pub);
            dest.setCertificate(KeyCertificate.X25519_Ed25519_CERT);
            dest.setPadding(new byte[320]);

            for (int auth : new int[] {BlindData.AUTH_NONE, BlindData.AUTH_DH, BlindData.AUTH_PSK}) {
                capture.clear();
                EncryptedLeaseSet original = create(dest, encryption);
                SimpleDataStructure clientKey = auth == BlindData.AUTH_DH ? client.getPublic() : client.getPrivate();
                original.sign(priv, auth, auth == BlindData.AUTH_NONE ? null : Collections.singletonList(clientKey));
                SigningPrivateKey alpha = Blinding.generateAlpha(ctx, pub, SECRET, original.getPublished());
                SigningPrivateKey blinded = Blinding.blind(priv, alpha);

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                original.writeBytes(output);
                EncryptedLeaseSet reader = new EncryptedLeaseSet();
                reader.readBytes(new ByteArrayInputStream(output.toByteArray()));
                reader.setSecret(SECRET);
                reader.setDestination(dest);
                PrivateKey clientPrivate = auth == BlindData.AUTH_NONE ? null : client.getPrivate();
                if (clientPrivate != null)
                    reader.setClientPrivateKey(clientPrivate);
                check(reader.verifySignature(), "signature/decryption round trip, auth " + auth);
                check(reader.getLeaseCount() == 1, "lease count preserved, auth " + auth);
                check(INNER_MARKER.equals(reader.getDecryptedLeaseSet().getOption("review.marker")),
                      "inner options preserved, auth " + auth);
                ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
                reader.writeBytes(rewritten);
                check(java.util.Arrays.equals(output.toByteArray(), rewritten.toByteArray()),
                      "serialized data unchanged by diagnostics/decryption, auth " + auth);

                String logs = capture.text();
                check(!logs.isEmpty(), "real DEBUG logging enabled, auth " + auth);
                check(!logs.contains(priv.toBase64()), "signing private key absent from DEBUG, auth " + auth);
                check(!logs.contains(blinded.toBase64()), "blinded private key absent from DEBUG, auth " + auth);
                check(!logs.contains(SECRET), "blinding secret absent from DEBUG, auth " + auth);
                check(!logs.contains(INNER_MARKER), "inner payload absent from DEBUG, auth " + auth);
                String diagnostic = reader.toString();
                check(!diagnostic.contains(SECRET), "blinding secret absent from toString, auth " + auth);
                check(!diagnostic.contains(INNER_MARKER), "inner payload absent from toString, auth " + auth);
                if (clientPrivate != null)
                    check(!diagnostic.contains(clientPrivate.toBase64()), "client private key absent from toString, auth " + auth);
                check(diagnostic.contains("Published:") && diagnostic.contains("Auth Type: " + auth),
                      "useful status remains in toString, auth " + auth);

                capture.clear();
                capture.setDefaultLimit("WARN");
                reader.setSecret(NEW_SECRET);
                String warnings = capture.text();
                check(warnings.contains("setSecret() after setSigningKey()"), "late-secret warning retained, auth " + auth);
                check(!warnings.contains(SECRET) && !warnings.contains(NEW_SECRET),
                      "old/new secrets absent from WARN, auth " + auth);
                capture.setDefaultLimit("DEBUG");
            }

            // Invalid caller-supplied key types must not be copied into exceptions.
            for (int auth : new int[] {BlindData.AUTH_DH, BlindData.AUTH_PSK}) {
                EncryptedLeaseSet invalid = create(dest, encryption);
                try {
                    invalid.sign(priv, auth, Collections.singletonList(priv));
                    check(false, "invalid client key rejected, auth " + auth);
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("client key type"), "invalid-key diagnostic retained, auth " + auth);
                    check(!expected.getMessage().contains(priv.toBase64()), "private key absent from exception, auth " + auth);
                }
            }
            System.out.println("ELS_PRIVACY_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0)
                throw new AssertionError("encrypted lease set diagnostic privacy checks failed");
        } finally {
            ctx.setLogManager(previous);
            capture.shutdown();
        }
    }

    private static EncryptedLeaseSet create(Destination dest, KeyPair encryption) {
        EncryptedLeaseSet result = new EncryptedLeaseSet();
        result.setSecret(SECRET);
        result.setPublished(System.currentTimeMillis());
        result.setDestination(dest);
        result.addEncryptionKey(encryption.getPublic());
        Lease2 lease = new Lease2();
        lease.setGateway(new Hash(new byte[32]));
        lease.setTunnelId(new TunnelId(1));
        lease.setEndDate(new Date(System.currentTimeMillis() + 60000));
        result.addLease(lease);
        Properties options = new Properties();
        options.setProperty("review.marker", INNER_MARKER);
        result.setOptions(options);
        return result;
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
