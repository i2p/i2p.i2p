/* ConnectionAcceptor - Accepts connections and routes them to sub-acceptors.
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Hash;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer2;

/**
 * Accepts connections on a I2PServerSocket and routes them to PeerAcceptors.
 */
class ConnectionAcceptor implements Runnable
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(ConnectionAcceptor.class);
  private I2PServerSocket serverSocket;
  private PeerAcceptor peeracceptor;
  private Thread thread;
  private final I2PSnarkUtil _util;
  private final ObjectCounter<Hash> _badCounter = new ObjectCounter();
  private final SimpleTimer2.TimedEvent _cleaner;

  private volatile boolean stop;
  private boolean socketChanged;

  // protocol errors before blacklisting.
  private static final int MAX_BAD = 1;
  private static final long BAD_CLEAN_INTERVAL = 30*60*1000;

  /**
   *  Multitorrent
   */
  public ConnectionAcceptor(I2PSnarkUtil util) {
      _util = util;
      _cleaner = new Cleaner();
  }
  
  public synchronized void startAccepting(PeerCoordinatorSet set, I2PServerSocket socket) {
    if (serverSocket != socket) {
      if ( (peeracceptor == null) || (peeracceptor.coordinators != set) )
        peeracceptor = new PeerAcceptor(set);
      serverSocket = socket;
      stop = false;
      socketChanged = true;
      if (thread == null) {
          thread = new I2PAppThread(this, "I2PSnark acceptor");
          thread.setDaemon(true);
          thread.start();
          _cleaner.schedule(BAD_CLEAN_INTERVAL);
      }
    }
  }
  
  /**
   *  Unused (single torrent)
   */
  public ConnectionAcceptor(I2PSnarkUtil util, I2PServerSocket serverSocket,
                            PeerAcceptor peeracceptor)
  {
    this.serverSocket = serverSocket;
    this.peeracceptor = peeracceptor;
    _util = util;
    
    thread = new I2PAppThread(this, "I2PSnark acceptor");
    thread.setDaemon(true);
    thread.start();
    _cleaner = new Cleaner();
  }

  public void halt()
  {
    if (stop) return;
    stop = true;

    I2PServerSocket ss = serverSocket;
    if (ss != null)
      try
        {
          ss.close();
        }
      catch(I2PException ioe) { }

    Thread t = thread;
    if (t != null)
      t.interrupt();
    _cleaner.cancel();
  }
  
  /**
   *  Effectively unused, would only be called if we changed
   *  I2CP host/port, which is hidden in the gui if in router context
   */
  public void restart() {
      serverSocket = _util.getServerSocket();
      socketChanged = true;
      Thread t = thread;
      if (t != null)
          t.interrupt();
      _cleaner.schedule(BAD_CLEAN_INTERVAL);
  }

  public int getPort()
  {
    return 6881; // serverSocket.getLocalPort();
  }

  public void run()
  {
    while(!stop)
      {
        if (socketChanged) {
            // ok, already updated
            socketChanged = false;
        }
        while ( (serverSocket == null) && (!stop)) {
            serverSocket = _util.getServerSocket();
            if (serverSocket == null)
                try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
        }
        if(stop)
            break;
        try
          {
            I2PSocket socket = serverSocket.accept();
            if (socket == null) {
                if (socketChanged) {
                    continue;
                } else {
                    I2PServerSocket ss = _util.getServerSocket();
                    if (ss != serverSocket) {
                        serverSocket = ss;
                        socketChanged = true;
                    }
                }
            } else {
                if (socket.getPeerDestination().equals(_util.getMyDestination())) {
                    _log.error("Incoming connection from myself");
                    try { socket.close(); } catch (IOException ioe) {}
                    continue;
                }
                int bad = _badCounter.count(socket.getPeerDestination().calculateHash());
                if (bad >= MAX_BAD) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Rejecting connection from " + socket.getPeerDestination().calculateHash() +
                                  " after " + bad + " failures, max is " + MAX_BAD);
                    try { socket.close(); } catch (IOException ioe) {}
                    continue;
                }
                Thread t = new I2PAppThread(new Handler(socket), "I2PSnark incoming connection");
                t.start();
            }
          }
        catch (I2PException ioe)
          {
            if (!socketChanged) {
                _log.error("Error while accepting", ioe);
                stop = true;
            }
          }
        catch (IOException ioe)
          {
            _log.error("Error while accepting", ioe);
            stop = true;
          }
        // catch oom?
      }

    try
      {
        if (serverSocket != null)
          serverSocket.close();
      }
    catch (I2PException ignored) { }
    
  }
  
  private class Handler implements Runnable {
      private final I2PSocket _socket;

      public Handler(I2PSocket socket) {
          _socket = socket;
      }

      public void run() {
          try {
              InputStream in = _socket.getInputStream();
              OutputStream out = _socket.getOutputStream();
              // this is for the readahead in PeerAcceptor.connection()
              in = new BufferedInputStream(in);
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Handling socket from " + _socket.getPeerDestination().calculateHash());
              peeracceptor.connection(_socket, in, out);
          } catch (PeerAcceptor.ProtocolException ihe) {
              _badCounter.increment(_socket.getPeerDestination().calculateHash());
              if (_log.shouldLog(Log.INFO))
                  _log.info("Protocol error from " + _socket.getPeerDestination().calculateHash(), ihe);
              try { _socket.close(); } catch (IOException ignored) { }
          } catch (IOException ioe) {
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Error handling connection from " + _socket.getPeerDestination().calculateHash(), ioe);
              try { _socket.close(); } catch (IOException ignored) { }
          }
      }
  }

    /** @since 0.9.1 */    
    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(_util.getContext().simpleTimer2());
        }

        public void timeReached() {
            if (stop)
                return;
            _badCounter.clear();
            schedule(BAD_CLEAN_INTERVAL);
        }
    }
}
