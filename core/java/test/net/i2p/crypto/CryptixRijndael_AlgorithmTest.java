package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


import junit.framework.TestCase;
import net.i2p.data.DataHelper;

public class CryptixRijndael_AlgorithmTest extends TestCase {
    public void testCRA() throws Exception{
        int[] sizes = {16,24,32};
        for(int j = 0; j < sizes.length; j++){
            
            byte[] kb = new byte[sizes[j]];
            byte[] pt = new byte[16];
            int i;

            for (i = 0; i < sizes[j]; i++)
                kb[i] = (byte) i;
            for (i = 0; i < 16; i++)
                pt[i] = (byte) i;

            
            Object key = CryptixRijndael_Algorithm.makeKey(kb, 16);
            
            byte[] ct = new byte[16];
            CryptixRijndael_Algorithm.blockEncrypt(pt, ct, 0, 0, key, 16);

            byte[] cpt = new byte[16];
            CryptixRijndael_Algorithm.blockDecrypt(ct, cpt, 0, 0, key, 16);

            assertTrue(DataHelper.eq(pt, cpt));
        }
    }
}