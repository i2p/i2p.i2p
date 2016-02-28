package net.i2p.crypto.eddsa;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * @author str4d
 *
 */
public class EdDSAEngineTest {
    static final byte[] TEST_SEED = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    static final byte[] TEST_PK = Utils.hexToBytes("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29");
    static final byte[] TEST_MSG = "This is a secret message".getBytes(Charset.forName("UTF-8"));
    static final byte[] TEST_MSG_SIG = Utils.hexToBytes("94825896c7075c31bcb81f06dba2bdcd9dcf16e79288d4b9f87c248215c8468d475f429f3de3b4a2cf67fe17077ae19686020364d6d4fa7a0174bab4a123ba0f");

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void testSign() throws Exception {
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName("ed25519-sha-512");

        for (Ed25519TestVectors.TestTuple testCase : Ed25519TestVectors.testCases) {
            EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(testCase.seed, spec);
            PrivateKey sKey = new EdDSAPrivateKey(privKey);
            sgr.initSign(sKey);

            sgr.update(testCase.message);

            assertThat("Test case " + testCase.caseNum + " failed",
                    sgr.sign(), is(equalTo(testCase.sig)));
        }
    }

    @Test
    public void testVerify() throws Exception {
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName("ed25519-sha-512");

        for (Ed25519TestVectors.TestTuple testCase : Ed25519TestVectors.testCases) {
            EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(testCase.pk, spec);
            PublicKey vKey = new EdDSAPublicKey(pubKey);
            sgr.initVerify(vKey);

            sgr.update(testCase.message);

            assertThat("Test case " + testCase.caseNum + " failed",
                    sgr.verify(testCase.sig), is(true));
        }
    }

    /**
     * Checks that a wrong-length signature throws an IAE.
     */
    @Test
    public void testVerifyWrongSigLength() throws Exception {
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));

        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK,
                EdDSANamedCurveTable.getByName("ed25519-sha-512"));
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);

        sgr.update(TEST_MSG);

        exception.expect(SignatureException.class);
        exception.expectMessage("signature length is wrong");
        sgr.verify(new byte[] {0});
    }

    @Test
    public void testSignResetsForReuse() throws Exception {
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName("ed25519-sha-512");

        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(TEST_SEED, spec);
        PrivateKey sKey = new EdDSAPrivateKey(privKey);
        sgr.initSign(sKey);

        // First usage
        sgr.update(new byte[] {0});
        sgr.sign();

        // Second usage
        sgr.update(TEST_MSG);
        assertThat("Second sign failed", sgr.sign(), is(equalTo(TEST_MSG_SIG)));
    }

    @Test
    public void testVerifyResetsForReuse() throws Exception {
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));

        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK,
                EdDSANamedCurveTable.getByName("ed25519-sha-512"));
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);

        // First usage
        sgr.update(new byte[] {0});
        sgr.verify(TEST_MSG_SIG);

        // Second usage
        sgr.update(TEST_MSG);
        assertThat("Second verify failed", sgr.verify(TEST_MSG_SIG), is(true));
    }
}
