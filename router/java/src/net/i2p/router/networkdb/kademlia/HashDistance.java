package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 *  Moved from PeerSelector
 *  @since 0.7.14
 */
public class HashDistance {
    
    public static BigInteger getDistance(Hash targetKey, Hash routerInQuestion) {
        // plain XOR of the key and router
        byte diff[] = DataHelper.xor(routerInQuestion.getData(), targetKey.getData());
        return new BigInteger(1, diff);
    }
}
