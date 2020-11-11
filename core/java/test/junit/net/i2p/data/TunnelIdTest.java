package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


/**
 * Test harness for loading / storing TunnelId objects
 *
 * @author jrandom
 */
public class TunnelIdTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        TunnelIdStructure id = new TunnelIdStructure();
        id.setTunnelId(42);
        return id; 
    }
    public DataStructure createStructureToRead() { return new TunnelIdStructure(); }

    /**
     * so we can test it as a structure
     * @since 0.9.48 TunnelId no longer extends DataStructureImpl
     */
    private static class TunnelIdStructure extends TunnelId implements DataStructure {
        public Hash calculateHash() { return null; }
        public void fromByteArray(byte[] in) {}
        public byte[] toByteArray() { return null; }
        public void fromBase64(String in) {}
        public String toBase64() { return null; }
    }
}
