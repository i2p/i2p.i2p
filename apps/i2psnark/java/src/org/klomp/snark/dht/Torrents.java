package org.klomp.snark.dht;
/*
 *  From zzzot, relicensed to GPLv2
 */

import java.util.concurrent.ConcurrentHashMap;

/**
 *  All the torrents
 *
 * @since 0.8.4
 * @author zzz
 */
class Torrents extends ConcurrentHashMap<InfoHash, Peers> {

    public Torrents() {
        super();
    }
}
