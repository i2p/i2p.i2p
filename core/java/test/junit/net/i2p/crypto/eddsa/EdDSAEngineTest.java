/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

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

import org.junit.Test;

/**
 * @author str4d
 *
 */
public class EdDSAEngineTest {
    static final byte[] TEST_SEED = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    static final byte[] TEST_PK = Utils.hexToBytes("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29");
    static final byte[] TEST_MSG = "This is a secret message".getBytes(Charset.forName("UTF-8"));
    static final byte[] TEST_MSG_SIG = Utils.hexToBytes("94825896c7075c31bcb81f06dba2bdcd9dcf16e79288d4b9f87c248215c8468d475f429f3de3b4a2cf67fe17077ae19686020364d6d4fa7a0174bab4a123ba0f");

    @Test
    public void testSign() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));

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
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
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
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        //Signature sgr = Signature.getInstance("EdDSA", "I2P");
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK, spec);
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);

        sgr.update(TEST_MSG);

        try {
            sgr.verify(new byte[]{0});
        } catch (SignatureException expected) {
            assertEquals("signature length is wrong", expected.getMessage());
        }
    }

    @Test
    public void testSignResetsForReuse() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
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
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK, spec);
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);

        // First usage
        sgr.update(new byte[] {0});
        sgr.verify(TEST_MSG_SIG);

        // Second usage
        sgr.update(TEST_MSG);
        assertThat("Second verify failed", sgr.verify(TEST_MSG_SIG), is(true));
    }

    @Test
    public void testSignOneShotMode() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(TEST_SEED, spec);
        PrivateKey sKey = new EdDSAPrivateKey(privKey);
        sgr.initSign(sKey);
        sgr.setParameter(EdDSAEngine.ONE_SHOT_MODE);

        sgr.update(TEST_MSG);

        assertThat("One-shot mode sign failed", sgr.sign(), is(equalTo(TEST_MSG_SIG)));
    }

    @Test
    public void testVerifyOneShotMode() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK, spec);
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);
        sgr.setParameter(EdDSAEngine.ONE_SHOT_MODE);

        sgr.update(TEST_MSG);

        assertThat("One-shot mode verify failed", sgr.verify(TEST_MSG_SIG), is(true));
    }

    @Test
    public void testSignOneShotModeMultipleUpdates() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(TEST_SEED, spec);
        PrivateKey sKey = new EdDSAPrivateKey(privKey);
        sgr.initSign(sKey);
        sgr.setParameter(EdDSAEngine.ONE_SHOT_MODE);

        sgr.update(TEST_MSG);

        try {
            sgr.update(TEST_MSG);
            fail("exception not thrown");
        }  catch (SignatureException expected) {
            assertEquals("update() already called", expected.getMessage());
        }
    }

    @Test
    public void testVerifyOneShotModeMultipleUpdates() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK, spec);
        Signature sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);
        sgr.setParameter(EdDSAEngine.ONE_SHOT_MODE);

        sgr.update(TEST_MSG);

        try {
            sgr.update(TEST_MSG);
            fail("exception not thrown");
        } catch (SignatureException expected) {
            assertEquals("update() already called", expected.getMessage());
        }
    }

    @Test
    public void testSignOneShot() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(TEST_SEED, spec);
        EdDSAEngine sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        PrivateKey sKey = new EdDSAPrivateKey(privKey);
        sgr.initSign(sKey);

        assertThat("signOneShot() failed", sgr.signOneShot(TEST_MSG), is(equalTo(TEST_MSG_SIG)));
    }

    @Test
    public void testVerifyOneShot() throws Exception {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(TEST_PK, spec);
        EdDSAEngine sgr = new EdDSAEngine(MessageDigest.getInstance(spec.getHashAlgorithm()));
        PublicKey vKey = new EdDSAPublicKey(pubKey);
        sgr.initVerify(vKey);

        assertThat("verifyOneShot() failed", sgr.verifyOneShot(TEST_MSG, TEST_MSG_SIG), is(true));
    }
}
