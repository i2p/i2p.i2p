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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.util.Iterator;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Accepts incomming connections from peers. The ConnectionAcceptor
 * will call the connection() method when it detects an incomming BT
 * protocol connection. The PeerAcceptor will then create a new peer
 * if the PeerCoordinator wants more peers.
 */
public class PeerAcceptor
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerAcceptor.class);
  private final PeerCoordinator coordinator;
  final PeerCoordinatorSet coordinators;

  /** shorten timeout while reading handshake */
  private static final long HASH_READ_TIMEOUT = 45*1000;


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

  public void connection(I2PSocket socket,
                         InputStream in, OutputStream out)
    throws IOException
  {
    // inside this Peer constructor's handshake is where you'd deal with the other
    // side saying they want to communicate with another torrent - aka multitorrent
    // support, but because of how the protocol works, we can get away with just reading
    // ahead the first $LOOKAHEAD_SIZE bytes to figure out which infohash they want to
    // talk about, and we can just look for that in our list of active torrents.
    byte peerInfoHash[] = null;
    if (in instanceof BufferedInputStream) {
        // multitorrent
        in.mark(LOOKAHEAD_SIZE);
        long timeout = socket.getReadTimeout();
        socket.setReadTimeout(HASH_READ_TIMEOUT);
        try {
            peerInfoHash = readHash(in);
        } catch (IOException ioe) {
            // unique exception so ConnectionAcceptor can blame the peer
            throw new ProtocolException(ioe.toString());
        }
        socket.setReadTimeout(timeout);
        in.reset();
    } else {
        // Single torrent - is this working right?
        try {
          peerInfoHash = readHash(in);
          if (_log.shouldLog(Log.INFO))
              _log.info("infohash read from " + socket.getPeerDestination().calculateHash().toBase64() 
                        + ": " + Base64.encode(peerInfoHash));
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Unable to read the infohash from " + socket.getPeerDestination().calculateHash().toBase64());
            throw ioe;
        }
        in = new SequenceInputStream(new ByteArrayInputStream(peerInfoHash), in);
    }
    if (coordinator != null) {
        // single torrent capability
        if (DataHelper.eq(coordinator.getInfoHash(), peerInfoHash)) {
            if (coordinator.needPeers())
              {
                Peer peer = new Peer(socket, in, out, coordinator.getID(),
                                     coordinator.getInfoHash(), coordinator.getMetaInfo());
                coordinator.addPeer(peer);
              }
            else
              socket.close();
        } else {
          // its for another infohash, but we are only single torrent capable.  b0rk.
            throw new IOException("Peer wants another torrent (" + Base64.encode(peerInfoHash) 
                                  + ") while we only support (" + Base64.encode(coordinator.getInfoHash()) + ")");
        }
    } else {
        // multitorrent capable, so lets see what we can handle
        for (Iterator iter = coordinators.iterator(); iter.hasNext(); ) {
            PeerCoordinator cur = (PeerCoordinator)iter.next();

            if (DataHelper.eq(cur.getInfoHash(), peerInfoHash)) {
                if (cur.needPeers())
                  {
                    Peer peer = new Peer(socket, in, out, cur.getID(),
                                         cur.getInfoHash(), cur.getMetaInfo());
                    cur.addPeer(peer);
                    return;
                  }
                else 
                  {
                    if (_log.shouldLog(Log.DEBUG))
                      _log.debug("Rejecting new peer for " + cur.getName());
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

  private static final String PROTO_STR = "BitTorrent protocol";
  private static final int PROTO_STR_LEN = PROTO_STR.length();
  private static final int PROTO_LEN = PROTO_STR_LEN + 1;
  private static final int[] PROTO = new int[PROTO_LEN];
  static {
      PROTO[0] = PROTO_STR_LEN;
      for (int i = 0; i < PROTO_STR_LEN; i++) {
          PROTO[i+1] = PROTO_STR.charAt(i);
      }
  }

  /** 48 */
  private static final int LOOKAHEAD_SIZE = PROTO_LEN +
                                            8 + // blank, reserved
                                            20; // infohash

  /** 
   * Read ahead to the infohash, throwing an exception if there isn't enough data.
   * Also check the first 20 bytes for the correct protocol here and throw IOE if bad,
   * so we don't hang waiting for 48 bytes if it's not a bittorrent client.
   * The 20 bytes are checked again in Peer.handshake().
   */
  private static byte[] readHash(InputStream in) throws IOException {
    for (int i = 0; i < PROTO_LEN; i++) {
        int b = in.read();
        if (b != PROTO[i])
            throw new IOException("Bad protocol 0x" + Integer.toHexString(b) + " at byte " + i);
    }
    if (in.skip(8) != 8)
        throw new IOException("EOF before hash");
    byte buf[] = new byte[20];
    int read = DataHelper.read(in, buf);
    if (read != buf.length)
        throw new IOException("Unable to read the hash (read " + read + ")");
    return buf;
  }

    /** 
     *  A unique exception so we can tell the ConnectionAcceptor about non-BT connections
     *  @since 0.9.1
     */
    public static class ProtocolException extends IOException {
        public ProtocolException(String s) {
            super(s);
        }
    }
}
