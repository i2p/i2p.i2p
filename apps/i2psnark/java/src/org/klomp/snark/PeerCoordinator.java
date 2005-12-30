/* PeerCoordinator - Coordinates which peers do what (up and downloading).
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

import java.util.*;
import java.io.IOException;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinates what peer does what.
 */
public class PeerCoordinator implements PeerListener
{
  private final Log _log = new Log(PeerCoordinator.class);
  final MetaInfo metainfo;
  final Storage storage;
  final Snark snark;

  // package local for access by CheckDownLoadersTask
  final static long CHECK_PERIOD = 20*1000; // 20 seconds
  final static int MAX_CONNECTIONS = 24;
  final static int MAX_UPLOADERS = 12; // i2p: might as well balance it out

  // Approximation of the number of current uploaders.
  // Resynced by PeerChecker once in a while.
  int uploaders = 0;

  // final static int MAX_DOWNLOADERS = MAX_CONNECTIONS;
  // int downloaders = 0;

  private long uploaded;
  private long downloaded;

  // synchronize on this when changing peers or downloaders
  final List peers = new ArrayList();
  /** estimate of the peers, without requiring any synchronization */
  volatile int peerCount;

  /** Timer to handle all periodical tasks. */
  private final Timer timer = new Timer(true);

  private final byte[] id;

  // Some random wanted pieces
  private final List wantedPieces;

  private boolean halted = false;

  private final CoordinatorListener listener;
  
  public String trackerProblems = null;
  public int trackerSeenPeers = 0;

  public PeerCoordinator(byte[] id, MetaInfo metainfo, Storage storage,
                         CoordinatorListener listener, Snark torrent)
  {
    this.id = id;
    this.metainfo = metainfo;
    this.storage = storage;
    this.listener = listener;
    this.snark = torrent;

    // Make a list of pieces
    wantedPieces = new ArrayList();
    BitField bitfield = storage.getBitField();
    for(int i = 0; i < metainfo.getPieces(); i++)
      if (!bitfield.get(i))
        wantedPieces.add(new Piece(i));
    Collections.shuffle(wantedPieces);

    // Install a timer to check the uploaders.
    timer.schedule(new PeerCheckerTask(this), CHECK_PERIOD, CHECK_PERIOD);
  }
  
  public Storage getStorage() { return storage; }
  public CoordinatorListener getListener() { return listener; }

  public byte[] getID()
  {
    return id;
  }

  public boolean completed()
  {
    return storage.complete();
  }

  public int getPeerCount() { return peerCount; }

  public int getPeers()
  {
    synchronized(peers)
      {
        int rv = peers.size();
        peerCount = rv;
        return rv;
      }
  }

  /**
   * Returns how many bytes are still needed to get the complete file.
   */
  public long getLeft()
  {
    // XXX - Only an approximation.
    return storage.needed() * metainfo.getPieceLength(0);
  }

  /**
   * Returns the total number of uploaded bytes of all peers.
   */
  public long getUploaded()
  {
    return uploaded;
  }

  /**
   * Returns the total number of downloaded bytes of all peers.
   */
  public long getDownloaded()
  {
    return downloaded;
  }

  public MetaInfo getMetaInfo()
  {
    return metainfo;
  }

  public boolean needPeers()
  {
    synchronized(peers)
      {
        return !halted && peers.size() < MAX_CONNECTIONS;
      }
  }
  
  public boolean halted() { return halted; }

  public void halt()
  {
    halted = true;
    List removed = new ArrayList();
    synchronized(peers)
      {
        // Stop peer checker task.
        timer.cancel();

        // Stop peers.
        removed.addAll(peers);
        peers.clear();
        peerCount = 0;
      }

    while (removed.size() > 0) {
        Peer peer = (Peer)removed.remove(0);
        peer.disconnect();
        removePeerFromPieces(peer);
    }
  }

