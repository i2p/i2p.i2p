/* TrackerInfo - Holds information returned by a tracker, mainly the peer list.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.
   
   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.
 
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
 
   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Hash;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 *  The data structure for the tracker response.
 *  Handles both traditional and compact formats.
 *  Compact format 1 - a list of hashes - early format for testing
 *  Compact format 2 - One big string of concatenated hashes - official format
 */
class TrackerInfo
{
  private final String failure_reason;
  private final int interval;
  private final Set<Peer> peers;
  private int complete;
  private int incomplete;

  /** @param metainfo may be null */
  public TrackerInfo(InputStream in, byte[] my_id, byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util)
    throws IOException
  {
    this(new BDecoder(in), my_id, infohash, metainfo, util);
  }

  private TrackerInfo(BDecoder be, byte[] my_id, byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util)
    throws IOException
  {
    this(be.bdecodeMap().getMap(), my_id, infohash, metainfo, util);
  }

  private TrackerInfo(Map<String, BEValue> m, byte[] my_id, byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util)
    throws IOException
  {
    BEValue reason = m.get("failure reason");
    if (reason != null)
      {
        failure_reason = reason.getString();
        interval = -1;
        peers = null;
      }
    else
      {
        failure_reason = null;
        BEValue beInterval = m.get("interval");
        if (beInterval == null)
          throw new InvalidBEncodingException("No interval given");
        else
          interval = beInterval.getInt();

        BEValue bePeers = m.get("peers");
        if (bePeers == null) {
          peers = Collections.emptySet();
        } else {
            Set<Peer> p;
            try {
              // One big string (the official compact format)
              p = getPeers(bePeers.getBytes(), my_id, infohash, metainfo, util);
            } catch (InvalidBEncodingException ibe) {
              // List of Dictionaries or List of Strings
              p = getPeers(bePeers.getList(), my_id, infohash, metainfo, util);
            }
            peers = p;
        }

        BEValue bev = m.get("complete");
        if (bev != null) try {
          complete = bev.getInt();
          if (complete < 0)
              complete = 0;
        } catch (InvalidBEncodingException ibe) {}

        bev = m.get("incomplete");
        if (bev != null) try {
          incomplete = bev.getInt();
          if (incomplete < 0)
              incomplete = 0;
        } catch (InvalidBEncodingException ibe) {}
      }
  }

/******
  public static Set<Peer> getPeers(InputStream in, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    return getPeers(new BDecoder(in), my_id, metainfo);
  }

  public static Set<Peer> getPeers(BDecoder be, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    return getPeers(be.bdecodeList().getList(), my_id, metainfo);
  }
******/

  /**
   *  To convert returned UDPTracker data to the standard structure
   *  @param hashes may be null
   *  @param error may be null
   *  @since 0.9.14
   */
  public TrackerInfo(Set<Hash> hashes, int interval, int complete, int incomplete, String error,
                     byte[] my_id, byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util) {
      peers = getPeers(hashes, my_id, infohash, metainfo, util);
      this.interval = interval;
      this.complete = complete;
      this.incomplete = incomplete;
      failure_reason = error;
  }

  /** List of Dictionaries or List of Strings */
  private static Set<Peer> getPeers(List<BEValue> l, byte[] my_id, byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util)
    throws IOException
  {
    Set<Peer> peers = new HashSet<Peer>(l.size());

    for (BEValue bev : l) {
        PeerID peerID;
        try {
            // Case 1 - non-compact - A list of dictionaries (maps)
            peerID = new PeerID(bev.getMap());
        } catch (InvalidBEncodingException ibe) {
            try {
                // Case 2 - compact - A list of 32-byte binary strings (hashes)
                // This was just for testing and is not the official format
                peerID = new PeerID(bev.getBytes(), util);
            } catch (InvalidBEncodingException ibe2) {
                // don't let one bad entry spoil the whole list
                //Snark.debug("Discarding peer from list: " + ibe, Snark.ERROR);
                continue;
            }
        }
        peers.add(new Peer(peerID, my_id, infohash, metainfo));
      }

    return peers;
  }

  private static final int HASH_LENGTH = 32;

  /**
   *  One big string of concatenated 32-byte hashes
   *  @since 0.8.1
   */
  private static Set<Peer> getPeers(byte[] l, byte[] my_id, byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util)
    throws IOException
  {
    int count = l.length / HASH_LENGTH;
    Set<Peer> peers = new HashSet<Peer>(count);

    for (int i = 0; i < count; i++) {
        PeerID peerID;
        byte[] hash = new byte[HASH_LENGTH];
        System.arraycopy(l, i * HASH_LENGTH, hash, 0, HASH_LENGTH);
        try {
            peerID = new PeerID(hash, util);
        } catch (InvalidBEncodingException ibe) {
            // won't happen
            continue;
        }
        peers.add(new Peer(peerID, my_id, infohash, metainfo));
      }

    return peers;
  }

  /**
   *  From Hash to Peer
   *  @since 0.9.14
   */
  private static Set<Peer> getPeers(Set<Hash> hashes, byte[] my_id,
                                    byte[] infohash, MetaInfo metainfo, I2PSnarkUtil util) {
    if (hashes == null)
        return Collections.emptySet();
    Set<Peer> peers = new HashSet<Peer>(hashes.size());
    for (Hash h : hashes) {
        PeerID peerID;
        byte[] hash = new byte[HASH_LENGTH];
        System.arraycopy(h.getData(), 0, hash, 0, HASH_LENGTH);
        try {
            peerID = new PeerID(hash, util);
        } catch (InvalidBEncodingException ibe) {
            // won't happen
            continue;
        }
        peers.add(new Peer(peerID, my_id, infohash, metainfo));
      }

    return peers;
  }

  public Set<Peer> getPeers()
  {
    return peers;
  }

  public int getPeerCount()
  {
    int pc = peers == null ? 0 : peers.size();
    return Math.max(pc, complete + incomplete - 1);
  }

  /** @since 0.9.9 */
  public int getSeedCount()
  {
    return complete;
  }

  /**
   *  Not HTML escaped.
   */
  public String getFailureReason()
  {
    return failure_reason;
  }

  /** in seconds */
  public int getInterval()
  {
    return interval;
  }

    @Override
  public String toString()
  {
    if (failure_reason != null)
      return "TrackerInfo[FAILED: " + failure_reason + "]";
    else
      return "TrackerInfo[interval=" + interval
        + (complete > 0 ? (", complete=" + complete) : "" )
        + (incomplete > 0 ? (", incomplete=" + incomplete) : "" )
        + ", peers=" + peers + "]";
  }
}
