/* Message - A protocol message which can be send through a DataOutputStream.
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

import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;

/**
 * Used to queue outgoing connections
 * sendMessage() should be used to translate them to wire format.
 */
class Message
{
  final static byte KEEP_ALIVE   = -1;
  final static byte CHOKE        = 0;
  final static byte UNCHOKE      = 1;
  final static byte INTERESTED   = 2;
  final static byte UNINTERESTED = 3;
  final static byte HAVE         = 4;
  final static byte BITFIELD     = 5;
  final static byte REQUEST      = 6;
  final static byte PIECE        = 7;
  final static byte CANCEL       = 8;
  final static byte PORT         = 9;   // DHT  (BEP 5)
  final static byte SUGGEST      = 13;  // Fast (BEP 6)
  final static byte HAVE_ALL     = 14;  // Fast (BEP 6)
  final static byte HAVE_NONE    = 15;  // Fast (BEP 6)
  final static byte REJECT       = 16;  // Fast (BEP 6)
  final static byte ALLOWED_FAST = 17;  // Fast (BEP 6)
  final static byte EXTENSION    = 20;  // BEP 10
  
  // Not all fields are used for every message.
  // KEEP_ALIVE doesn't have a real wire representation
  final byte type;

  // Used for HAVE, REQUEST, PIECE and CANCEL messages.
  // Also SUGGEST, REJECT, ALLOWED_FAST
  // low byte used for EXTENSION message
  // low two bytes used for PORT message
  final int piece;

  // Used for REQUEST, PIECE and CANCEL messages.
  // Also REJECT
  final int begin;
  final int length;

  // Used for PIECE and BITFIELD and EXTENSION messages
  byte[] data;
  final int off;
  final int len;

  // Used to do deferred fetch of data
  private final DataLoader dataLoader;

  // now unused
  //SimpleTimer.TimedEvent expireEvent;
  
  private static final int BUFSIZE = PeerState.PARTSIZE;
  private static final ByteCache _cache = ByteCache.getInstance(16, BUFSIZE);

  /**
   * For types KEEP_ALIVE, CHOKE, UNCHOKE, INTERESTED, UNINTERESTED, HAVE_ALL, HAVE_NONE
   * @since 0.9.32
   */
  Message(byte type) {
      this(type, 0, 0, 0, null, 0, 0, null);
  }

  /**
   * For types HAVE, PORT, SUGGEST, ALLOWED_FAST
   * @since 0.9.32
   */
  Message(byte type, int piece) {
      this(type, piece, 0, 0, null, 0, 0, null);
  }

  /**
   * For types REQUEST, REJECT, CANCEL
   * @since 0.9.32
   */
  Message(byte type, int piece, int begin, int length) {
      this(type, piece, begin, length, null, 0, 0, null);
  }

  /**
   * For type BITFIELD
   * @since 0.9.32
   */
  Message(byte[] data) {
      this(BITFIELD, 0, 0, 0, data, 0, data.length, null);
  }

  /**
   * For type EXTENSION
   * @since 0.9.32
   */
  Message(int id, byte[] data) {
      this(EXTENSION, id, 0, 0, data, 0, data.length, null);
  }

  /**
   * For type PIECE with deferred data
   * @since 0.9.32
   */
  Message(int piece, int begin, int length, DataLoader loader) {
      this(PIECE, piece, begin, length, null, 0, length, loader);
  }

  /**
   * For type PIECE with data
   * We don't use this anymore.
   * @since 0.9.32
   */
/****
  Message(int piece, int begin, int length, byte[] data) {
      this(PIECE, piece, begin, length, data, 0, length, null);
  }
****/

  /**
   * @since 0.9.32
   */
  private Message(byte type, int piece, int begin, int length, byte[] data, int off, int len, DataLoader loader) {
      this.type = type;
      this.piece = piece;
      this.begin = begin;
      this.length = length;
      this.data = data;
      this.off = off;
      this.len = len;
      dataLoader = loader;
  }

