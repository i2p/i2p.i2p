package i2p.bote.network.kademlia;

import java.math.BigInteger;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

public class KademliaUtil {

    public static BigInteger getDistance(KademliaPeer node, Hash key) {
        return getDistance(node.getDestinationHash(), key);
    }

    public static BigInteger getDistance(Hash key1, Hash key2) {
        // This shouldn't be a performance bottleneck, so save some mem by not using Hash.cachedXor
        byte[] xoredData = DataHelper.xor(key1.getData(), key2.getData());
        return new BigInteger(xoredData);
    }
}