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

import net.i2p.client.streaming.I2PSocket;

/**
 * Accepts incomming connections from peers. The ConnectionAcceptor
 * will call the connection() method when it detects an incomming BT
 * protocol connection. The PeerAcceptor will then create a new peer
 * if the PeerCoordinator wants more peers.
 */
public class PeerAcceptor
{
  private final PeerCoordinator coordinator;

  public PeerAcceptor(PeerCoordinator coordinator)
  {
    this.coordinator = coordinator;
  }

  public void connection(I2PSocket socket,
                         BufferedInputStream bis, BufferedOutputStream bos)
    throws IOException
  {
    if (coordinator.needPeers())
      {
        // XXX: inside this Peer constructor's handshake is where you'd deal with the other
        //      side saying they want to communicate with another torrent - aka multitorrent
        //      support.  you'd then want to grab the meta info /they/ want, look that up in
        //      our own list of active torrents, and put it on the right coordinator for it.
        //      this currently, however, throws an IOException if the metainfo doesn't match
        //      coodinator.getMetaInfo (Peer.java:242)
        Peer peer = new Peer(socket, bis, bos, coordinator.getID(),
                             coordinator.getMetaInfo());
        coordinator.addPeer(peer);
      }
    else
      socket.close();
  }
}
