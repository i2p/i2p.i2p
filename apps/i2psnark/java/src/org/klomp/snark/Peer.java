/* Peer - All public information concerning a peer.
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
import java.util.Arrays;
import java.util.Map;

import org.klomp.snark.bencode.*;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

public class Peer implements Comparable
{
  private Log _log = new Log(Peer.class);
  // Identifying property, the peer id of the other side.
  private final PeerID peerID;

  private final byte[] my_id;
  final MetaInfo metainfo;

  // The data in/output streams set during the handshake and used by
  // the actual connections.
  private DataInputStream din;
  private DataOutputStream dout;

  // Keeps state for in/out connections.  Non-null when the handshake
  // was successful, the connection setup and runs
  PeerState state;

  private I2PSocket sock;
  
  private boolean deregister = true;
  private static long __id;
  private long _id;

  /**
   * Creates a disconnected peer given a PeerID, your own id and the
   * relevant MetaInfo.
   */
  public Peer(PeerID peerID, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    this.peerID = peerID;
    this.my_id = my_id;
    this.metainfo = metainfo;
    _id = ++__id;
    //_log.debug("Creating a new peer with " + peerID.getAddress().calculateHash().toBase64(), new Exception("creating"));
  }

  /**
   * Creates a unconnected peer from the input and output stream got
   * from the socket. Note that the complete handshake (which can take
   * some time or block indefinitely) is done in the calling Thread to
   * get the remote peer id. To completely start the connection call
   * the connect() method.
   *
   * @exception IOException when an error occurred during the handshake.
   */
  public Peer(final I2PSocket sock, InputStream in, OutputStream out, byte[] my_id, MetaInfo metainfo)
    throws IOException
  {
    this.my_id = my_id;
    this.metainfo = metainfo;
    this.sock = sock;

    byte[] id  = handshake(in, out);
    this.peerID = new PeerID(id, sock.getPeerDestination());
    _id = ++__id;
    _log.debug("Creating a new peer with " + peerID.getAddress().calculateHash().toBase64(), new Exception("creating " + _id));
  }

  /**
   * Returns the id of the peer.
   */
  public PeerID getPeerID()
  {
    return peerID;
  }

  /**
   * Returns the String representation of the peerID.
   */
  public String toString()
  {
    if (peerID != null)
      return peerID.toString() + ' ' + _id;
    else
      return "[unknown id] " + _id;
  }

  /**
   * The hash code of a Peer is the hash code of the peerID.
   */
  public int hashCode()
  {
    return peerID.hashCode() ^ (2 << _id);
  }

  /**
   * Two Peers are equal when they have the same PeerID.
   * All other properties are ignored.
   */
  public boolean equals(Object o)
  {
    if (o instanceof Peer)
      {
        Peer p = (Peer)o;
        return _id == p._id && peerID.equals(p.peerID);
      }
    else
      return false;
  }

  /**
   * Compares the PeerIDs.
   */
  public int compareTo(Object o)
  {
    Peer p = (Peer)o;
    int rv = peerID.compareTo(p.peerID);
    if (rv == 0) {
        if (_id > p._id) return 1;
        else if (_id < p._id) return -1;
        else return 0;
    } else {
        return rv;
    }
  }

  /**
   * Runs the connection to the other peer. This method does not
   * return until the connection is terminated.
   *
   * When the connection is correctly started the connected() method
   * of the given PeerListener is called. If the connection ends or
   * the connection could not be setup correctly the disconnected()
   * method is called.
   *
   * If the given BitField is non-null it is send to the peer as first
   * message.
   */
  public void runConnection(PeerListener listener, BitField bitfield)
  {
    if (state != null)
      throw new IllegalStateException("Peer already started");

    _log.debug("Running connection to " + peerID.getAddress().calculateHash().toBase64(), new Exception("connecting"));    
    try
      {
        // Do we need to handshake?
        if (din == null)
          {
            sock = I2PSnarkUtil.instance().connect(peerID);
            _log.debug("Connected to " + peerID + ": " + sock);
            if ((sock == null) || (sock.isClosed())) {
                throw new IOException("Unable to reach " + peerID);
            }
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream(); //new BufferedOutputStream(sock.getOutputStream());
            if (true) {
                out = new BufferedOutputStream(out);
                in = new BufferedInputStream(sock.getInputStream());
            }
            //BufferedInputStream bis
            //  = new BufferedInputStream(sock.getInputStream());
            //BufferedOutputStream bos
            //  = new BufferedOutputStream(sock.getOutputStream());
            byte [] id = handshake(in, out); //handshake(bis, bos);
            byte [] expected_id = peerID.getID();
            if (!Arrays.equals(expected_id, id))
              throw new IOException("Unexpected peerID '"
                                    + PeerID.idencode(id)
                                    + "' expected '"
                                    + PeerID.idencode(expected_id) + "'");
            _log.debug("Handshake got matching IDs with " + toString());
          } else {
            _log.debug("Already have din [" + sock + "] with " + toString());
          }
        
        PeerConnectionIn in = new PeerConnectionIn(this, din);
        PeerConnectionOut out = new PeerConnectionOut(this, dout);
        PeerState s = new PeerState(this, listener, metainfo, in, out);
        
        // Send our bitmap
        if (bitfield != null)
          s.out.sendBitfield(bitfield);
    
        // We are up and running!
        state = s;
        listener.connected(this);
  
        _log.debug("Start running the reader with " + toString());
        // Use this thread for running the incomming connection.
        // The outgoing connection creates its own Thread.
        out.startup();
        Thread.currentThread().setName("Snark reader from " + peerID);
        s.in.run();
      }
    catch(IOException eofe)
      {
        // Ignore, probably just the other side closing the connection.
        // Or refusing the connection, timing out, etc.
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(this.toString(), eofe);
      }
    catch(Throwable t)
      {
        _log.error(this + ": " + t.getMessage(), t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
    finally
      {
        if (deregister) listener.disconnected(this);
        disconnect();
      }
  }

  /**
   * Sets DataIn/OutputStreams, does the handshake and returns the id
   * reported by the other side.
   */
  private byte[] handshake(InputStream in, OutputStream out) //BufferedInputStream bis, BufferedOutputStream bos)
    throws IOException
  {
    din = new DataInputStream(in);
    dout = new DataOutputStream(out);
    
    // Handshake write - header
    dout.write(19);
    dout.write("BitTorrent protocol".getBytes("UTF-8"));
    // Handshake write - zeros
    byte[] zeros = new byte[8];
    dout.write(zeros);
    // Handshake write - metainfo hash
    byte[] shared_hash = metainfo.getInfoHash();
    dout.write(shared_hash);
    // Handshake write - peer id
    dout.write(my_id);
    dout.flush();
    
    _log.debug("Wrote my shared hash and ID to " + toString());
    
    // Handshake read - header
    byte b = din.readByte();
    if (b != 19)
      throw new IOException("Handshake failure, expected 19, got "
                            + (b & 0xff) + " on " + sock);
    
    byte[] bs = new byte[19];
    din.readFully(bs);
    String bittorrentProtocol = new String(bs, "UTF-8");
    if (!"BitTorrent protocol".equals(bittorrentProtocol))
      throw new IOException("Handshake failure, expected "
                            + "'Bittorrent protocol', got '"
                            + bittorrentProtocol + "'");
    
    // Handshake read - zeros
    din.readFully(zeros);
    
    // Handshake read - metainfo hash
    bs = new byte[20];
    din.readFully(bs);
    if (!Arrays.equals(shared_hash, bs))
      throw new IOException("Unexpected MetaInfo hash");

    // Handshake read - peer id
    din.readFully(bs);
    _log.debug("Read the remote side's hash and peerID fully from " + toString());
    return bs;
  }

  public boolean isConnected()
  {
    return state != null;
  }

  /**
   * Disconnects this peer if it was connected.  If deregister is
   * true, PeerListener.disconnected() will be called when the
   * connection is completely terminated. Otherwise the connection is
   * silently terminated.
   */
  public void disconnect(boolean deregister)
  {
    // Both in and out connection will call this.
    this.deregister = deregister;
    disconnect();
  }

  void disconnect()
  {
    PeerState s = state;
    if (s != null)
      {
        state = null;

        PeerConnectionIn in = s.in;
        if (in != null)
          in.disconnect();
        PeerConnectionOut out = s.out;
        if (out != null)
          out.disconnect();
        PeerListener pl = s.listener;
        if (pl != null)
          pl.disconnected(this);
      }
    I2PSocket csock = sock;
    sock = null;
    if ( (csock != null) && (!csock.isClosed()) ) {
        try {
            csock.close(); 
        } catch (IOException ioe) { 
            _log.warn("Error disconnecting " + toString(), ioe);
        }
    }
  }

  /**
   * Tell the peer we have another piece.
   */
  public void have(int piece)
  {
    PeerState s = state;
    if (s != null)
      s.havePiece(piece);
  }

  /**
   * Whether or not the peer is interested in pieces we have. Returns
   * false if not connected.
   */
  public boolean isInterested()
  {
    PeerState s = state;
    return (s != null) && s.interested;
  }

  /**
   * Sets whether or not we are interested in pieces from this peer.
   * Defaults to false. When interest is true and this peer unchokes
   * us then we start downloading from it. Has no effect when not connected.
   */
  public void setInteresting(boolean interest)
  {
    PeerState s = state;
    if (s != null)
      s.setInteresting(interest);
  }

  /**
   * Whether or not the peer has pieces we want from it. Returns false
   * if not connected.
   */
  public boolean isInteresting()
  {
    PeerState s = state;
    return (s != null) && s.interesting;
  }

  /**
   * Sets whether or not we are choking the peer. Defaults to
   * true. When choke is false and the peer requests some pieces we
   * upload them, otherwise requests of this peer are ignored.
   */
  public void setChoking(boolean choke)
  {
    PeerState s = state;
    if (s != null)
      s.setChoking(choke);
  }

  /**
   * Whether or not we are choking the peer. Returns true when not connected.
   */
  public boolean isChoking()
  {
    PeerState s = state;
    return (s == null) || s.choking;
  }

  /**
   * Whether or not the peer choked us. Returns true when not connected.
   */
  public boolean isChoked()
  {
    PeerState s = state;
    return (s == null) || s.choked;
  }

  /**
   * Returns the number of bytes that have been downloaded.
   * Can be reset to zero with <code>resetCounters()</code>/
   */
  public long getDownloaded()
  {
    PeerState s = state;
    return (s != null) ? s.downloaded : 0;
  }

  /**
   * Returns the number of bytes that have been uploaded.
   * Can be reset to zero with <code>resetCounters()</code>/
   */
  public long getUploaded()
  {
    PeerState s = state;
    return (s != null) ? s.uploaded : 0;
  }

  /**
   * Resets the downloaded and uploaded counters to zero.
   */
  public void resetCounters()
  {
    PeerState s = state;
    if (s != null)
      {
        s.downloaded = 0;
        s.uploaded = 0;
      }
  }
  
  public long getInactiveTime() {
      PeerState s = state;
      if (s != null) {
          PeerConnectionOut out = s.out;
          if (out != null)
            return System.currentTimeMillis() - out.lastSent;
          else
            return -1; //"state, no out";
      } else {
          return -1; //"no state";
      }
  }
}
