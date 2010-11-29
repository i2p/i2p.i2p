/* PeerConnectionOut - Keeps a queue of outgoing messages and delivers them.
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

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

class PeerConnectionOut implements Runnable
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionOut.class);
  private final Peer peer;
  private final DataOutputStream dout;

  private Thread thread;
  private boolean quit;

  // Contains Messages.
  private final List<Message> sendQueue = new ArrayList();
  
  private static long __id = 0;
  private long _id;
  
  long lastSent;

  public PeerConnectionOut(Peer peer, DataOutputStream dout)
  {
    this.peer = peer;
    this.dout = dout;
    _id = ++__id;

    lastSent = System.currentTimeMillis();
    quit = false;
  }
  
  public void startup() {
    thread = new I2PAppThread(this, "Snark sender " + _id + ": " + peer);
    thread.start();
  }

  /**
   * Continuesly monitors for more outgoing messages that have to be send.
   * Stops if quit is true of an IOException occurs.
   */
  public void run()
  {
    try
      {
        while (!quit && peer.isConnected())
          {
            Message m = null;
            PeerState state = null;
            boolean shouldFlush;
            synchronized(sendQueue)
              {
                shouldFlush = !quit && peer.isConnected() && sendQueue.isEmpty();
              }
            if (shouldFlush)
                // Make sure everything will reach the other side.
                // flush while not holding lock, could take a long time
                dout.flush();

            synchronized(sendQueue)
              {
                while (!quit && peer.isConnected() && sendQueue.isEmpty())
                  {
                    try
                      {
                        // Make sure everything will reach the other side.
                        // don't flush while holding lock, could take a long time
                        // dout.flush();
                        
                        // Wait till more data arrives.
                        sendQueue.wait(60*1000);
                      }
                    catch (InterruptedException ie)
                      {
                        /* ignored */
                      }
                  }
                state = peer.state;
                if (!quit && state != null && peer.isConnected())
                  {
                    // Piece messages are big. So if there are other
                    // (control) messages make sure they are send first.
                    // Also remove request messages from the queue if
                    // we are currently being choked to prevent them from
                    // being send even if we get unchoked a little later.
                    // (Since we will resent them anyway in that case.)
                    // And remove piece messages if we are choking.
                    
                    // this should get fixed for starvation
                    Iterator it = sendQueue.iterator();
                    while (m == null && it.hasNext())
                      {
                        Message nm = (Message)it.next();
                        if (nm.type == Message.PIECE)
                          {
                            if (state.choking) {
                              it.remove();
                              SimpleTimer.getInstance().removeEvent(nm.expireEvent);
                            }
                            nm = null;
                          }
                        else if (nm.type == Message.REQUEST && state.choked)
                          {
                            it.remove();
                            SimpleTimer.getInstance().removeEvent(nm.expireEvent);
                            nm = null;
                          }
                          
                        if (m == null && nm != null)
                          {
                            m = nm;
                            SimpleTimer.getInstance().removeEvent(nm.expireEvent);
                            it.remove();
                          }
                      }
                    if (m == null && !sendQueue.isEmpty()) {
                      m = (Message)sendQueue.remove(0);
                      SimpleTimer.getInstance().removeEvent(m.expireEvent);
                    }
                  }
              }
            if (m != null)
              {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Send " + peer + ": " + m + " on " + peer.metainfo.getName());

                // This can block for quite a while.
                // To help get slow peers going, and track the bandwidth better,
                // move this _after_ state.uploaded() and see how it works.
                //m.sendMessage(dout);
                lastSent = System.currentTimeMillis();

                // Remove all piece messages after sending a choke message.
                if (m.type == Message.CHOKE)
                  removeMessage(Message.PIECE);

                // XXX - Should also register overhead...
                // Don't let other clients requesting big chunks get an advantage
                // when we are seeding;
                // only count the rest of the upload after sendMessage().
                int remainder = 0;
                if (m.type == Message.PIECE) {
                  if (m.len <= PeerState.PARTSIZE) {
                     state.uploaded(m.len);
                  } else {
                     state.uploaded(PeerState.PARTSIZE);
                     remainder = m.len - PeerState.PARTSIZE;
                  }
                }

                m.sendMessage(dout);
                if (remainder > 0)
                  state.uploaded(remainder);
                m = null;
              }
          }
      }
    catch (IOException ioe)
      {
        // Ignore, probably other side closed connection.
        if (_log.shouldLog(Log.INFO))
            _log.info("IOError sending to " + peer, ioe);
      }
    catch (Throwable t)
      {
        _log.error("Error sending to " + peer, t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
    finally
      {
        quit = true;
        peer.disconnect();
      }
  }

  public void disconnect()
  {
    synchronized(sendQueue)
      {
        //if (quit == true)
        //  return;
        
        quit = true;
        if (thread != null)
            thread.interrupt();
        
        sendQueue.clear();
        sendQueue.notify();
      }
    if (dout != null) {
        try {
            dout.close();
        } catch (IOException ioe) {
            _log.warn("Error closing the stream to " + peer, ioe);
        }
    }
  }

  /**
   * Adds a message to the sendQueue and notifies the method waiting
   * on the sendQueue to change.
   */
  private void addMessage(Message m)
  {
    synchronized(sendQueue)
      {
        sendQueue.add(m);
        sendQueue.notifyAll();
      }
  }
  
  /** remove messages not sent in 3m */
  private static final int SEND_TIMEOUT = 3*60*1000;
  private class RemoveTooSlow implements SimpleTimer.TimedEvent {
      private Message _m;
      public RemoveTooSlow(Message m) {
          _m = m;
          m.expireEvent = RemoveTooSlow.this;
      }
      
      public void timeReached() {
          boolean removed = false;
          synchronized (sendQueue) {
              removed = sendQueue.remove(_m);
              sendQueue.notifyAll();
          }
          if (removed)
              _log.info("Took too long to send " + _m + " to " + peer);
      }
  }

  /**
   * Removes a particular message type from the queue.
   *
   * @param type the Message type to remove.
   * @returns true when a message of the given type was removed, false
   * otherwise.
   */
  private boolean removeMessage(int type)
  {
    boolean removed = false;
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == type)
              {
                it.remove();
                removed = true;
              }
          }
        sendQueue.notifyAll();
      }
    return removed;
  }

  void sendAlive()
  {
    Message m = new Message();
    m.type = Message.KEEP_ALIVE;
//  addMessage(m);
    synchronized(sendQueue)
      {
        if(sendQueue.isEmpty())
          sendQueue.add(m);
        sendQueue.notifyAll();
      }
  }

  void sendChoke(boolean choke)
  {
    // We cancel the (un)choke but keep PIECE messages.
    // PIECE messages are purged if a choke is actually send.
    synchronized(sendQueue)
      {
        int inverseType  = choke ? Message.UNCHOKE
                                 : Message.CHOKE;
        if (!removeMessage(inverseType))
          {
            Message m = new Message();
            if (choke)
              m.type = Message.CHOKE;
            else
              m.type = Message.UNCHOKE;
            addMessage(m);
          }
      }
  }

  void sendInterest(boolean interest)
  {
    synchronized(sendQueue)
      {
        int inverseType  = interest ? Message.UNINTERESTED
                                    : Message.INTERESTED;
        if (!removeMessage(inverseType))
          {
            Message m = new Message();
            if (interest)
              m.type = Message.INTERESTED;
            else
              m.type = Message.UNINTERESTED;
            addMessage(m);
          }
      }
  }

  void sendHave(int piece)
  {
    Message m = new Message();
    m.type = Message.HAVE;
    m.piece = piece;
    addMessage(m);
  }

  void sendBitfield(BitField bitfield)
  {
    Message m = new Message();
    m.type = Message.BITFIELD;
    m.data = bitfield.getFieldBytes();
    m.off = 0;
    m.len = m.data.length;
    addMessage(m);
  }

  /** reransmit requests not received in 7m */
  private static final int REQ_TIMEOUT = (2 * SEND_TIMEOUT) + (60 * 1000);
  void retransmitRequests(List requests)
  {
    long now = System.currentTimeMillis();
    Iterator it = requests.iterator();
    while (it.hasNext())
      {
        Request req = (Request)it.next();
        if(now > req.sendTime + REQ_TIMEOUT) {
          if (_log.shouldLog(Log.DEBUG))
              _log.debug("Retransmit request " + req + " to peer " + peer);
          sendRequest(req);
        }
      }
  }

  void sendRequests(List requests)
  {
    Iterator it = requests.iterator();
    while (it.hasNext())
      {
        Request req = (Request)it.next();
        sendRequest(req);
      }
  }

  void sendRequest(Request req)
  {
    // Check for duplicate requests to deal with fibrillating i2p-bt
    // (multiple choke/unchokes received cause duplicate requests in the queue)
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.REQUEST && m.piece == req.piece &&
                m.begin == req.off && m.length == req.len)
              {
                if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Discarding duplicate request " + req + " to peer " + peer);
                return;
              }
          }
      }
    Message m = new Message();
    m.type = Message.REQUEST;
    m.piece = req.piece;
    m.begin = req.off;
    m.length = req.len;
    addMessage(m);
    req.sendTime = System.currentTimeMillis();
  }

  // Used by PeerState to limit pipelined requests
  int queuedBytes()
  {
    int total = 0;
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.PIECE)
                total += m.length;
          }
      }
    return total;
  }

  /**
   *  Queue a piece message with a callback to load the data
   *  from disk when required.
   *  @since 0.8.2
   */
  void sendPiece(int piece, int begin, int length, DataLoader loader)
  {
      boolean sendNow = false;
      // are there any cases where we should?

      if (sendNow) {
        // queue the real thing
        byte[] bytes = loader.loadData(piece, begin, length);
        if (bytes != null)
            sendPiece(piece, begin, length, bytes);
        return;
      }

      // queue a fake message... set everything up,
      // except save the PeerState instead of the bytes.
      Message m = new Message();
      m.type = Message.PIECE;
      m.piece = piece;
      m.begin = begin;
      m.length = length;
      m.dataLoader = loader;
      m.off = 0;
      m.len = length;
      addMessage(m);
  }

  /**
   *  Queue a piece message with the data already loaded from disk
   *  Also add a timeout.
   *  We don't use this anymore.
   */
  void sendPiece(int piece, int begin, int length, byte[] bytes)
  {
    Message m = new Message();
    m.type = Message.PIECE;
    m.piece = piece;
    m.begin = begin;
    m.length = length;
    m.data = bytes;
    m.off = 0;
    m.len = length;
    // since we have the data already loaded, queue a timeout to remove it
    SimpleScheduler.getInstance().addEvent(new RemoveTooSlow(m), SEND_TIMEOUT);
    addMessage(m);
  }

  void sendCancel(Request req)
  {
    // See if it is still in our send queue
    synchronized(sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.REQUEST
                && m.piece == req.piece
                && m.begin == req.off
                && m.length == req.len)
              it.remove();
          }
      }

    // Always send, just to be sure it it is really canceled.
    Message m = new Message();
    m.type = Message.CANCEL;
    m.piece = req.piece;
    m.begin = req.off;
    m.length = req.len;
    addMessage(m);
  }

  /**
   *  Remove all Request messages from the queue
   *  @since 0.8.2
   */
  void cancelRequestMessages() {
      synchronized(sendQueue) {
          for (Iterator<Message> it = sendQueue.iterator(); it.hasNext(); ) {
              if (it.next().type == Message.REQUEST)
                it.remove();
          }
      }
  }

  // Called by the PeerState when the other side doesn't want this
  // request to be handled anymore. Removes any pending Piece Message
  // from out send queue.
  void cancelRequest(int piece, int begin, int length)
  {
    synchronized (sendQueue)
      {
        Iterator it = sendQueue.iterator();
        while (it.hasNext())
          {
            Message m = (Message)it.next();
            if (m.type == Message.PIECE
                && m.piece == piece
                && m.begin == begin
                && m.length == length)
              it.remove();
          }
      }
  }

  /** @since 0.8.2 */
  void sendExtension(int id, byte[] bytes) {
    Message m = new Message();
    m.type = Message.EXTENSION;
    m.piece = id;
    m.data = bytes;
    m.begin = 0;
    m.length = bytes.length;
    addMessage(m);

  }
}