  /** Utility method for sending a message through a DataStream. */
  void sendMessage(DataOutputStream dos) throws IOException
  {
    // KEEP_ALIVE is special.
    if (type == KEEP_ALIVE)
      {
        dos.writeInt(0);
        return;
      }

    ByteArray ba;
    // Get deferred data
    if (data == null && dataLoader != null) {
        ba = dataLoader.loadData(piece, begin, length);
        if (ba == null)
            return;  // hmm will get retried, but shouldn't happen
        data = ba.getData();
    } else {
        ba = null;
    }

    // Calculate the total length in bytes

    // Type is one byte.
    int datalen = 1;

    // piece is 4 bytes.
    if (type == HAVE || type == REQUEST || type == PIECE || type == CANCEL ||
        type == SUGGEST || type == REJECT || type == ALLOWED_FAST)
      datalen += 4;

    // begin/offset is 4 bytes
    if (type == REQUEST || type == PIECE || type == CANCEL ||
        type == REJECT)
      datalen += 4;

    // length is 4 bytes
    if (type == REQUEST || type == CANCEL ||
        type == REJECT)
      datalen += 4;

    // msg type is 1 byte
    else if (type == EXTENSION)
      datalen += 1;

    else if (type == PORT)
      datalen += 2;

    // add length of data for piece or bitfield array.
    if (type == BITFIELD || type == PIECE || type == EXTENSION)
      datalen += len;

    // Send length
    dos.writeInt(datalen);
    dos.writeByte(type & 0xFF);

    // Send additional info (piece number)
    if (type == HAVE || type == REQUEST || type == PIECE || type == CANCEL ||
        type == SUGGEST || type == REJECT || type == ALLOWED_FAST)
      dos.writeInt(piece);

    // Send additional info (begin/offset)
    if (type == REQUEST || type == PIECE || type == CANCEL ||
        type == REJECT)
      dos.writeInt(begin);

    // Send additional info (length); for PIECE this is implicit.
    if (type == REQUEST || type == CANCEL ||
        type == REJECT)
        dos.writeInt(length);

    else if (type == EXTENSION)
        dos.writeByte((byte) piece & 0xff);

    else if (type == PORT)
        dos.writeShort(piece & 0xffff);

    // Send actual data
    if (type == BITFIELD || type == PIECE || type == EXTENSION)
      dos.write(data, off, len);

    // Was pulled from cache in Storage.getPiece() via dataLoader
    if (ba != null && ba.getData().length == BUFSIZE)
        _cache.release(ba, false);
  }

    @Override
  public String toString()
  {
    switch (type)
      {
      case KEEP_ALIVE:
        return "KEEP_ALIVE";
      case CHOKE:
        return "CHOKE";
      case UNCHOKE:
        return "UNCHOKE";
      case INTERESTED:
        return "INTERESTED";
      case UNINTERESTED:
        return "UNINTERESTED";
      case HAVE:
        return "HAVE(" + piece + ')';
      case BITFIELD:
        return "BITFIELD";
      case REQUEST:
        return "REQUEST(" + piece + ',' + begin + ',' + length + ')';
      case PIECE:
        return "PIECE(" + piece + ',' + begin + ',' + length + ')';
      case CANCEL:
        return "CANCEL(" + piece + ',' + begin + ',' + length + ')';
      case PORT:
        return "PORT(" + piece + ')';
      case EXTENSION:
        return "EXTENSION(" + piece + ',' + data.length + ')';
      // fast extensions below here
      case SUGGEST:
        return "SUGGEST(" + piece + ')';
      case HAVE_ALL:
        return "HAVE_ALL";
      case HAVE_NONE:
        return "HAVE_NONE";
      case REJECT:
        return "REJECT(" + piece + ',' + begin + ',' + length + ')';
      case ALLOWED_FAST:
        return "ALLOWED_FAST(" + piece + ')';
      default:
        return "UNKNOWN (" + type + ')';
      }
  }
}
