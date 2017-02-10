package org.klomp.snark.dht;

/*
 *  GPLv2
 */

import java.util.Collection;

import net.i2p.data.Destination;
import net.i2p.data.Hash;


/**
 * Stub for KRPC
 * @since 0.8.4
 */
public interface DHT {


    /**
     *  @return The UDP query port
     */
    public int getPort();

    /**
     *  @return The UDP response port
     */
    public int getRPort();

    /**
     *  Ping. We don't have a NID yet so the node is presumed
     *  to be absent from our DHT.
     *  Non-blocking, does not wait for pong.
     *  If and when the pong is received the node will be inserted in our DHT.
     */
    public void ping(Destination dest, int port);

    /**
     *  Get peers for a torrent, and announce to the closest annMax nodes we find.
     *  Blocking!
     *  Caller should run in a thread.
     *
     *  @param ih the Info Hash (torrent)
     *  @param max maximum number of peers to return
     *  @param maxWait the maximum time to wait (ms) must be &gt; 0
     *  @param annMax the number of peers to announce to
     *  @param annMaxWait the maximum total time to wait for announces, may be 0 to return immediately without waiting for acks
     *  @param isSeed true if seed, false if leech
     *  @param noSeeds true if we do not want seeds in the result
     *  @return possibly empty (never null)
     */
    public Collection<Hash> getPeersAndAnnounce(byte[] ih, int max, long maxWait,
                                                int annMax, long annMaxWait,
                                                boolean isSeed, boolean noSeeds);

    /**
     *  Announce to ourselves.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     */
    public void announce(byte[] ih, boolean isSeed);

    /**
     *  Announce somebody else we know about to ourselves.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     *  @param peerHash the peer's Hash
     */
    public void announce(byte[] ih, byte[] peerHash, boolean isSeed);

    /**
     *  Remove reference to ourselves in the local tracker.
     *  Use when shutting down the torrent locally.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     */
    public void unannounce(byte[] ih);

    /**
     *  Announce to the closest DHT peers.
     *  Blocking unless maxWait &lt;= 0
     *  Caller should run in a thread.
     *  This also automatically announces ourself to our local tracker.
     *  For best results do a getPeers() first so we have tokens.
     *
     *  @param ih the Info Hash (torrent)
     *  @param maxWait the maximum total time to wait (ms) or 0 to do all in parallel and return immediately.
     *  @param isSeed true if seed, false if leech
     *  @return the number of successful announces, not counting ourselves.
     */
    public int announce(byte[] ih, int max, long maxWait, boolean isSeed);

    /**
     * Stop everything.
     */
    public void stop();

    /**
     * Known nodes, not estimated total network size.
     */
    public int size();

    /**
     * Debug info, HTML formatted
     */
    public String renderStatusHTML();
}
