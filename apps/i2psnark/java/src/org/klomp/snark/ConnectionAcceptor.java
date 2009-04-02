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

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Accepts connections on a TCP port and routes them to sub-acceptors.
 */
public class ConnectionAcceptor implements Runnable
{
  private Log _log = new Log(ConnectionAcceptor.class);
  private I2PServerSocket serverSocket;
  private PeerAcceptor peeracceptor;
  private Thread thread;
  private I2PSnarkUtil _util;

  private boolean stop;
  private boolean socketChanged;

  public ConnectionAcceptor(I2PSnarkUtil util) { _util = util; }
  
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
      }
    }
  }
  
  public ConnectionAcceptor(I2PSnarkUtil util, I2PServerSocket serverSocket,
                            PeerAcceptor peeracceptor)
  {
    this.serverSocket = serverSocket;
    this.peeracceptor = peeracceptor;
    _util = util;
    
    socketChanged = false;
    stop = false;
    thread = new I2PAppThread(this, "I2PSnark acceptor");
    thread.setDaemon(true);
    thread.start();
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
  }
  
  public void restart() {
      serverSocket = _util.getServerSocket();
      socketChanged = true;
      Thread t = thread;
      if (t != null)
          t.interrupt();
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
                Thread t = new I2PAppThread(new Handler(socket), "Connection-" + socket);
                t.start();
            }
          }
        catch (I2PException ioe)
          {
            if (!socketChanged) {
                _util.debug("Error while accepting: " + ioe, Snark.ERROR);
                stop = true;
            }
          }
        catch (IOException ioe)
          {
            _util.debug("Error while accepting: " + ioe, Snark.ERROR);
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
      private I2PSocket _socket;
      public Handler(I2PSocket socket) {
          _socket = socket;
      }
      public void run() {
          try {
              InputStream in = _socket.getInputStream();
              OutputStream out = _socket.getOutputStream();

              if (true) {
                  in = new BufferedInputStream(in);
                  //out = new BufferedOutputStream(out);
              }
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Handling socket from " + _socket.getPeerDestination().calculateHash().toBase64());
              peeracceptor.connection(_socket, in, out);
          } catch (IOException ioe) {
              if (_log.shouldLog(Log.DEBUG))
                  _log.debug("Error handling connection from " + _socket.getPeerDestination().calculateHash().toBase64(), ioe);
              try { _socket.close(); } catch (IOException ignored) { }
          }
      }
  }
}
