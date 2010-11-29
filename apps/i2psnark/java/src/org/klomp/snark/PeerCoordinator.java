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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Coordinates what peer does what.
 */
public class PeerCoordinator implements PeerListener
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerCoordinator.class);
  final MetaInfo metainfo;
  final Storage storage;
  final Snark snark;

  // package local for access by CheckDownLoadersTask
  final static long CHECK_PERIOD = 40*1000; // 40 seconds
  final static int MAX_UPLOADERS = 6;

  // Approximation of the number of current uploaders.
  // Resynced by PeerChecker once in a while.
  int uploaders = 0;
  int interestedAndChoking = 0;

  // final static int MAX_DOWNLOADERS = MAX_CONNECTIONS;
  // int downloaders = 0;

  private long uploaded;
  private long downloaded;
  final static int RATE_DEPTH = 3; // make following arrays RATE_DEPTH long
  private long uploaded_old[] = {-1,-1,-1};
  private long downloaded_old[] = {-1,-1,-1};

  // synchronize on this when changing peers or downloaders
  // This is a Queue, not a Set, because PeerCheckerTask keeps things in order for choking/unchoking
  final Queue<Peer> peers;
  /** estimate of the peers, without requiring any synchronization */
  volatile int peerCount;

  /** Timer to handle all periodical tasks. */
  private final Timer timer = new Timer(true);

  private final byte[] id;

  /** The wanted pieces. We could use a TreeSet but we'd have to clear and re-add everything
   *  when priorities change.
   */
  private final List<Piece> wantedPieces;

  /** partial pieces - lock by synching on wantedPieces */
  private final List<PartialPiece> partialPieces;

  private boolean halted = false;

  private final CoordinatorListener listener;
  public I2PSnarkUtil _util;
  private static final Random _random = I2PAppContext.getGlobalContext().random();
  
  public String trackerProblems = null;
  public int trackerSeenPeers = 0;

  public PeerCoordinator(I2PSnarkUtil util, byte[] id, MetaInfo metainfo, Storage storage,
                         CoordinatorListener listener, Snark torrent)
  {
    _util = util;
    this.id = id;
    this.metainfo = metainfo;
    this.storage = storage;
    this.listener = listener;
    this.snark = torrent;

    wantedPieces = new ArrayList();
    setWantedPieces();
    partialPieces = new ArrayList(getMaxConnections() + 1);
    peers = new LinkedBlockingQueue();

    // Install a timer to check the uploaders.
    // Randomize the first start time so multiple tasks are spread out,
    // this will help the behavior with global limits
    timer.schedule(new PeerCheckerTask(_util, this), (CHECK_PERIOD / 2) + _random.nextInt((int) CHECK_PERIOD), CHECK_PERIOD);
  }
  
  // only called externally from Storage after the double-check fails
  public void setWantedPieces()
  {
    // Make a list of pieces
      synchronized(wantedPieces) {
          wantedPieces.clear();
          BitField bitfield = storage.getBitField();
          int[] pri = storage.getPiecePriorities();
          for (int i = 0; i < metainfo.getPieces(); i++) {
              // only add if we don't have and the priority is >= 0
              if ((!bitfield.get(i)) &&
                  (pri == null || pri[i] >= 0)) {
                  Piece p = new Piece(i);
                  if (pri != null)
                      p.setPriority(pri[i]);
                  wantedPieces.add(p);
              }
          }
          Collections.shuffle(wantedPieces, _random);
      }
  }

  public Storage getStorage() { return storage; }
  public CoordinatorListener getListener() { return listener; }

  // for web page detailed stats
  public List<Peer> peerList()
  {
        return new ArrayList(peers);
  }

  public byte[] getID()
  {
    return id;
  }

  public boolean completed()
  {
    return storage.complete();
  }

  /** might be wrong */
  public int getPeerCount() { return peerCount; }

  /** should be right */
  public int getPeers()
  {
        int rv = peers.size();
        peerCount = rv;
        return rv;
  }

  /**
   * Returns how many bytes are still needed to get the complete file.
   */
  public long getLeft()
  {
    // XXX - Only an approximation.
    return ((long) storage.needed()) * metainfo.getPieceLength(0);
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

  /**
   * Push the total uploaded/downloaded onto a RATE_DEPTH deep stack
   */
  public void setRateHistory(long up, long down)
  {
    setRate(up, uploaded_old);
    setRate(down, downloaded_old);
  }

  static void setRate(long val, long array[])
  {
    synchronized(array) {
      for (int i = RATE_DEPTH-1; i > 0; i--)
        array[i] = array[i-1];
      array[0] = val;
    }
  }

  /**
   * Returns the 4-minute-average rate in Bps
   */
  public long getDownloadRate()
  {
    return getRate(downloaded_old);
  }

  public long getUploadRate()
  {
    return getRate(uploaded_old);
  }

  public long getCurrentUploadRate()
  {
    // no need to synchronize, only one value
    long r = uploaded_old[0];
    if (r <= 0)
        return 0;
    return (r * 1000) / CHECK_PERIOD;
  }

  static long getRate(long array[])
  {
    long rate = 0;
    int i = 0;
    int factor = 0;
    synchronized(array) {
      for ( ; i < RATE_DEPTH; i++) {
        if (array[i] < 0)
            break;
        int f = RATE_DEPTH - i;
        rate += array[i] * f;
        factor += f;
      }
    }
    if (i == 0)
        return 0;
    return rate / (factor * CHECK_PERIOD / 1000);
  }

  public MetaInfo getMetaInfo()
  {
    return metainfo;
  }

  public boolean needPeers()
  {
        return !halted && peers.size() < getMaxConnections();
  }
  
  /**
   *  Reduce max if huge pieces to keep from ooming when leeching
   *  @return 512K: 16; 1M: 11; 2M: 6
   */
  private int getMaxConnections() {
    int size = metainfo.getPieceLength(0);
    int max = _util.getMaxConnections();
    if (size <= 512*1024 || completed())
      return max;
    if (size <= 1024*1024)
      return (max + max + 2) / 3;
    return (max + 2) / 3;
  }

  public boolean halted() { return halted; }

  public void halt()
  {
    halted = true;
    List<Peer> removed = new ArrayList();
    synchronized(peers)
      {
        // Stop peer checker task.
        timer.cancel();

        // Stop peers.
        removed.addAll(peers);
        peers.clear();
        peerCount = 0;
      }

    while (!removed.isEmpty()) {
        Peer peer = removed.remove(0);
        peer.disconnect();
        removePeerFromPieces(peer);
    }
    // delete any saved orphan partial piece
    synchronized (partialPieces) {
        partialPieces.clear();
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
        if ( (old != null) && (old.getInactiveTime() > 8*60*1000) ) {
            // idle for 8 minutes, kill the old con (32KB/8min = 68B/sec minimum for one block)
            if (_log.shouldLog(Log.WARN))
              _log.warn("Remomving old peer: " + peer + ": " + old + ", inactive for " + old.getInactiveTime());
            peers.remove(old);
            toDisconnect = old;
            old = null;
        }
        if (old != null)
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Already connected to: " + peer + ": " + old + ", inactive for " + old.getInactiveTime());
            // toDisconnect = peer to get out of synchronized(peers)
            peer.disconnect(false); // Don't deregister this connection/peer.
          }
        // This is already checked in addPeer() but we could have gone over the limit since then
        else if (peers.size() >= getMaxConnections())
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Already at MAX_CONNECTIONS in connected() with peer: " + peer);
            // toDisconnect = peer to get out of synchronized(peers)
            peer.disconnect(false);
          }
        else
          {
            if (_log.shouldLog(Log.INFO))
              _log.info("New connection to peer: " + peer + " for " + metainfo.getName());

            // Add it to the beginning of the list.
            // And try to optimistically make it a uploader.
            // Can't add to beginning since we converted from a List to a Queue
            // We can do this in Java 6 with a Deque
            //peers.add(0, peer);
            peers.add(peer);
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

  /**
   * @return peer if peer id  is in the collection, else null
   */
  private static Peer peerIDInList(PeerID pid, Collection<Peer> peers)
  {
    Iterator<Peer> it = peers.iterator();
    while (it.hasNext()) {
      Peer cur = it.next();
      if (pid.sameID(cur.getPeerID()))
        return cur;
    }
    return null;
  }

// returns true if actual attempt to add peer occurs
  public boolean addPeer(final Peer peer)
  {
    if (halted)
      {
        peer.disconnect(false);
        return false;
      }

    boolean need_more;
    int peersize = 0;
    synchronized(peers)
      {
        peersize = peers.size();
        // This isn't a strict limit, as we may have several pending connections;
        // thus there is an additional check in connected()
        need_more = (!peer.isConnected()) && peersize < getMaxConnections();
        // Check if we already have this peer before we build the connection
        Peer old = peerIDInList(peer.getPeerID(), peers);
        need_more = need_more && ((old == null) || (old.getInactiveTime() > 8*60*1000));
      }

    if (need_more)
      {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Adding a peer " + peer.getPeerID().toString() + " for " + metainfo.getName(), new Exception("add/run"));

        // Run the peer with us as listener and the current bitfield.
        final PeerListener listener = this;
        final BitField bitfield = storage.getBitField();
        Runnable r = new Runnable()
          {
            public void run()
            {
              peer.runConnection(_util, listener, bitfield);
            }
          };
        String threadName = peer.toString();
        new I2PAppThread(r, threadName).start();
        return true;
      }
    if (_log.shouldLog(Log.DEBUG)) {
      if (peer.isConnected())
        _log.info("Add peer already connected: " + peer);
      else
        _log.info("Connections: " + peersize + "/" + getMaxConnections()
                  + " not accepting extra peer: " + peer);
    }
    return false;
  }


  // (Optimistically) unchoke. Should be called with peers synchronized
  void unchokePeer()
  {
    // linked list will contain all interested peers that we choke.
    // At the start are the peers that have us unchoked at the end the
    // other peer that are interested, but are choking us.
    List<Peer> interested = new LinkedList();
        int count = 0;
        int unchokedCount = 0;
        int maxUploaders = allowedUploaders();
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext())
          {
            Peer peer = it.next();
            if (peer.isChoking() && peer.isInterested())
              {
                count++;
                if (uploaders < maxUploaders)
                  {
                    if (peer.isInteresting() && !peer.isChoked())
                      interested.add(unchokedCount++, peer);
                    else
                      interested.add(peer);
                  }
              }
          }

        while (uploaders < maxUploaders && !interested.isEmpty())
          {
            Peer peer = interested.remove(0);
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Unchoke: " + peer);
            peer.setChoking(false);
            uploaders++;
            count--;
            // Put peer back at the end of the list.
            peers.remove(peer);
            peers.add(peer);
            peerCount = peers.size();
          }
        interestedAndChoking = count;
  }

  public byte[] getBitMap()
  {
    return storage.getBitField().getFieldBytes();
  }

  /**
   * @return true if we still want the given piece
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
        Iterator<Piece> it = wantedPieces.iterator();
        while (it.hasNext())
          {
            Piece p = it.next();
            int i = p.getId();
            if (bitfield.get(i)) {
              p.addPeer(peer);
              return true;
            }
          }
      }
    return false;
  }

  /**
   *  This should be somewhat less than the max conns per torrent,
   *  but not too much less, so a torrent doesn't get stuck near the end.
   *  @since 0.7.14
   */
  private static final int END_GAME_THRESHOLD = 8;

  /**
   *  Max number of peers to get a piece from when in end game
   *  @since 0.8.1
   */
  private static final int MAX_PARALLEL_REQUESTS = 4;

  /**
   * Returns one of pieces in the given BitField that is still wanted or
   * -1 if none of the given pieces are wanted.
   */
  public int wantPiece(Peer peer, BitField havePieces) {
      return wantPiece(peer, havePieces, true);
  }

  /**
   * Returns one of pieces in the given BitField that is still wanted or
   * -1 if none of the given pieces are wanted.
   *
   * @param record if true, actually record in our data structures that we gave the
   *               request to this peer. If false, do not update the data structures.
   * @since 0.8.2
   */
  private int wantPiece(Peer peer, BitField havePieces, boolean record) {
    if (halted) {
      if (_log.shouldLog(Log.WARN))
          _log.warn("We don't want anything from the peer, as we are halted!  peer=" + peer);
      return -1;
    }

    synchronized(wantedPieces)
      {
        Piece piece = null;
        if (record)
            Collections.sort(wantedPieces); // Sort in order of rarest first.
        List<Piece> requested = new ArrayList(); 
        Iterator<Piece> it = wantedPieces.iterator();
        while (piece == null && it.hasNext())
          {
            Piece p = it.next();
            // sorted by priority, so when we hit a disabled piece we are done
            if (p.isDisabled())
                break;
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
            // AND if there are almost no wanted pieces left (real end game).
            // If we do end game all the time, we generate lots of extra traffic
            // when the seeder is super-slow and all the peers are "caught up"
            if (wantedPieces.size() > END_GAME_THRESHOLD)
                return -1;  // nothing to request and not in end game
            // let's not all get on the same piece
            // Even better would be to sort by number of requests
            if (record)
                Collections.shuffle(requested, _random);
            Iterator<Piece> it2 = requested.iterator();
            while (piece == null && it2.hasNext())
              {
                Piece p = it2.next();
                if (havePieces.get(p.getId())) {
                    // limit number of parallel requests
                    int requestedCount = 0;
                        for (Peer pr : peers) {
                            if (pr.isRequesting(p.getId())) {
                                if (pr.equals(peer)) {
                                    // don't give it to him again
                                    requestedCount = MAX_PARALLEL_REQUESTS;
                                    break;
                                }
                                if (++requestedCount >= MAX_PARALLEL_REQUESTS)
                                    break;
                            }
                        }
                    if (requestedCount >= MAX_PARALLEL_REQUESTS)
                        continue;
                    piece = p;
                }
              }
            if (piece == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("nothing to even rerequest from " + peer + ": requested = " + requested);
                //  _log.warn("nothing to even rerequest from " + peer + ": requested = " + requested 
                //            + " wanted = " + wantedPieces + " peerHas = " + havePieces);
                return -1; //If we still can't find a piece we want, so be it.
            } else {
                // Should be a lot smarter here -
                // share blocks rather than starting from 0 with each peer.
                // This is where the flaws of the snark data model are really exposed.
                // Could also randomize within the duplicate set rather than strict rarest-first
                if (_log.shouldLog(Log.INFO))
                    _log.info("parallel request (end game?) for " + peer + ": piece = " + piece);
            }
        }
        if (record) {
            if (_log.shouldLog(Log.INFO))
                _log.info(peer + " is now requesting: piece " + piece + " priority " + piece.getPriority());
            piece.setRequested(true);
        }
        return piece.getId();
      }
  }

  /**
   *  Maps file priorities to piece priorities.
   *  Call after updating file priorities Storage.setPriority()
   *  @since 0.8.1
   */
  public void updatePiecePriorities() {
      int[] pri = storage.getPiecePriorities();
      if (pri == null) {
          _log.debug("Updated piece priorities called but no priorities to set?");
          return;
      }
      synchronized(wantedPieces) {
          // Add incomplete and previously unwanted pieces to the list
          // Temp to avoid O(n**2)
          BitField want = new BitField(pri.length);
          for (Piece p : wantedPieces) {
              want.set(p.getId());
          }
          BitField bitfield = storage.getBitField();
          for (int i = 0; i < pri.length; i++) {
              if (pri[i] >= 0 && !bitfield.get(i)) {
                  if (!want.get(i)) {
                      Piece piece = new Piece(i);
                      wantedPieces.add(piece);
                      // As connections are already up, new Pieces will
                      // not have their PeerID list populated, so do that.
                          for (Peer p : peers) {
                              PeerState s = p.state;
                              if (s != null) {
                                  BitField bf = s.bitfield;
                                  if (bf != null && bf.get(i))
                                      piece.addPeer(p);
                              }
                          }
                  }
              }
          }
          // now set the new priorities and remove newly unwanted pieces
          for (Iterator<Piece> iter = wantedPieces.iterator(); iter.hasNext(); ) {
               Piece p = iter.next();
               int priority = pri[p.getId()];
               if (priority >= 0) {
                   p.setPriority(priority);
               } else {
                   iter.remove();
                   // cancel all peers
                       for (Peer peer : peers) {
                           peer.cancel(p.getId());
                       }
               }
          }
          if (_log.shouldLog(Log.DEBUG))
              _log.debug("Updated piece priorities, now wanted: " + wantedPieces);
          // if we added pieces, they will be in-order unless we shuffle
          Collections.shuffle(wantedPieces, _random);

          // update request queues, in case we added wanted pieces
          // and we were previously uninterested
              for (Peer peer : peers) {
                  peer.request();
          }
      }
  }

  /**
   * Returns a byte array containing the requested piece or null of
   * the piece is unknown.
   */
  public byte[] gotRequest(Peer peer, int piece, int off, int len)
  {
    if (halted)
      return null;

    try
      {
        return storage.getPiece(piece, off, len);
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
            // Well, this could be caused by a change in priorities, so
            // only return true if we already have it, otherwise might as well keep it.
            if (storage.getBitField().get(piece))
                return true;
          }
        
        try
          {
            if (storage.putPiece(piece, bs))
              {
                if (_log.shouldLog(Log.INFO))
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

    // just in case
    removePartialPiece(piece);

    // Announce to the world we have it!
    // Disconnect from other seeders when we get the last piece
        List<Peer> toDisconnect = new ArrayList(); 
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext())
          {
            Peer p = it.next();
            if (p.isConnected())
              {
                  if (completed() && p.isCompleted())
                      toDisconnect.add(p);
                  else
                      p.have(piece);
              }
          }
        it = toDisconnect.iterator();
        while (it.hasNext())
          {
            Peer p = it.next();
            p.disconnect(true);
          }
    
    return true;
  }

  /** this does nothing but logging */
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
            if (uploaders < allowedUploaders())
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
          for(Iterator<Piece> iter = wantedPieces.iterator(); iter.hasNext(); ) {
              Piece piece = iter.next();
              piece.removePeer(peer);
          }
      } 
  }

  /**
   *  Save partial pieces on peer disconnection
   *  and hopefully restart it later.
   *  Replace a partial piece in the List if the new one is bigger.
   *  Storage method is private so we can expand to save multiple partials
   *  if we wish.
   *
   *  Also mark the piece unrequested if this peer was the only one.
   *
   *  @param peer partials, must include the zero-offset (empty) ones too
   *  @since 0.8.2
   */
  public void savePartialPieces(Peer peer, List<PartialPiece> partials)
  {
      if (halted)
          return;
      if (_log.shouldLog(Log.INFO))
          _log.info("Partials received from " + peer + ": " + partials);
      synchronized(wantedPieces) {
          for (PartialPiece pp : partials) {
              if (pp.getDownloaded() > 0) {
                  // PartialPiece.equals() only compares piece number, which is what we want
                  int idx = partialPieces.indexOf(pp);
                  if (idx < 0) {
                      partialPieces.add(pp);
                      if (_log.shouldLog(Log.INFO))
                          _log.info("Saving orphaned partial piece (new) " + pp);
                  } else if (idx >= 0 && pp.getDownloaded() > partialPieces.get(idx).getDownloaded()) {
                      // replace what's there now
                      partialPieces.set(idx, pp);
                      if (_log.shouldLog(Log.INFO))
                          _log.info("Saving orphaned partial piece (bigger) " + pp);
                  } else {
                      if (_log.shouldLog(Log.INFO))
                          _log.info("Discarding partial piece (not bigger)" + pp);
                  }
                  int max = getMaxConnections();
                  if (partialPieces.size() > max) {
                      // sorts by remaining bytes, least first
                      Collections.sort(partialPieces);
                      PartialPiece gone = partialPieces.remove(max);
                      if (_log.shouldLog(Log.INFO))
                          _log.info("Discarding orphaned partial piece (list full)" + gone);
                  }
              }  // else drop the empty partial piece
              // synchs on wantedPieces...
              markUnrequestedIfOnlyOne(peer, pp.getPiece());
          }
          if (_log.shouldLog(Log.INFO))
              _log.info("Partial list size now: " + partialPieces.size());
      }
  }

  /**
   *  Return partial piece to the PeerState if it's still wanted and peer has it.
   *  @param havePieces pieces the peer has, the rv will be one of these
   *
   *  @return PartialPiece or null
   *  @since 0.8.2
   */
  public PartialPiece getPartialPiece(Peer peer, BitField havePieces) {
      synchronized(wantedPieces) {
          // sorts by remaining bytes, least first
          Collections.sort(partialPieces);
          for (Iterator<PartialPiece> iter = partialPieces.iterator(); iter.hasNext(); ) {
              PartialPiece pp = iter.next();
              int savedPiece = pp.getPiece();
              if (havePieces.get(savedPiece)) {
                 iter.remove();
                 // this is just a double-check, it should be in there
                 for(Piece piece : wantedPieces) {
                     if (piece.getId() == savedPiece) {
                         piece.setRequested(true);
                         if (_log.shouldLog(Log.INFO)) {
                             _log.info("Restoring orphaned partial piece " + pp +
                                       " Partial list size now: " + partialPieces.size());
                         }
                         return pp;
                      }
                  }
                  if (_log.shouldLog(Log.WARN))
                      _log.warn("Partial piece " + pp + " NOT in wantedPieces??");
              }
          }
          if (_log.shouldLog(Log.WARN) && !partialPieces.isEmpty())
              _log.warn("Peer " + peer + " has none of our partials " + partialPieces);
      }
      // ...and this section turns this into the general move-requests-around code!
      // Temporary? So PeerState never calls wantPiece() directly for now...
      int piece = wantPiece(peer, havePieces);
      if (piece >= 0) {
          try {
              return new PartialPiece(piece, metainfo.getPieceLength(piece));
          } catch (OutOfMemoryError oom) {
              if (_log.shouldLog(Log.WARN))
                  _log.warn("OOM creating new partial piece");
          }
      }
      if (_log.shouldLog(Log.DEBUG))
          _log.debug("We have no partial piece to return");
      return null;
  }

  /**
   * Called when we are downloading from the peer and may need to ask for
   * a new piece. Returns true if wantPiece() or getPartialPiece() would return a piece.
   *
   * @param peer the Peer that will be asked to provide the piece.
   * @param havePieces a BitField containing the pieces that the other
   * side has.
   *
   * @return if we want any of what the peer has
   * @since 0.8.2
   */
  public boolean needPiece(Peer peer, BitField havePieces) {
      synchronized(wantedPieces) {
          for (PartialPiece pp : partialPieces) {
              int savedPiece = pp.getPiece();
              if (havePieces.get(savedPiece)) {
                 // this is just a double-check, it should be in there
                 for(Piece piece : wantedPieces) {
                     if (piece.getId() == savedPiece) {
                         if (_log.shouldLog(Log.INFO)) {
                             _log.info("We could restore orphaned partial piece " + pp);
                         }
                         return true;
                      }
                  }
              }
          }
      }
      return wantPiece(peer, havePieces, false) >= 0;
  }

  /**
   *  Remove saved state for this piece.
   *  Unless we are in the end game there shouldnt be anything in there.
   *  Do not call with wantedPieces lock held (deadlock)
   */
  private void removePartialPiece(int piece) {
      synchronized(wantedPieces) {
          for (Iterator<PartialPiece> iter = partialPieces.iterator(); iter.hasNext(); ) {
              PartialPiece pp = iter.next();
              if (pp.getPiece() == piece) {
                  iter.remove();
                  // there should be only one but keep going to be sure
              }
          }
      }
  }

  /** Clear the requested flag for a piece if the peer
   ** is the only one requesting it
   */
  private void markUnrequestedIfOnlyOne(Peer peer, int piece)
  {
    // see if anybody else is requesting
        for (Peer p : peers) {
          if (p.equals(peer))
            continue;
          if (p.state == null)
            continue;
          // FIXME don't go into the state
          if (p.state.getRequestedPieces().contains(Integer.valueOf(piece))) {
              if (_log.shouldLog(Log.DEBUG))
                _log.debug("Another peer is requesting piece " + piece);
              return;
          }
        }

    // nobody is, so mark unrequested
    synchronized(wantedPieces)
      {
        for (Piece pc : wantedPieces) {
          if (pc.getId() == piece) {
            pc.setRequested(false);
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Removing from request list piece " + piece);
            return;
          }
        }
      }
  }

  /** Return number of allowed uploaders for this torrent.
   ** Check with Snark to see if we are over the total upload limit.
   */
  public int allowedUploaders()
  {
    if (listener != null && listener.overUploadLimit(uploaders)) {
        // if (_log.shouldLog(Log.DEBUG))
        //   _log.debug("Over limit, uploaders was: " + uploaders);
        return uploaders - 1;
    } else if (uploaders < MAX_UPLOADERS)
        return uploaders + 1;
    else
        return MAX_UPLOADERS;
  }

  public boolean overUpBWLimit()
  {
    if (listener != null)
        return listener.overUpBWLimit();
    return false;
  }

  public boolean overUpBWLimit(long total)
  {
    if (listener != null)
        return listener.overUpBWLimit(total * 1000 / CHECK_PERIOD);
    return false;
  }
}

