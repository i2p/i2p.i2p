/* PeerAcceptor - Accepts incomming connections from peers.
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

import java.io.*;
import java.net.*;
import java.util.Iterator;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Accepts incomming connections from peers. The ConnectionAcceptor
 * will call the connection() method when it detects an incomming BT
 * protocol connection. The PeerAcceptor will then create a new peer
 * if the PeerCoordinator wants more peers.
 */
public class PeerAcceptor
{
  private final PeerCoordinator coordinator;
  private final PeerCoordinatorSet coordinators;

  public PeerAcceptor(PeerCoordinator coordinator)
  {
    this.coordinator = coordinator;
    this.coordinators = null;
  }
  
  public PeerAcceptor(PeerCoordinatorSet coordinators)
  {
    this.coordinators = coordinators;
    this.coordinator = null;
  }

  private static final int LOOKAHEAD_SIZE = "19".length() +
                                            "BitTorrent protocol".length() +
                                            8 + // blank, reserved
                                            20; // infohash

  public void connection(I2PSocket socket,
                         BufferedInputStream bis, BufferedOutputStream bos)
    throws IOException
  {
    // inside this Peer constructor's handshake is where you'd deal with the other
    // side saying they want to communicate with another torrent - aka multitorrent
    // support, but because of how the protocol works, we can get away with just reading
    // ahead the first $LOOKAHEAD_SIZE bytes to figure out which infohash they want to
    // talk about, and we can just look for that in our list of active torrents.
    bis.mark(LOOKAHEAD_SIZE);
    byte peerInfoHash[] = readHash(bis);
    bis.reset();
    if (coordinator != null) {
        // single torrent capability
        MetaInfo meta = coordinator.getMetaInfo();
        if (DataHelper.eq(meta.getInfoHash(), peerInfoHash)) {
            if (coordinator.needPeers())
              {
                Peer peer = new Peer(socket, bis, bos, coordinator.getID(),
                                     coordinator.getMetaInfo());
                coordinator.addPeer(peer);
              }
            else
              socket.close();
        } else {
          // its for another infohash, but we are only single torrent capable.  b0rk.
            throw new IOException("Peer wants another torrent (" + Base64.encode(peerInfoHash) 
                                  + ") while we only support (" + Base64.encode(meta.getInfoHash()) + ")");
        }
    } else {
        // multitorrent capable, so lets see what we can handle
        for (Iterator iter = coordinators.iterator(); iter.hasNext(); ) {
            PeerCoordinator cur = (PeerCoordinator)iter.next();
            MetaInfo meta = cur.getMetaInfo();
            
            if (DataHelper.eq(meta.getInfoHash(), peerInfoHash)) {
                if (cur.needPeers())
                  {
                    Peer peer = new Peer(socket, bis, bos, cur.getID(),
                                         cur.getMetaInfo());
                    cur.addPeer(peer);
                    return;
                  }
                else 
                  {
                    socket.close();
                    return;
                  }
            }
        }
        // this is only reached if none of the coordinators match the infohash
        throw new IOException("Peer wants another torrent (" + Base64.encode(peerInfoHash) 
                              + ") while we don't support that hash");
    }
  }

  /** 
   * Read ahead to the infohash, throwing an exception if there isn't enough data
   */
  private byte[] readHash(InputStream in) throws IOException {
    byte buf[] = new byte[LOOKAHEAD_SIZE];
    int read = DataHelper.read(in, buf);
    if (read != buf.length)
        throw new IOException("Unable to read the hash (read " + read + ")");
    byte rv[] = new byte[20];
    System.arraycopy(buf, buf.length-rv.length-1, rv, 0, rv.length);
    return rv;
  }
}
