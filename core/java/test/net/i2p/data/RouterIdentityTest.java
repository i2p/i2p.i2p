package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SigningPublicKey;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
class RouterIdentityTest extends StructureTest {
    static {
        TestData.registerTest(new RouterIdentityTest(), "RouterIdentity");
    }
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
}
