package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.util.Comparator;

import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;

/**
 *  Closest to a InfoHash or NID key.
 *  Use for NodeInfos.
 *
 * @since 0.8.4
 * @author zzz
 */
class NodeInfoComparator implements Comparator<NodeInfo> {
    private final SHA1Hash _base;

    public NodeInfoComparator(SHA1Hash h) {
        _base = h;
    }

    public int compare(NodeInfo lhs, NodeInfo rhs) {
        byte lhsDelta[] = DataHelper.xor(lhs.getNID().getData(), _base.getData());
        byte rhsDelta[] = DataHelper.xor(rhs.getNID().getData(), _base.getData());
        return DataHelper.compareTo(lhsDelta, rhsDelta);
    }

}
