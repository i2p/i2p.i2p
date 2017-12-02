package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import static org.junit.Assert.*;

import org.junit.Test;
 
public class Base64Test {
    @Test
    public void testBase64(){
        String orig = "you smell";
        String encoded = Base64.encode(DataHelper.getASCII(orig));
        byte decoded[] = Base64.decode(encoded);
        String transformed = new String(decoded);
        assertTrue(orig.equals(transformed));

        byte all[] = new byte[256];
        for (int i = 0; i < all.length; i++)
            all[i] = (byte) (0xFF & i);
        encoded = Base64.encode(all);
        decoded = Base64.decode(encoded);
        assertTrue(DataHelper.eq(decoded, all));
    }
}
