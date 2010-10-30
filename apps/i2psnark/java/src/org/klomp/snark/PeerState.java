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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

class PeerState
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerState.class);
  final Peer peer;
  final PeerListener listener;
  final MetaInfo metainfo;

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

  BitField bitfield;

  // Package local for use by Peer.
  final PeerConnectionIn in;
  final PeerConnectionOut out;

  // Outstanding request
  private final List<Request> outstandingRequests = new ArrayList();
  /** the tail (NOT the head) of the request queue */
  private Request lastRequest = null;

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

    byte[] pieceBytes = listener.gotRequest(peer, piece, begin, length);
    if (pieceBytes == null)
      {
        // XXX - Protocol error-> diconnect?
        if (_log.shouldLog(Log.WARN))
          _log.warn("Got request for unknown piece: " + piece);
        return;
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
        return;
      }

    if (_log.shouldLog(Log.INFO))
      _log.info("Sending (" + piece + ", " + begin + ", "
                + length + ")" + " to " + peer);
    out.sendPiece(piece, begin, length, pieceBytes);
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
  Request pendingRequest = null;

  /**
   * Called when a partial piece request has been handled by
   * PeerConnectionIn.
   */
  void pieceMessage(Request req)
  {
    int size = req.len;
    downloaded += size;
    listener.downloaded(peer, size);

    pendingRequest = null;

    // Last chunk needed for this piece?
    if (getFirstOutstandingRequest(req.piece) == -1)
      {
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
            downloaded = 0;
          }
      }
  }

  synchronized private int getFirstOutstandingRequest(int piece)
  {
    for (int i = 0; i < outstandingRequests.size(); i++)
      if (outstandingRequests.get(i).piece == piece)
        return i;
    return -1;
  }

  /**
   * Called when a piece message is being processed by the incoming
   * connection. Returns null when there was no such request. It also
   * requeues/sends requests when it thinks that they must have been
   * lost.
   */
  Request getOutstandingRequest(int piece, int begin, int length)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug("getChunk("
                  + piece + "," + begin + "," + length + ") "
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

    pendingRequest = req;
    return req;

  }

  // get longest partial piece
  Request getPartialRequest()
  {
    Request req = null;
    for (int i = 0; i < outstandingRequests.size(); i++) {
      Request r1 = outstandingRequests.get(i);
      int j = getFirstOutstandingRequest(r1.piece);
      if (j == -1)
        continue;
      Request r2 = outstandingRequests.get(j);
      if (r2.off > 0 && ((req == null) || (r2.off > req.off)))
        req = r2;
    }
    if (pendingRequest != null && req != null && pendingRequest.off < req.off) {
      if (pendingRequest.off != 0)
        req = pendingRequest;
      else
        req = null;
    }
    return req;
  }

  // return array of pieces terminated by -1
  // remove most duplicates
  // but still could be some duplicates, not guaranteed
  int[] getRequestedPieces()
  {
    int size = outstandingRequests.size();
    int[] arr = new int[size+2];
    int pc = -1;
    int pos = 0;
    if (pendingRequest != null) {
      pc = pendingRequest.piece;
      arr[pos++] = pc;
    }
    Request req = null;
    for (int i = 0; i < size; i++) {
      Request r1 = outstandingRequests.get(i);
      if (pc != r1.piece) {
        pc = r1.piece;
        arr[pos++] = pc;
      }
    }
    arr[pos] = -1;
    return(arr);
  }

  void cancelMessage(int piece, int begin, int length)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug("Got cancel message ("
                  + piece + ", " + begin + ", " + length + ")");
    out.cancelRequest(piece, begin, length);
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
    
    synchronized(this)
      {
        // Is the peer still interesting?
        if (lastRequest == null)
          setInteresting(false);
      }
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
   * Starts or resumes requesting pieces.
   * @param resend should we resend outstanding requests?
   */
  private void request(boolean resend)
  {
    // Are there outstanding requests that have to be resend?
    if (resend)
      {
        synchronized (this) {
            out.sendRequests(outstandingRequests);
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
   */
  synchronized void addRequest()
  {
    boolean more_pieces = true;
    while (more_pieces)
      {
        more_pieces = outstandingRequests.size() < MAX_PIPELINE;
        // We want something and we don't have outstanding requests?
        if (more_pieces && lastRequest == null)
          more_pieces = requestNextPiece();
        else if (more_pieces) // We want something
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
    if (bitfield != null)
      {
        // Check for adopting an orphaned partial piece
        Request r = listener.getPeerPartial(bitfield);
        if (r != null) {
              // Check that r not already in outstandingRequests
              int[] arr = getRequestedPieces();
              boolean found = false;
              for (int i = 0; arr[i] >= 0; i++) {
                if (arr[i] == r.piece) {
                  found = true;
                  break;
                }
              }
              if (!found) {
                outstandingRequests.add(r);
                if (!choked)
                  out.sendRequest(r);
                lastRequest = r;
                return true;
              }
        }
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
    if (_log.shouldLog(Log.DEBUG))
      _log.debug(peer + " setInteresting(" + interest + ")");

    if (interest != interesting)
      {
        interesting = interest;
        out.sendInterest(interest);

        if (interesting && !choked)
          request(true);  // we shouldnt have any pending requests, but if we do, resend them
      }
  }

  synchronized void setChoking(boolean choke)
  {
    if (_log.shouldLog(Log.DEBUG))
      _log.debug(peer + " setChoking(" + choke + ")");

    if (choking != choke)
      {
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
