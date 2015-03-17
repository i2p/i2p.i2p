package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;

/**
 *  All the peers for a single torrent
 *
 * @since 0.8.4
 * @author zzz
 */
class Peers extends ConcurrentHashMap<Hash, Peer> {

    public Peers() {
        super();
    }
}
