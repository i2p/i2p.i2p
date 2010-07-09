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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 *  The data structure for the tracker response.
 *  Handles both traditional and compact formats.
 *  Compact format 1 - a list of hashes - implemented
 *  Compact format 2 - One big string of concatenated hashes - unimplemented
 */
public class TrackerInfo
{
  private final String failure_reason;
  private final int interval;
  private final Set<Peer> peers;
  private int complete;
  private int incomplete;

  public TrackerInfo(InputStream in, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    this(new BDecoder(in), my_id, metainfo);
  }

  public TrackerInfo(BDecoder be, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    this(be.bdecodeMap().getMap(), my_id, metainfo);
  }

  public TrackerInfo(Map m, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    BEValue reason = (BEValue)m.get("failure reason");
    if (reason != null)
      {
        failure_reason = reason.getString();
        interval = -1;
        peers = null;
      }
    else
      {
        failure_reason = null;
        BEValue beInterval = (BEValue)m.get("interval");
        if (beInterval == null)
          throw new InvalidBEncodingException("No interval given");
        else
          interval = beInterval.getInt();

        BEValue bePeers = (BEValue)m.get("peers");
        if (bePeers == null)
          peers = Collections.EMPTY_SET;
        else
          // if compact is going to be one big string instead, try/catch here
          peers = getPeers(bePeers.getList(), my_id, metainfo);

        BEValue bev = (BEValue)m.get("complete");
        if (bev != null) try {
          complete = bev.getInt();
          if (complete < 0)
              complete = 0;
        } catch (InvalidBEncodingException ibe) {}

        bev = (BEValue)m.get("incomplete");
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

  private static Set<Peer> getPeers(List<BEValue> l, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    Set<Peer> peers = new HashSet(l.size());

    for (BEValue bev : l) {
        PeerID peerID;
        try {
            // Case 1 - non-compact - A list of dictionaries (maps)
            peerID = new PeerID(bev.getMap());
        } catch (InvalidBEncodingException ibe) {
            try {
                // Case 2 - compact - A list of 32-byte binary strings (hashes)
                peerID = new PeerID(bev.getBytes());
            } catch (InvalidBEncodingException ibe2) {
                // don't let one bad entry spoil the whole list
                //Snark.debug("Discarding peer from list: " + ibe, Snark.ERROR);
                continue;
            }
        }
        peers.add(new Peer(peerID, my_id, metainfo));
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

  public String getFailureReason()
  {
    return failure_reason;
  }

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
