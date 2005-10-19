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
import java.util.Set;
import java.util.HashSet;

class PeerState
{
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
  private final List outstandingRequests = new ArrayList();
  private Request lastRequest = null;

  // If we have te resend outstanding requests (true after we got choked).
  private boolean resend = false;

  private final static int MAX_PIPELINE = 5;
  private final static int PARTSIZE = 64*1024; // default was 16K, i2p-bt uses 64KB

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
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " rcv alive", Snark.DEBUG);
    /* XXX - ignored */
  }

  void chokeMessage(boolean choke)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " rcv " + (choke ? "" : "un") + "choked",
                  Snark.DEBUG);

    choked = choke;
    if (choked)
      resend = true;

    listener.gotChoke(peer, choke);

    if (!choked && interesting)
      request();
  }

  void interestedMessage(boolean interest)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " rcv " + (interest ? "" : "un")
                  + "interested", Snark.DEBUG);
    interested = interest;
    listener.gotInterest(peer, interest);
  }

  void haveMessage(int piece)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " rcv have(" + piece + ")", Snark.DEBUG);
    // Sanity check
    if (piece < 0 || piece >= metainfo.getPieces())
      {
        // XXX disconnect?
        if (Snark.debug >= Snark.INFO)
          Snark.debug("Got strange 'have: " + piece + "' message from " + peer,
                      + Snark.INFO);
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
        if (Snark.debug >= Snark.DEBUG)
          Snark.debug(peer + " rcv bitfield", Snark.DEBUG);
        if (bitfield != null)
          {
            // XXX - Be liberal in what you except?
            if (Snark.debug >= Snark.INFO)
              Snark.debug("Got unexpected bitfield message from " + peer,
                          Snark.INFO);
            return;
          }
        
        // XXX - Check for weird bitfield and disconnect?
        bitfield = new BitField(bitmap, metainfo.getPieces());
      }
    setInteresting(listener.gotBitField(peer, bitfield));
  }

  void requestMessage(int piece, int begin, int length)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " rcv request("
                  + piece + ", " + begin + ", " + length + ") ",
                  Snark.DEBUG);
    if (choking)
      {
        if (Snark.debug >= Snark.INFO)
          Snark.debug("Request received, but choking " + peer, Snark.INFO);
        return;
      }

    // Sanity check
    if (piece < 0
        || piece >= metainfo.getPieces()
        || begin < 0
        || begin > metainfo.getPieceLength(piece)
        || length <= 0
        || length > 4*PARTSIZE)
      {
        // XXX - Protocol error -> disconnect?
        if (Snark.debug >= Snark.INFO)
          Snark.debug("Got strange 'request: " + piece
                      + ", " + begin
                      + ", " + length
                      + "' message from " + peer,
                      Snark.INFO);
        return;
      }

    byte[] pieceBytes = listener.gotRequest(peer, piece);
    if (pieceBytes == null)
      {
        // XXX - Protocol error-> diconnect?
        if (Snark.debug >= Snark.INFO)
          Snark.debug("Got request for unknown piece: " + piece, Snark.INFO);
        return;
      }

    // More sanity checks
    if (begin >= pieceBytes.length || begin + length > pieceBytes.length)
      {
        // XXX - Protocol error-> disconnect?
        if (Snark.debug >= Snark.INFO)
          Snark.debug("Got out of range 'request: " + piece
                      + ", " + begin
                      + ", " + length
                      + "' message from " + peer,
                      Snark.INFO);
        return;
      }

    if (Snark.debug >= Snark.DEBUG)
      Snark.debug("Sending (" + piece + ", " + begin + ", "
                  + length + ")" + " to " + peer, Snark.DEBUG);
    out.sendPiece(piece, begin, length, pieceBytes);

    // Tell about last subpiece delivery.
    if (begin + length == pieceBytes.length)
      if (Snark.debug >= Snark.DEBUG)
        Snark.debug("Send p" + piece + " " + peer,
                    Snark.DEBUG);
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

  /**
   * Called when a partial piece request has been handled by
   * PeerConnectionIn.
   */
  void pieceMessage(Request req)
  {
    int size = req.len;
    downloaded += size;
    listener.downloaded(peer, size);

    // Last chunk needed for this piece?
    if (getFirstOutstandingRequest(req.piece) == -1)
      {
        if (listener.gotPiece(peer, req.piece, req.bs))
          {
            if (Snark.debug >= Snark.DEBUG)
              Snark.debug("Got " + req.piece + ": " + peer, Snark.DEBUG);
          }
        else
          {
            if (Snark.debug >= Snark.DEBUG)
              Snark.debug("Got BAD " + req.piece + " from " + peer,
                          Snark.DEBUG);
            // XXX ARGH What now !?!
            downloaded = 0;
          }
      }
  }

  synchronized private int getFirstOutstandingRequest(int piece)
  {
    for (int i = 0; i < outstandingRequests.size(); i++)
      if (((Request)outstandingRequests.get(i)).piece == piece)
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
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug("getChunk("
                  + piece + "," + begin + "," + length + ") "
                  + peer, Snark.DEBUG);

    int r = getFirstOutstandingRequest(piece);

    // Unrequested piece number?
    if (r == -1)
      {
        if (Snark.debug >= Snark.INFO)
          Snark.debug("Unrequested 'piece: " + piece + ", "
                      + begin + ", " + length + "' received from "
                      + peer,
                      Snark.INFO);
        downloaded = 0; // XXX - punishment?
        return null;
      }

    // Lookup the correct piece chunk request from the list.
    Request req;
    synchronized(this)
      {
        req = (Request)outstandingRequests.get(r);
        while (req.piece == piece && req.off != begin
               && r < outstandingRequests.size() - 1)
          {
            r++;
            req = (Request)outstandingRequests.get(r);
          }
        
        // Something wrong?
        if (req.piece != piece || req.off != begin || req.len != length)
          {
            if (Snark.debug >= Snark.INFO)
              Snark.debug("Unrequested or unneeded 'piece: "
                          + piece + ", "
                          + begin + ", "
                          + length + "' received from "
                          + peer,
                          Snark.INFO);
            downloaded = 0; // XXX - punishment?
            return null;
          }
        
        // Report missing requests.
        if (r != 0)
          {
            if (Snark.debug >= Snark.INFO)
              System.err.print("Some requests dropped, got " + req
                               + ", wanted:");
            for (int i = 0; i < r; i++)
              {
                Request dropReq = (Request)outstandingRequests.remove(0);
                outstandingRequests.add(dropReq);
                // We used to rerequest the missing chunks but that mostly
                // just confuses the other side. So now we just keep
                // waiting for them. They will be rerequested when we get
                // choked/unchoked again.
                /*
                  if (!choked)
                  out.sendRequest(dropReq);
                */
                if (Snark.debug >= Snark.INFO)
                  System.err.print(" " + dropReq);
              }
            if (Snark.debug >= Snark.INFO)
              System.err.println(" " + peer);
          }
        outstandingRequests.remove(0);
      }

    // Request more if necessary to keep the pipeline filled.
    addRequest();

    return req;

  }

  void cancelMessage(int piece, int begin, int length)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug("Got cancel message ("
                  + piece + ", " + begin + ", " + length + ")",
                  Snark.DEBUG);
    out.cancelRequest(piece, begin, length);
  }

  void unknownMessage(int type, byte[] bs)
  {
    if (Snark.debug >= Snark.WARNING)
      Snark.debug("Warning: Ignoring unknown message type: " + type
                  + " length: " + bs.length, Snark.WARNING);
  }

  void havePiece(int piece)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug("Tell " + peer + " havePiece(" + piece + ")", Snark.DEBUG);

    synchronized(this)
      {
        // Tell the other side that we are no longer interested in any of
        // the outstanding requests for this piece.
        if (lastRequest != null && lastRequest.piece == piece)
          lastRequest = null;
        
        Iterator it = outstandingRequests.iterator();
        while (it.hasNext())
          {
            Request req = (Request)it.next();
            if (req.piece == piece)
              {
                it.remove();
                // Send cancel even when we are choked to make sure that it is
                // really never ever send.
                out.sendCancel(req);
              }
          }
      }
    
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

  // Starts or resumes requesting pieces.
  private void request()
  {
    // Are there outstanding requests that have to be resend?
    if (resend)
      {
        out.sendRequests(outstandingRequests);
        resend = false;
      }

    // Add/Send some more requests if necessary.
    addRequest();
  }

  /**
   * Adds a new request to the outstanding requests list.
   */
  private void addRequest()
  {
    boolean more_pieces = true;
    while (more_pieces)
      {
        synchronized(this)
          {
            more_pieces = outstandingRequests.size() < MAX_PIPELINE;
          }
        
        // We want something and we don't have outstanding requests?
        if (more_pieces && lastRequest == null)
          more_pieces = requestNextPiece();
        else if (more_pieces) // We want something
          {
            int pieceLength;
            boolean isLastChunk;
            synchronized(this)
              {
                pieceLength = metainfo.getPieceLength(lastRequest.piece);
                isLastChunk = lastRequest.off + lastRequest.len == pieceLength;
              }

            // Last part of a piece?
            if (isLastChunk)
              more_pieces = requestNextPiece();
            else
              {
                synchronized(this)
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
      }

    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " requests " + outstandingRequests, Snark.DEBUG);
  }

  // Starts requesting first chunk of next piece. Returns true if
  // something has been added to the requests, false otherwise.
  private boolean requestNextPiece()
  {
    // Check that we already know what the other side has.
    if (bitfield != null)
      {
        int nextPiece = listener.wantPiece(peer, bitfield);
        if (Snark.debug >= Snark.DEBUG)
          Snark.debug(peer + " want piece " + nextPiece, Snark.DEBUG);
        synchronized(this)
          {
            if (nextPiece != -1
                && (lastRequest == null || lastRequest.piece != nextPiece))
              {
                int piece_length = metainfo.getPieceLength(nextPiece);
                byte[] bs = new byte[piece_length];
                
                int length = Math.min(piece_length, PARTSIZE);
                Request req = new Request(nextPiece, bs, 0, length);
                outstandingRequests.add(req);
                if (!choked)
                  out.sendRequest(req);
                lastRequest = req;
                return true;
              }
          }
      }

    return false;
  }

  synchronized void setInteresting(boolean interest)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " setInteresting(" + interest + ")", Snark.DEBUG);

    if (interest != interesting)
      {
        interesting = interest;
        out.sendInterest(interest);

        if (interesting && !choked)
          request();
      }
  }

  synchronized void setChoking(boolean choke)
  {
    if (Snark.debug >= Snark.DEBUG)
      Snark.debug(peer + " setChoking(" + choke + ")", Snark.DEBUG);

    if (choking != choke)
      {
        choking = choke;
        out.sendChoke(choke);
      }
  }
}
