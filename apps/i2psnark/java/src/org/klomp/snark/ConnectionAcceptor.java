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

import java.io.*;
import java.net.*;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;

/**
 * Accepts connections on a TCP port and routes them to sub-acceptors.
 */
public class ConnectionAcceptor implements Runnable
{
  private final I2PServerSocket serverSocket;
  private final PeerAcceptor peeracceptor;
  private Thread thread;

  private boolean stop;

  public ConnectionAcceptor(I2PServerSocket serverSocket,
                            PeerAcceptor peeracceptor)
  {
    this.serverSocket = serverSocket;
    this.peeracceptor = peeracceptor;
    
    stop = false;
    thread = new Thread(this);
    thread.start();
  }

  public void halt()
  {
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

  public int getPort()
  {
    return 6881; // serverSocket.getLocalPort();
  }

  public void run()
  {
    while(!stop)
      {
        try
          {
            final I2PSocket socket = serverSocket.accept();
            Thread t = new Thread("Connection-" + socket)
              {
                public void run()
                {
                  try
                    {
                      InputStream in = socket.getInputStream();
                      OutputStream out = socket.getOutputStream();
                      BufferedInputStream bis = new BufferedInputStream(in);
                      BufferedOutputStream bos = new BufferedOutputStream(out);
                      
                      // See what kind of connection it is.
                      /*
                      if (httpacceptor != null)
                        {
                          byte[] scratch = new byte[4];
                          bis.mark(4);
                          int len = bis.read(scratch);
                          if (len != 4)
                            throw new IOException("Need at least 4 bytes");
                          bis.reset();
                          if (scratch[0] == 19 && scratch[1] == 'B'
                              && scratch[2] == 'i' && scratch[3] == 't')
                            peeracceptor.connection(socket, bis, bos);
                          else if (scratch[0] == 'G' && scratch[1] == 'E'
                                   && scratch[2] == 'T' && scratch[3] == ' ')
                            httpacceptor.connection(socket, bis, bos);
                        }
                      else
                       */
                        peeracceptor.connection(socket, bis, bos);
                    }
                  catch (IOException ioe)
                    {
                      try
                        {
                          socket.close();
                        }
                      catch (IOException ignored) { }
                    }
                }
              };
            t.start();
          }
        catch (I2PException ioe)
          {
            Snark.debug("Error while accepting: " + ioe, Snark.ERROR);
            stop = true;
          }
        catch (IOException ioe)
          {
            Snark.debug("Error while accepting: " + ioe, Snark.ERROR);
            stop = true;
          }
      }

    try
      {
        serverSocket.close();
      }
    catch (I2PException ignored) { }
  }
}
