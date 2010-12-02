/* PeerConnectionIn - Handles incomming messages and hands them to PeerState.
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

import java.io.DataInputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

class PeerConnectionIn implements Runnable
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionIn.class);
  private final Peer peer;
  private final DataInputStream din;

  private Thread thread;
  private volatile boolean quit;

  long lastRcvd;

  public PeerConnectionIn(Peer peer, DataInputStream din)
  {
    this.peer = peer;
    this.din = din;
    lastRcvd = System.currentTimeMillis();
    quit = false;
  }

  void disconnect()
  {
    if (quit == true)
      return;

    quit = true;
    Thread t = thread;
    if (t != null)
      t.interrupt();
    if (din != null) {
        try {
            din.close();
        } catch (IOException ioe) {
            _log.warn("Error closing the stream from " + peer, ioe);
        }
    }
  }

  public void run()
  {
    thread = Thread.currentThread();
    try
      {
        PeerState ps = peer.state;
        while (!quit && ps != null)
          {
            // Common variables used for some messages.
            int piece;
            int begin;
            int len;
        
            // Wait till we hear something...
            // The length of a complete message in bytes.
            // The biggest is the piece message, for which the length is the
            // request size (32K) plus 9. (we could also check if Storage.MAX_PIECES / 8
            // in the bitfield message is bigger but it's currently 5000/8 = 625 so don't bother)
            int i = din.readInt();
            lastRcvd = System.currentTimeMillis();
            if (i < 0 || i > PeerState.PARTSIZE + 9)
              throw new IOException("Unexpected length prefix: " + i);

            if (i == 0)
              {
                ps.keepAliveMessage();
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received keepalive from " + peer + " on " + peer.metainfo.getName());
                continue;
              }
            
            byte b = din.readByte();
            Message m = new Message();
            m.type = b;
            switch (b)
              {
              case 0:
                ps.chokeMessage(true);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received choke from " + peer + " on " + peer.metainfo.getName());
                break;
              case 1:
                ps.chokeMessage(false);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received unchoke from " + peer + " on " + peer.metainfo.getName());
                break;
              case 2:
                ps.interestedMessage(true);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received interested from " + peer + " on " + peer.metainfo.getName());
                break;
              case 3:
                ps.interestedMessage(false);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received not interested from " + peer + " on " + peer.metainfo.getName());
                break;
              case 4:
                piece = din.readInt();
                ps.haveMessage(piece);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received havePiece(" + piece + ") from " + peer + " on " + peer.metainfo.getName());
                break;
              case 5:
                byte[] bitmap = new byte[i-1];
                din.readFully(bitmap);
                ps.bitfieldMessage(bitmap);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received bitmap from " + peer + " on " + peer.metainfo.getName() + ": size=" + (i-1) /* + ": " + ps.bitfield */ );
                break;
              case 6:
                piece = din.readInt();
                begin = din.readInt();
                len = din.readInt();
                ps.requestMessage(piece, begin, len);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received request(" + piece + "," + begin + ") from " + peer + " on " + peer.metainfo.getName());
                break;
              case 7:
                piece = din.readInt();
                begin = din.readInt();
                len = i-9;
                Request req = ps.getOutstandingRequest(piece, begin, len);
                byte[] piece_bytes;
                if (req != null)
                  {
                    piece_bytes = req.bs;
                    din.readFully(piece_bytes, begin, len);
                    ps.pieceMessage(req);
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug("Received data(" + piece + "," + begin + ") from " + peer + " on " + peer.metainfo.getName());
                  }
                else
                  {
                    // XXX - Consume but throw away afterwards.
                    piece_bytes = new byte[len];
                    din.readFully(piece_bytes);
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug("Received UNWANTED data(" + piece + "," + begin + ") from " + peer + " on " + peer.metainfo.getName());
                  }
                break;
              case 8:
                piece = din.readInt();
                begin = din.readInt();
                len = din.readInt();
                ps.cancelMessage(piece, begin, len);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received cancel(" + piece + "," + begin + ") from " + peer + " on " + peer.metainfo.getName());
                break;
              case 20:  // Extension message
                int id = din.readUnsignedByte();
                byte[] payload = new byte[i-2];
                din.readFully(payload);
                ps.extensionMessage(id, payload);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received extension message from " + peer + " on " + peer.metainfo.getName());
                break;
              default:
                byte[] bs = new byte[i-1];
                din.readFully(bs);
                ps.unknownMessage(b, bs);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Received unknown message from " + peer + " on " + peer.metainfo.getName());
              }
          }
      }
    catch (IOException ioe)
      {
        // Ignore, probably the other side closed connection.
        if (_log.shouldLog(Log.INFO))
            _log.info("IOError talking with " + peer, ioe);
      }
    catch (Throwable t)
      {
        _log.error("Error talking with " + peer, t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
    finally
      {
        peer.disconnect();
      }
  }
}
