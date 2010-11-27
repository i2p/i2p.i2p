/* PeerState - Keeps track of the Peer state through connection callbacks.
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;

class PeerState implements DataLoader
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerState.class);
  private final Peer peer;
  final PeerListener listener;
  private final MetaInfo metainfo;

  // Interesting and choking describes whether we are interested in or
  // are choking the other side.
  boolean interesting = false;
  boolean choking = true;

  // Interested and choked describes whether the other side is
  // interested in us or choked us.
  boolean interested = false;
  boolean choked = true;

  // Package local for use by Peer.
  long downloaded;
  long uploaded;

  /** the pieces the peer has */
  BitField bitfield;

  // Package local for use by Peer.
  final PeerConnectionIn in;
  final PeerConnectionOut out;

  // Outstanding request
  private final List<Request> outstandingRequests = new ArrayList();
  /** the tail (NOT the head) of the request queue */
  private Request lastRequest = null;

  // FIXME if piece size < PARTSIZE, pipeline could be bigger
  private final static int MAX_PIPELINE = 5;               // this is for outbound requests
  private final static int MAX_PIPELINE_BYTES = 128*1024;  // this is for inbound requests
  public final static int PARTSIZE = 16*1024; // outbound request
  private final static int MAX_PARTSIZE = 64*1024; // Don't let anybody request more than this

  PeerState(Peer peer, PeerListener listener, MetaInfo metainfo,
            PeerConnectionIn in, PeerConnectionOut out)
  {
    this.peer = peer;
    this.listener = listener;
    this.metainfo = metainfo;

    this.in = in;
    this.out = out;
  }

  // NOTE Methods that inspect or change the state synchronize (on this).

  void keepAliveMessage()
  {
    if (_log.shouldLog(Log.DEBUG))
        _log.debug(peer + " rcv alive");
    /* XXX - ignored */
  }

  void chokeMessage(boolean choke)
  {
    if (_log.shouldLog(Log.DEBUG))
        _log.debug(peer + " rcv " + (choke ? "" : "un") + "choked");

    boolean resend = choked && !choke;
    choked = choke;

    listener.gotChoke(peer, choke);

    if (interesting && !choked)
      request(resend);

    if (choked) {
        out.cancelRequestMessages();
        // old Roberts thrash us here, choke+unchoke right together
        List<PartialPiece> pcs = returnPartialPieces();
        if (!pcs.isEmpty()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(peer + " got choked, returning partial pieces to the PeerCoordinator: " + pcs);
            listener.savePartialPieces(this.peer, pcs);
        }
    }
  }

  void interestedMessage(boolean interest)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug(peer + " rcv " + (interest ? "" : "un")
                 + "interested");
    interested = interest;
    listener.gotInterest(peer, interest);
  }

  void haveMessage(int piece)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug(peer + " rcv have(" + piece + ")");
    // Sanity check
    if (piece < 0 || piece >= metainfo.getPieces())
      {
        // XXX disconnect?
        if (_log.shouldLog(Log.WARN))
            _log.warn("Got strange 'have: " + piece + "' message from " + peer);
        return;
      }

    synchronized(this)
      {
        // Can happen if the other side never send a bitfield message.
        if (bitfield == null)
          bitfield = new BitField(metainfo.getPieces());

        bitfield.set(piece);
      }

    if (listener.gotHave(peer, piece))
      setInteresting(true);
  }

  void bitfieldMessage(byte[] bitmap)
  {
    synchronized(this)
      {
        if (_log.shouldLog(Log.DEBUG))
          _log.debug(peer + " rcv bitfield");
        if (bitfield != null)
          {
            // XXX - Be liberal in what you except?
            if (_log.shouldLog(Log.WARN))
              _log.warn("Got unexpected bitfield message from " + peer);
            return;
          }
        
        // XXX - Check for weird bitfield and disconnect?
        bitfield = new BitField(bitmap, metainfo.getPieces());
      }
    boolean interest = listener.gotBitField(peer, bitfield);
    setInteresting(interest);
    if (bitfield.complete() && !interest) {
        // They are seeding and we are seeding,
        // why did they contact us? (robert)
        // Dump them quick before we send our whole bitmap
        if (_log.shouldLog(Log.WARN))
            _log.warn("Disconnecting seed that connects to seeds: " + peer);
        peer.disconnect(true);
    }
  }

  void requestMessage(int piece, int begin, int length)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug(peer + " rcv request("
                  + piece + ", " + begin + ", " + length + ") ");
    if (choking)
      {
        if (_log.shouldLog(Log.INFO))
          _log.info("Request received, but choking " + peer);
        return;
      }

    // Sanity check
    if (piece < 0
        || piece >= metainfo.getPieces()
        || begin < 0
        || begin > metainfo.getPieceLength(piece)
        || length <= 0
        || length > MAX_PARTSIZE)
      {
        // XXX - Protocol error -> disconnect?
        if (_log.shouldLog(Log.WARN))
          _log.warn("Got strange 'request: " + piece
                      + ", " + begin
                      + ", " + length
                      + "' message from " + peer);
        return;
      }

    // Limit total pipelined requests to MAX_PIPELINE bytes
    // to conserve memory and prevent DOS
    // Todo: limit number of requests also? (robert 64 x 4KB)
    if (out.queuedBytes() + length > MAX_PIPELINE_BYTES)
      {
        if (_log.shouldLog(Log.WARN))
          _log.warn("Discarding request over pipeline limit from " + peer);
        return;
      }

    if (_log.shouldLog(Log.INFO))
      _log.info("Queueing (" + piece + ", " + begin + ", "
                + length + ")" + " to " + peer);

    // don't load the data into mem now, let PeerConnectionOut do it
    out.sendPiece(piece, begin, length, this);
  }

  /**
   *  This is the callback that PeerConnectionOut calls
   *
   *  @return bytes or null for errors
   *  @since 0.8.2
   */
  public byte[] loadData(int piece, int begin, int length) {
    byte[] pieceBytes = listener.gotRequest(peer, piece, begin, length);
    if (pieceBytes == null)
      {
        // XXX - Protocol error-> diconnect?
        if (_log.shouldLog(Log.WARN))
          _log.warn("Got request for unknown piece: " + piece);
        return null;
      }

    // More sanity checks
    if (length != pieceBytes.length)
      {
        // XXX - Protocol error-> disconnect?
        if (_log.shouldLog(Log.WARN))
          _log.warn("Got out of range 'request: " + piece
                      + ", " + begin
                      + ", " + length
                      + "' message from " + peer);
        return null;
      }

    if (_log.shouldLog(Log.INFO))
      _log.info("Sending (" + piece + ", " + begin + ", "
                + length + ")" + " to " + peer);
    return pieceBytes;
  }

  /**
   * Called when some bytes have left the outgoing connection.
   * XXX - Should indicate whether it was a real piece or overhead.
   */
  void uploaded(int size)
  {
    uploaded += size;
    listener.uploaded(peer, size);
  }

  // This is used to flag that we have to back up from the firstOutstandingRequest
  // when calculating how far we've gotten
  private Request pendingRequest;

  /**
   * Called when a full chunk (i.e. a piece message) has been received by
   * PeerConnectionIn.
   *
   * This may block quite a while if it is the last chunk for a piece,
   * as it calls the listener, who stores the piece and then calls
   * havePiece for every peer on the torrent (including us).
   *
   */
  void pieceMessage(Request req)
  {
    int size = req.len;
    downloaded += size;
    listener.downloaded(peer, size);

    if (_log.shouldLog(Log.DEBUG))
      _log.debug("got end of Chunk("
                  + req.piece + "," + req.off + "," + req.len + ") from "
                  + peer);

    // Last chunk needed for this piece?
    if (getFirstOutstandingRequest(req.piece) == -1)
      {
        // warning - may block here for a while
        if (listener.gotPiece(peer, req.piece, req.bs))
          {
            if (_log.shouldLog(Log.DEBUG))
              _log.debug("Got " + req.piece + ": " + peer);
          }
        else
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Got BAD " + req.piece + " from " + peer);
            // XXX ARGH What now !?!
            // FIXME Why would we set downloaded to 0?
            downloaded = 0;
          }
      }

      // ok done with this one
      synchronized(this) {
          pendingRequest = null;
      }
  }

  /**
   *  @return index in outstandingRequests or -1
   */
  synchronized private int getFirstOutstandingRequest(int piece)
   {
    for (int i = 0; i < outstandingRequests.size(); i++)
      if (outstandingRequests.get(i).piece == piece)
        return i;
    return -1;
  }

  /**
   * Called when a piece message is being processed by the incoming
   * connection. That is, when the header of the piece message was received.
   * Returns null when there was no such request. It also
   * requeues/sends requests when it thinks that they must have been
   * lost.
   */
  Request getOutstandingRequest(int piece, int begin, int length)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug("got start of Chunk("
                  + piece + "," + begin + "," + length + ") from "
                  + peer);

    int r = getFirstOutstandingRequest(piece);

    // Unrequested piece number?
    if (r == -1)
      {
        if (_log.shouldLog(Log.INFO))
          _log.info("Unrequested 'piece: " + piece + ", "
                      + begin + ", " + length + "' received from "
                      + peer);
        downloaded = 0; // XXX - punishment?
        return null;
      }

    // Lookup the correct piece chunk request from the list.
    Request req;
    synchronized(this)
      {
        req = outstandingRequests.get(r);
        while (req.piece == piece && req.off != begin
               && r < outstandingRequests.size() - 1)
          {
            r++;
            req = outstandingRequests.get(r);
          }
        
        // Something wrong?
        if (req.piece != piece || req.off != begin || req.len != length)
          {
            if (_log.shouldLog(Log.INFO))
              _log.info("Unrequested or unneeded 'piece: "
                          + piece + ", "
                          + begin + ", "
                          + length + "' received from "
                          + peer);
            downloaded = 0; // XXX - punishment?
            return null;
          }

        // note that this request is being read
        pendingRequest = req;
        
        // Report missing requests.
        if (r != 0)
          {
            if (_log.shouldLog(Log.WARN))
              _log.warn("Some requests dropped, got " + req
                               + ", wanted for peer: " + peer);
            for (int i = 0; i < r; i++)
              {
                Request dropReq = outstandingRequests.remove(0);
                outstandingRequests.add(dropReq);
                if (!choked)
                  out.sendRequest(dropReq);
                if (_log.shouldLog(Log.WARN))
                  _log.warn("dropped " + dropReq + " with peer " + peer);
              }
          }
        outstandingRequests.remove(0);
      }

    // Request more if necessary to keep the pipeline filled.
    addRequest();

    return req;

  }

  /**
   *  @return lowest offset of any request for the piece
   *  @since 0.8.2
   */
  synchronized private Request getLowestOutstandingRequest(int piece) {
      Request rv = null;
      int lowest = Integer.MAX_VALUE;
      for (Request r :  outstandingRequests) {
          if (r.piece == piece && r.off < lowest) {
              lowest = r.off;
              rv = r;
          }
      }
      if (pendingRequest != null &&
          pendingRequest.piece == piece && pendingRequest.off < lowest)
          rv = pendingRequest;

      if (_log.shouldLog(Log.DEBUG))
          _log.debug(peer + " lowest for " + piece + " is " + rv + " out of " + pendingRequest + " and " + outstandingRequests);
      return rv;
  }

  /**
   *  Get partial pieces, give them back to PeerCoordinator.
   *  Clears the request queue.
   *  @return List of PartialPieces, even those with an offset == 0, or empty list
   *  @since 0.8.2
   */
  synchronized List<PartialPiece> returnPartialPieces()
  {
      Set<Integer> pcs = getRequestedPieces();
      List<PartialPiece> rv = new ArrayList(pcs.size());
      for (Integer p : pcs) {
          Request req = getLowestOutstandingRequest(p.intValue());
          if (req != null)
              rv.add(new PartialPiece(req));
      }
      outstandingRequests.clear();
      pendingRequest = null;
      lastRequest = null;
      return rv;
  }

  /**
   * @return all pieces we are currently requesting, or empty Set
   */
  synchronized Set<Integer> getRequestedPieces() {
      Set<Integer> rv = new HashSet(outstandingRequests.size() + 1);
      for (Request req : outstandingRequests) {
          rv.add(Integer.valueOf(req.piece));
      if (pendingRequest != null)
          rv.add(Integer.valueOf(pendingRequest.piece));
      }
      return rv;
  }

  void cancelMessage(int piece, int begin, int length)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug("Got cancel message ("
                  + piece + ", " + begin + ", " + length + ")");
    out.cancelRequest(piece, begin, length);
  }

  /** @since 0.8.2 */
  void extensionMessage(int id, byte[] bs)
  {
      if (id == 0) {
          InputStream is = new ByteArrayInputStream(bs);
          try {
              BDecoder dec = new BDecoder(is);
              BEValue bev = dec.bdecodeMap();
              Map map = bev.getMap();
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Got extension handshake message " + bev.toString());
          } catch (Exception e) {}
      } else {
          if (_log.shouldLog(Log.DEBUG))
              _log.debug("Got extended message type: " + id + " length: " + bs.length);
      }
  }

  void unknownMessage(int type, byte[] bs)
  {
    if (_log.shouldLog(Log.WARN))
      _log.warn("Warning: Ignoring unknown message type: " + type
                  + " length: " + bs.length);
  }

  /**
   *  We now have this piece.
   *  Tell the peer and cancel any requests for the piece.
   */
  void havePiece(int piece)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug("Tell " + peer + " havePiece(" + piece + ")");

        // Tell the other side that we are no longer interested in any of
        // the outstanding requests for this piece.
    cancelPiece(piece);

    // Tell the other side that we really have this piece.
    out.sendHave(piece);
    
    // Request something else if necessary.
    addRequest();
    
   /**** taken care of in addRequest()
    synchronized(this)
      {
        // Is the peer still interesting?
        if (lastRequest == null)
          setInteresting(false);
      }
    ****/
  }

  /**
   * Tell the other side that we are no longer interested in any of
   * the outstanding requests (if any) for this piece.
   * @since 0.8.1
   */
  synchronized void cancelPiece(int piece) {
        if (lastRequest != null && lastRequest.piece == piece)
          lastRequest = null;
        
        Iterator<Request> it = outstandingRequests.iterator();
        while (it.hasNext())
          {
            Request req = it.next();
            if (req.piece == piece)
              {
                it.remove();
                // Send cancel even when we are choked to make sure that it is
                // really never ever send.
                out.sendCancel(req);
              }
          }
  }

  /**
   * Are we currently requesting the piece?
   * @since 0.8.1
   */
  synchronized boolean isRequesting(int piece) {
      if (pendingRequest != null && pendingRequest.piece == piece)
          return true;
      for (Request req : outstandingRequests) {
          if (req.piece == piece)
              return true;
      }
      return false;
  }

  /**
   * Starts or resumes requesting pieces.
   * @param resend should we resend outstanding requests?
   */
  private void request(boolean resend)
  {
    // Are there outstanding requests that have to be resend?
    if (resend)
      {
        synchronized (this) {
            if (!outstandingRequests.isEmpty()) {
                out.sendRequests(outstandingRequests);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Resending requests to " + peer + outstandingRequests);
            }
        }
      }

    // Add/Send some more requests if necessary.
    addRequest();
  }

  /**
   * Adds a new request to the outstanding requests list.
   * Then send interested if we weren't.
   * Then send new requests if not choked.
   * If nothing to request, send not interested if we were.
   *
   * This is called from several places:
   *<pre>
   *   By getOustandingRequest() when the first part of a chunk comes in
   *   By havePiece() when somebody got a new piece completed
   *   By chokeMessage() when we receive an unchoke
   *   By setInteresting() when we are now interested
   *   By PeerCoordinator.updatePiecePriorities()
   *</pre>
   */
  synchronized void addRequest()
  {
    boolean more_pieces = true;
    while (more_pieces)
      {
        more_pieces = outstandingRequests.size() < MAX_PIPELINE;
        // We want something and we don't have outstanding requests?
        if (more_pieces && lastRequest == null) {
          // we have nothing in the queue right now
          if (!interesting) {
              // If we need something, set interesting but delay pulling
              // a request from the PeerCoordinator until unchoked.
              if (listener.needPiece(this.peer, bitfield)) {
                  setInteresting(true);
                  if (_log.shouldLog(Log.DEBUG))
                      _log.debug(peer + " addRequest() we need something, setting interesting, delaying requestNextPiece()");
              } else {
                  if (_log.shouldLog(Log.DEBUG))
                      _log.debug(peer + " addRequest() needs nothing");
              }
              return;
          }
          if (choked) {
              // If choked, delay pulling
              // a request from the PeerCoordinator until unchoked.
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug(peer + " addRequest() we are choked, delaying requestNextPiece()");
              return;
          }
          more_pieces = requestNextPiece();
        } else if (more_pieces) // We want something
          {
            int pieceLength;
            boolean isLastChunk;
            pieceLength = metainfo.getPieceLength(lastRequest.piece);
            isLastChunk = lastRequest.off + lastRequest.len == pieceLength;

            // Last part of a piece?
            if (isLastChunk)
              more_pieces = requestNextPiece();
            else
              {
                    int nextPiece = lastRequest.piece;
                    int nextBegin = lastRequest.off + PARTSIZE;
                    byte[] bs = lastRequest.bs;
                    int maxLength = pieceLength - nextBegin;
                    int nextLength = maxLength > PARTSIZE ? PARTSIZE
                                                          : maxLength;
                    Request req
                      = new Request(nextPiece, bs, nextBegin, nextLength);
                    outstandingRequests.add(req);
                    if (!choked)
                      out.sendRequest(req);
                    lastRequest = req;
              }
          }
      }

    // failsafe
    if (interesting && lastRequest == null && outstandingRequests.isEmpty())
        setInteresting(false);

    if (_log.shouldLog(Log.DEBUG))
      _log.debug(peer + " requests " + outstandingRequests);
  }

  /**
   * Starts requesting first chunk of next piece. Returns true if
   * something has been added to the requests, false otherwise.
   * Caller should synchronize.
   */
  private boolean requestNextPiece()
  {
    // Check that we already know what the other side has.
    if (bitfield != null) {
        // Check for adopting an orphaned partial piece
        PartialPiece pp = listener.getPartialPiece(peer, bitfield);
        if (pp != null) {
            // Double-check that r not already in outstandingRequests
            if (!getRequestedPieces().contains(Integer.valueOf(pp.getPiece()))) {
                Request r = pp.getRequest();
                outstandingRequests.add(r);
                if (!choked)
                  out.sendRequest(r);
                lastRequest = r;
                return true;
            }
        }

      /******* getPartialPiece() does it all now
        // Note that in addition to the bitfield, PeerCoordinator uses
        // its request tracking and isRequesting() to determine
        // what piece to give us next.
        int nextPiece = listener.wantPiece(peer, bitfield);
        if (nextPiece != -1
            && (lastRequest == null || lastRequest.piece != nextPiece)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(peer + " want piece " + nextPiece);
                // Fail safe to make sure we are interested
                // When we transition into the end game we may not be interested...
                if (!interesting) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(peer + " transition to end game, setting interesting");
                    interesting = true;
                    out.sendInterest(true);
                }

                int piece_length = metainfo.getPieceLength(nextPiece);
                //Catch a common place for OOMs esp. on 1MB pieces
                byte[] bs;
                try {
                  bs = new byte[piece_length];
                } catch (OutOfMemoryError oom) {
                  _log.warn("Out of memory, can't request piece " + nextPiece, oom);
                  return false;
                }
                
                int length = Math.min(piece_length, PARTSIZE);
                Request req = new Request(nextPiece, bs, 0, length);
                outstandingRequests.add(req);
                if (!choked)
                  out.sendRequest(req);
                lastRequest = req;
                return true;
        } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(peer + " no more pieces to request");
        }
     *******/
    }

    // failsafe
    if (outstandingRequests.isEmpty())
        lastRequest = null;

    // If we are not in the end game, we may run out of things to request
    // because we are asking other peers. Set not-interesting now rather than
    // wait for those other requests to be satisfied via havePiece()
    if (interesting && lastRequest == null) {
        interesting = false;
        out.sendInterest(false);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(peer + " nothing more to request, now uninteresting");
    }
    return false;
  }

  synchronized void setInteresting(boolean interest)
  {
    if (interest != interesting)
      {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(peer + " setInteresting(" + interest + ")");
        interesting = interest;
        out.sendInterest(interest);

        if (interesting && !choked)
          request(true);  // we shouldnt have any pending requests, but if we do, resend them
      }
  }

  synchronized void setChoking(boolean choke)
  {
    if (choking != choke)
      {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(peer + " setChoking(" + choke + ")");
        choking = choke;
        out.sendChoke(choke);
      }
  }

  void keepAlive()
  {
        out.sendAlive();
  }

  synchronized void retransmitRequests()
  {
      if (interesting && !choked)
        out.retransmitRequests(outstandingRequests);
  }

  /**
   *  debug
   *  @return string or null
   *  @since 0.8.1
   */
  synchronized String getRequests() {
      if (outstandingRequests.isEmpty())
          return null;
      else
          return outstandingRequests.toString();
  }
}
