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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import net.i2p.client.streaming.I2PSocket;
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
  final static long CHECK_PERIOD = PeerCoordinator.CHECK_PERIOD; // 40 seconds
  final static int RATE_DEPTH = PeerCoordinator.RATE_DEPTH; // make following arrays RATE_DEPTH long
  private long uploaded_old[] = {-1,-1,-1};
  private long downloaded_old[] = {-1,-1,-1};

  //  bytes per bt spec:                 0011223344556677
  static final long OPTION_EXTENSION = 0x0000000000100000l;
  static final long OPTION_FAST      = 0x0000000000000004l;
  private long options;

  /**
   * Outgoing connection.
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
   * Incoming connection.
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
    if (_log.shouldLog(Log.DEBUG))
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
    @Override
  public String toString()
  {
    if (peerID != null)
      return peerID.toString() + ' ' + _id;
    else
      return "[unknown id] " + _id;
  }

  /**
   * @return socket debug string (for debug printing)
   */
  public String getSocket()
  {
    if (state != null) {
        String r = state.getRequests();
        if (r != null)
            return sock.toString() + "<br>Requests: " + r;
    }
    return sock.toString();
  }

  /**
   * The hash code of a Peer is the hash code of the peerID.
   */
    @Override
  public int hashCode()
  {
    return peerID.hashCode() ^ (7777 * (int)_id);
  }

  /**
   * Two Peers are equal when they have the same PeerID.
   * All other properties are ignored.
   */
    @Override
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
   * @deprecated unused?
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
  public void runConnection(I2PSnarkUtil util, PeerListener listener, BitField bitfield)
  {
    if (state != null)
      throw new IllegalStateException("Peer already started");

    if (_log.shouldLog(Log.DEBUG))
        _log.debug("Running connection to " + peerID.getAddress().calculateHash().toBase64(), new Exception("connecting"));    
    try
      {
        // Do we need to handshake?
        if (din == null)
          {
            // Outgoing connection
            sock = util.connect(peerID);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connected to " + peerID + ": " + sock);
            if ((sock == null) || (sock.isClosed())) {
                throw new IOException("Unable to reach " + peerID);
            }
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream(); //new BufferedOutputStream(sock.getOutputStream());
            if (true) {
                // buffered output streams are internally synchronized, so we can't get through to the underlying
                // I2PSocket's MessageOutputStream to close() it if we are blocking on a write(...).  Oh, and the
                // buffer is unnecessary anyway, as unbuffered access lets the streaming lib do the 'right thing'.
                //out = new BufferedOutputStream(out);
                in = new BufferedInputStream(sock.getInputStream());
            }
            //BufferedInputStream bis
            //  = new BufferedInputStream(sock.getInputStream());
            //BufferedOutputStream bos
            //  = new BufferedOutputStream(sock.getOutputStream());
            byte [] id = handshake(in, out); //handshake(bis, bos);
            byte [] expected_id = peerID.getID();
            if (expected_id == null) {
                peerID.setID(id);
            } else if (Arrays.equals(expected_id, id)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handshake got matching IDs with " + toString());
            } else {
                throw new IOException("Unexpected peerID '"
                                    + PeerID.idencode(id)
                                    + "' expected '"
                                    + PeerID.idencode(expected_id) + "'");
            }
          } else {
            // Incoming connection
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Already have din [" + sock + "] with " + toString());
          }
        
        PeerConnectionIn in = new PeerConnectionIn(this, din);
        PeerConnectionOut out = new PeerConnectionOut(this, dout);
        PeerState s = new PeerState(this, listener, metainfo, in, out);
        
        if ((options & OPTION_EXTENSION) != 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peer supports extensions, sending test message");
            out.sendExtension(0, ExtensionHandshake.getPayload());
        }

        // Send our bitmap
        if (bitfield != null)
          s.out.sendBitfield(bitfield);
    
        // We are up and running!
        state = s;
        listener.connected(this);
  
        if (_log.shouldLog(Log.DEBUG))
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
    // Handshake write - options
    dout.writeLong(OPTION_EXTENSION);
    // Handshake write - metainfo hash
    byte[] shared_hash = metainfo.getInfoHash();
    dout.write(shared_hash);
    // Handshake write - peer id
    dout.write(my_id);
    dout.flush();
    
    if (_log.shouldLog(Log.DEBUG))
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
    
    // Handshake read - options
    options = din.readLong();
    
    // Handshake read - metainfo hash
    bs = new byte[20];
    din.readFully(bs);
    if (!Arrays.equals(shared_hash, bs))
      throw new IOException("Unexpected MetaInfo hash");

    // Handshake read - peer id
    din.readFully(bs);
    if (_log.shouldLog(Log.DEBUG))
        _log.debug("Read the remote side's hash and peerID fully from " + toString());

    if (options != 0) {
        // send them something
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Peer supports options 0x" + Long.toString(options, 16) + ": " + toString());
    }

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
        // try to save partial piece
        if (this.deregister) {
          PeerListener p = s.listener;
          if (p != null) {
            List<PartialPiece> pcs = s.returnPartialPieces();
            if (!pcs.isEmpty())
                p.savePartialPieces(this, pcs);
            // now covered by savePartialPieces
            //p.markUnrequested(this);
          }
        }
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
   * Tell the other side that we are no longer interested in any of
   * the outstanding requests (if any) for this piece.
   * @since 0.8.1
   */
  void cancel(int piece) {
    PeerState s = state;
    if (s != null)
      s.cancelPiece(piece);
  }

  /**
   * Are we currently requesting the piece?
   * @since 0.8.1
   */
  boolean isRequesting(int p) {
    PeerState s = state;
    return s != null && s.isRequesting(p);
  }

  /**
   * Update the request queue.
   * Call after adding wanted pieces.
   * @since 0.8.1
   */
  void request() {
    PeerState s = state;
    if (s != null)
      s.addRequest();
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
   * @deprecated unused
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
          PeerConnectionIn in = s.in;
          PeerConnectionOut out = s.out;
          if (in != null && out != null) {
            long now = System.currentTimeMillis();
            return Math.max(now - out.lastSent, now - in.lastRcvd);
          } else
            return -1; //"state, no out";
      } else {
          return -1; //"no state";
      }
  }

  /**
   * Send keepalive
   */
  public void keepAlive()
  {
    PeerState s = state;
    if (s != null)
      s.keepAlive();
  }

  /**
   * Retransmit outstanding requests if necessary
   */
  public void retransmitRequests()
  {
    PeerState s = state;
    if (s != null)
      s.retransmitRequests();
  }

  /**
   * Return how much the peer has
   */
  public int completed()
  {
    PeerState s = state;
    if (s == null || s.bitfield == null)
        return 0;
    return s.bitfield.count();
  }

  /**
   * Return if a peer is a seeder
   */
  public boolean isCompleted()
  {
    PeerState s = state;
    if (s == null || s.bitfield == null)
        return false;
    return s.bitfield.complete();
  }

  /**
   * Push the total uploaded/downloaded onto a RATE_DEPTH deep stack
   */
  public void setRateHistory(long up, long down)
  {
    PeerCoordinator.setRate(up, uploaded_old);
    PeerCoordinator.setRate(down, downloaded_old);
  }

  /**
   * Returns the 4-minute-average rate in Bps
   */
  public long getUploadRate()
  {
    return PeerCoordinator.getRate(uploaded_old);
  }

  public long getDownloadRate()
  {
    return PeerCoordinator.getRate(downloaded_old);
  }
}
