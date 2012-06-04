package org.klomp.snark.dht;

/*
 *  GPLv2
 */

import java.util.List;

import net.i2p.data.Destination;
import net.i2p.data.Hash;


/**
 * Stub for KRPC
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
     *  Get peers for a torrent.
     *  Blocking!
     *  Caller should run in a thread.
     *
     *  @param ih the Info Hash (torrent)
     *  @param max maximum number of peers to return
     *  @param maxWait the maximum time to wait (ms) must be > 0
     *  @return list or empty list (never null)
     */
    public List<Hash> getPeers(byte[] ih, int max, long maxWait);

    /**
     *  Announce to ourselves.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     */
    public void announce(byte[] ih);

    /**
     *  Announce somebody else we know about.
     *  Non-blocking.
     *
     *  @param ih the Info Hash (torrent)
     *  @param peerHash the peer's Hash
     */
    public void announce(byte[] ih, byte[] peerHash);

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
     *  Blocking unless maxWait <= 0
     *  Caller should run in a thread.
     *  This also automatically announces ourself to our local tracker.
     *  For best results do a getPeers() first so we have tokens.
     *
     *  @param ih the Info Hash (torrent)
     *  @param maxWait the maximum total time to wait (ms) or 0 to do all in parallel and return immediately.
     *  @return the number of successful announces, not counting ourselves.
     */
    public int announce(byte[] ih, int max, long maxWait);

    /**
     * Stop everything.
     */
    public void stop();
}
