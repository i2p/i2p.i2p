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
import java.net.ConnectException;

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
  private final PeerAcceptor peeracceptor;
  private Thread thread;
  private final I2PSnarkUtil _util;
  private final ObjectCounter<Hash> _badCounter = new ObjectCounter<Hash>();
  private final SimpleTimer2.TimedEvent _cleaner;

  private volatile boolean stop;

  // protocol errors before blacklisting.
  private static final int MAX_BAD = 1;
  private static final long BAD_CLEAN_INTERVAL = 30*60*1000;

  /**
   *  Multitorrent. Caller MUST call startAccepting()
   */
  public ConnectionAcceptor(I2PSnarkUtil util, PeerCoordinatorSet set) {
      _util = util;
      _cleaner = new Cleaner();
      peeracceptor = new PeerAcceptor(set);
  }
  
  /**
   *  May be called even when already running. May be called to start up again after halt().
   */
  public synchronized void startAccepting() {
      stop = false;
      if (_log.shouldLog(Log.WARN))
          _log.warn("ConnectionAcceptor startAccepting new thread? " + (thread == null));
      if (thread == null) {
          thread = new I2PAppThread(this, "I2PSnark acceptor");
          thread.setDaemon(true);
          thread.start();
          _cleaner.reschedule(BAD_CLEAN_INTERVAL, false);
      }
  }
  
  /**
   *  Unused (single torrent).
   *  Do NOT call startAccepting().
   */
  public ConnectionAcceptor(I2PSnarkUtil util,
                            PeerAcceptor peeracceptor)
  {
    this.peeracceptor = peeracceptor;
    _util = util;
    
    thread = new I2PAppThread(this, "I2PSnark acceptor");
    thread.setDaemon(true);
    thread.start();
    _cleaner = new Cleaner();
  }

  /**
   *  May be restarted later with startAccepting().
   */
  public synchronized void halt()
  {
    if (stop) return;
    stop = true;
    locked_halt();
    Thread t = thread;
    if (t != null) {
      t.interrupt();
      thread = null;
    }
  }


  /**
   *  Caller must synch
   *  @since 0.9.9
   */
  private void locked_halt()
  {
    I2PServerSocket ss = _util.getServerSocket();
    if (ss != null) {
      try
        {
          ss.close();
        }
      catch(I2PException ioe) { }
    }
    _badCounter.clear();
    _cleaner.cancel();
  }
  
  /**
   *  Effectively unused, would only be called if we changed
   *  I2CP host/port, which is hidden in the gui if in router context
   *  FIXME this only works if already running
   */
  public synchronized void restart() {
      Thread t = thread;
      if (t != null)
          t.interrupt();
  }

  public int getPort()
  {
    return TrackerClient.PORT; // serverSocket.getLocalPort();
  }

  public void run()
  {
      try {
          run2();
      } finally {
          synchronized(this) {
              thread = null;
          }
      }
  }

  private void run2()
  {
    while(!stop)
      {
        I2PServerSocket serverSocket = _util.getServerSocket();
        while ( (serverSocket == null) && (!stop)) {
            if (!(_util.isConnecting() || _util.connected())) {
                stop = true;
                break;
            }
            try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
            serverSocket = _util.getServerSocket();
        }
        if(stop)
            break;
        try
          {
            I2PSocket socket = serverSocket.accept();
            if (socket == null) {
                    continue;
            } else {
                if (socket.getPeerDestination().equals(_util.getMyDestination())) {
                    _log.error("Incoming connection from myself");
                    try { socket.close(); } catch (IOException ioe) {}
                    continue;
                }
                Hash h = socket.getPeerDestination().calculateHash();
                if (socket.getLocalPort() == 80) {
                     _badCounter.increment(h);
                    if (_log.shouldLog(Log.WARN))
                        _log.error("Dropping incoming HTTP from " + h);
                    try { socket.close(); } catch (IOException ioe) {}
                    continue;
                }
                int bad = _badCounter.count(h);
                if (bad >= MAX_BAD) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Rejecting connection from " + h +
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
            int level = stop ? Log.WARN : Log.ERROR;
            if (_log.shouldLog(level))
                _log.log(level, "Error while accepting", ioe);
            synchronized(this) {
                if (!stop) {
                    locked_halt();
                    thread = null;
                    stop = true;
                }
            }
          }
        catch (ConnectException ioe)
          {
            // This is presumed to be due to socket closing by I2PSnarkUtil.disconnect(),
            // which does not currently call our halt(), although it should
            if (_log.shouldWarn())
                _log.warn("Error while accepting", ioe);
            synchronized(this) {
                if (!stop) {
                    locked_halt();
                    thread = null;
                    stop = true;
                }
            }
          }
        catch (IOException ioe)
          {
            int level = stop ? Log.WARN : Log.ERROR;
            if (_log.shouldLog(level))
                _log.log(level, "Error while accepting", ioe);
            synchronized(this) {
                if (!stop) {
                    locked_halt();
                    thread = null;
                    stop = true;
                }
            }
          }
        // catch oom?
      }
      if (_log.shouldLog(Log.WARN))
          _log.warn("ConnectionAcceptor closed");
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
