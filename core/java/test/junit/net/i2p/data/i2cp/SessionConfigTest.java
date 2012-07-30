package net.i2p.data.i2cp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.data.StructureTest;
import net.i2p.data.DataStructure;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.DestinationTest;
import net.i2p.data.Signature;
import net.i2p.data.SignatureTest;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPrivateKeyTest;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class SessionConfigTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        SessionConfig cfg = new SessionConfig((Destination)(new DestinationTest()).createDataStructure());
        cfg.setSignature((Signature)(new SignatureTest()).createDataStructure());
        Properties options = new Properties();
        options.setProperty("routerHost", "localhost");
        options.setProperty("routerPort", "54321");
        options.setProperty("routerSecret", "blah");
        cfg.setOptions(options);
        cfg.signSessionConfig((SigningPrivateKey)(new SigningPrivateKeyTest()).createDataStructure());
        return cfg; 
    }
    public DataStructure createStructureToRead() { return new SessionConfig(); }
}