  public void connected(Peer peer)
  { 
    if (halted)
      {
        peer.disconnect(false);
        return;
      }

    Peer toDisconnect = null;
    synchronized(peers)
      {
        Peer old = peerIDInList(peer.getPeerID(), peers);
        if ( (old != null) && (old.getInactiveTime() > 2*60*1000) ) {
            // idle for 2 minutes, kill the old con
            peers.remove(old);
            toDisconnect = old;
            old = null;
        }
        if (old != null)
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Already connected to: " + peer + ": " + old + ", inactive for " + old.getInactiveTime());
            peer.disconnect(false); // Don't deregister this connection/peer.
          }
        else
          {
            if (_log.shouldLog(Log.INFO))
              _log.info("New connection to peer: " + peer + " for " + metainfo.getName());

            // Add it to the beginning of the list.
            // And try to optimistically make it a uploader.
            peers.add(0, peer);
            peerCount = peers.size();
            unchokePeer();

            if (listener != null)
              listener.peerChange(this, peer);
          }
      }
    if (toDisconnect != null) {
        toDisconnect.disconnect(false);
        removePeerFromPieces(toDisconnect);
    }
  }

  private static Peer peerIDInList(PeerID pid, List peers)
  {
    Iterator it = peers.iterator();
    while (it.hasNext()) {
      Peer cur = (Peer)it.next();
      if (pid.sameID(cur.getPeerID()))
        return cur;
    }
    return null;
  }

  public void addPeer(final Peer peer)
  {
    if (halted)
      {
        peer.disconnect(false);
        return;
      }

    boolean need_more;
    synchronized(peers)
      {
        need_more = !peer.isConnected() && peers.size() < MAX_CONNECTIONS;
      }

    if (need_more)
      {
        _log.debug("Adding a peer " + peer.getPeerID().getAddress().calculateHash().toBase64() + " for " + metainfo.getName(), new Exception("add/run"));

        // Run the peer with us as listener and the current bitfield.
        final PeerListener listener = this;
        final BitField bitfield = storage.getBitField();
        Runnable r = new Runnable()
          {
            public void run()
            {
              peer.runConnection(listener, bitfield);
            }
          };
        String threadName = peer.toString();
        new I2PThread(r, threadName).start();
      }
    else
      if (_log.shouldLog(Log.DEBUG)) {
        if (peer.isConnected())
          _log.info("Add peer already connected: " + peer);
        else
          _log.info("MAX_CONNECTIONS = " + MAX_CONNECTIONS
                    + " not accepting extra peer: " + peer);
      }
  }


  // (Optimistically) unchoke. Should be called with peers synchronized
  void unchokePeer()
  {
    // linked list will contain all interested peers that we choke.
    // At the start are the peers that have us unchoked at the end the
    // other peer that are interested, but are choking us.
    List interested = new LinkedList();
    synchronized (peers) {
        Iterator it = peers.iterator();
        while (it.hasNext())
          {
            Peer peer = (Peer)it.next();
            boolean remove = false;
            if (uploaders < MAX_UPLOADERS
                && peer.isChoking()
                && peer.isInterested())
              {
                if (!peer.isChoked())
                  interested.add(0, peer);
                else
                  interested.add(peer);
              }
          }

        while (uploaders < MAX_UPLOADERS && interested.size() > 0)
          {
            Peer peer = (Peer)interested.remove(0);
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Unchoke: " + peer);
            peer.setChoking(false);
            uploaders++;
            // Put peer back at the end of the list.
            peers.remove(peer);
            peers.add(peer);
            peerCount = peers.size();
          }
    }
  }

  public byte[] getBitMap()
  {
    return storage.getBitField().getFieldBytes();
  }

  /**
   * Returns true if we don't have the given piece yet.
   */
  public boolean gotHave(Peer peer, int piece)
  {
    if (listener != null)
      listener.peerChange(this, peer);

    synchronized(wantedPieces)
      {
        return wantedPieces.contains(new Piece(piece));
      }
  }

  /**
   * Returns true if the given bitfield contains at least one piece we
   * are interested in.
   */
  public boolean gotBitField(Peer peer, BitField bitfield)
  {
    if (listener != null)
      listener.peerChange(this, peer);

    synchronized(wantedPieces)
      {
        Iterator it = wantedPieces.iterator();
        while (it.hasNext())
          {
            Piece p = (Piece)it.next();
            int i = p.getId();
            if (bitfield.get(i))
              p.addPeer(peer);
              return true;
          }
      }
    return false;
  }

  /**
   * Returns one of pieces in the given BitField that is still wanted or
   * -1 if none of the given pieces are wanted.
   */
  public int wantPiece(Peer peer, BitField havePieces)
  {
    if (halted) {
      if (_log.shouldLog(Log.WARN))
          _log.warn("We don't want anything from the peer, as we are halted!  peer=" + peer);
      return -1;
    }

    synchronized(wantedPieces)
      {
        Piece piece = null;
        Collections.sort(wantedPieces); // Sort in order of rarest first.
        List requested = new ArrayList(); 
        Iterator it = wantedPieces.iterator();
        while (piece == null && it.hasNext())
          {
            Piece p = (Piece)it.next();
            if (havePieces.get(p.getId()) && !p.isRequested())
              {
                piece = p;
              }
            else if (p.isRequested()) 
            {
                requested.add(p);
            }
          }
        
        //Only request a piece we've requested before if there's no other choice.
        if (piece == null) {
            Iterator it2 = requested.iterator();
            while (piece == null && it2.hasNext())
              {
                Piece p = (Piece)it2.next();
                if (havePieces.get(p.getId()))
                  {
                    piece = p;
                  }
              }
            if (piece == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("nothing to even rerequest from " + peer + ": requested = " + requested 
                              + " wanted = " + wantedPieces + " peerHas = " + havePieces);
                return -1; //If we still can't find a piece we want, so be it.
            }
        }
        piece.setRequested(true);
        return piece.getId();
      }
  }

  /**
   * Returns a byte array containing the requested piece or null of
   * the piece is unknown.
   */
  public byte[] gotRequest(Peer peer, int piece)
  {
    if (halted)
      return null;

    try
      {
        return storage.getPiece(piece);
      }
    catch (IOException ioe)
      {
        snark.stopTorrent();
        _log.error("Error reading the storage for " + metainfo.getName(), ioe);
        throw new RuntimeException("B0rked");
      }
  }

  /**
   * Called when a peer has uploaded some bytes of a piece.
   */
  public void uploaded(Peer peer, int size)
  {
    uploaded += size;

    if (listener != null)
      listener.peerChange(this, peer);
  }

  /**
   * Called when a peer has downloaded some bytes of a piece.
   */
  public void downloaded(Peer peer, int size)
  {
    downloaded += size;

    if (listener != null)
      listener.peerChange(this, peer);
  }

  /**
   * Returns false if the piece is no good (according to the hash).
   * In that case the peer that supplied the piece should probably be
   * blacklisted.
   */
  public boolean gotPiece(Peer peer, int piece, byte[] bs)
  {
    if (halted) {
      _log.info("Got while-halted piece " + piece + "/" + metainfo.getPieces() +" from " + peer + " for " + metainfo.getName());
      return true; // We don't actually care anymore.
    }
    
    synchronized(wantedPieces)
      {
        Piece p = new Piece(piece);
        if (!wantedPieces.contains(p))
          {
            _log.info("Got unwanted piece " + piece + "/" + metainfo.getPieces() +" from " + peer + " for " + metainfo.getName());
            
            // No need to announce have piece to peers.
            // Assume we got a good piece, we don't really care anymore.
            return true;
          }
        
        try
          {
            if (storage.putPiece(piece, bs))
              {
                _log.info("Got valid piece " + piece + "/" + metainfo.getPieces() +" from " + peer + " for " + metainfo.getName());
              }
            else
              {
                // Oops. We didn't actually download this then... :(
                downloaded -= metainfo.getPieceLength(piece);
                _log.warn("Got BAD piece " + piece + "/" + metainfo.getPieces() + " from " + peer + " for " + metainfo.getName());
                return false; // No need to announce BAD piece to peers.
              }
          }
        catch (IOException ioe)
          {
            snark.stopTorrent();
            _log.error("Error writing storage for " + metainfo.getName(), ioe);
            throw new RuntimeException("B0rked");
          }
        wantedPieces.remove(p);
      }

    // Announce to the world we have it!
    synchronized(peers)
      {
        Iterator it = peers.iterator();
        while (it.hasNext())
          {
            Peer p = (Peer)it.next();
            if (p.isConnected())
              p.have(piece);
          }
      }
    
    return true;
  }

  public void gotChoke(Peer peer, boolean choke)
  {
    if (_log.shouldLog(Log.INFO))
      _log.info("Got choke(" + choke + "): " + peer);

    if (listener != null)
      listener.peerChange(this, peer);
  }

  public void gotInterest(Peer peer, boolean interest)
  {
    if (interest)
      {
        synchronized(peers)
          {
            if (uploaders < MAX_UPLOADERS)
              {
                if(peer.isChoking())
                  {
                    uploaders++;
                    peer.setChoking(false);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Unchoke: " + peer);
                  }
              }
          }
      }

    if (listener != null)
      listener.peerChange(this, peer);
  }

  public void disconnected(Peer peer)
  {
    if (_log.shouldLog(Log.INFO))
        _log.info("Disconnected " + peer, new Exception("Disconnected by"));
    
    synchronized(peers)
      {
        // Make sure it is no longer in our lists
        if (peers.remove(peer))
          {
            // Unchoke some random other peer
            unchokePeer();
            removePeerFromPieces(peer);
          }
        peerCount = peers.size();
      }

    if (listener != null)
      listener.peerChange(this, peer);
  }
  
  /** Called when a peer is removed, to prevent it from being used in 
   * rarest-first calculations.
   */
  public void removePeerFromPieces(Peer peer) {
      synchronized(wantedPieces) {
          for(Iterator iter = wantedPieces.iterator(); iter.hasNext(); ) {
              Piece piece = (Piece)iter.next();
              piece.removePeer(peer);
          }
      } 
  }
}
