package net.i2p.data.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

import net.i2p.data.Certificate;
import net.i2p.data.CertificateTest;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.PublicKey;
import net.i2p.data.PublicKeyTest;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SigningPublicKeyTest;
import net.i2p.data.StructureTest;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterIdentityTest extends StructureTest {

    public DataStructure createDataStructure() throws DataFormatException {
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);
        return ident;
    }
    public DataStructure createStructureToRead() { return new RouterIdentity(); }

    @Test
    public void testNullCert() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(null);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);

        try {
            ident.writeBytes(new ByteArrayOutputStream());
            fail("no exception thrown");
        } catch (DataFormatException expected) {
            assertEquals("Not enough data to format the router identity", expected.getMessage());
        }
    }

    @Test
    public void testNullPublicKey() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        ident.setPublicKey(null);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);

        try {
            ident.writeBytes(new ByteArrayOutputStream());
            fail("no exception thrown");
        } catch (DataFormatException expected) {
            assertEquals("Not enough data to format the router identity", expected.getMessage());
        }
    }

    @Test
    public void testNullSigningKey() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        ident.setSigningPublicKey(null);

        try {
            ident.writeBytes(new ByteArrayOutputStream());
            fail("no exception thrown");
        } catch (DataFormatException expected) {
            assertEquals("Not enough data to format the router identity", expected.getMessage());
        }
    }

    @Test
    public void testNullEquals() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        assertFalse(ident.equals(null));
    }

    @Test
    public void testCalculatedHash() throws Exception{
        RouterIdentity ident = new RouterIdentity();
        Certificate cert = (Certificate)(new CertificateTest()).createDataStructure();
        ident.setCertificate(cert);
        PublicKey pk = (PublicKey)(new PublicKeyTest()).createDataStructure();
        ident.setPublicKey(pk);
        SigningPublicKey k = (SigningPublicKey)(new SigningPublicKeyTest()).createDataStructure();
        ident.setSigningPublicKey(k);

        ident.calculateHash();
        ident.calculateHash();
        ident.calculateHash();
        ident.calculateHash();
        ident.calculateHash();
    }

    @Test
    public void testBadHash() throws Exception {
        RouterIdentity ident = new RouterIdentity();

        try {
            ident.getHash();
            fail("no exception thrown");
        } catch (IllegalStateException expected) {
            assertEquals("KAC hash error", expected.getMessage());
        }
    }
}
