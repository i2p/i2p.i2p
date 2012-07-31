/* Request - Holds all information needed for a (partial) piece request.
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

/**
 * Holds all information needed for a partial piece request.
 * This class should be used only by PeerState, PeerConnectionIn, and PeerConnectionOut.
 */
class Request
{
  private final PartialPiece piece;
  final int off;
  final int len;
  long sendTime;

  /**
   * Creates a new Request.
   *
   * @param piece Piece number requested.
   * @param off the offset in the array.
   * @param len the number of bytes requested.
   */
  Request(PartialPiece piece, int off, int len)
  {
    // Sanity check
    if (off < 0 || len <= 0 || off + len > piece.getLength())
      throw new IndexOutOfBoundsException("Illegal Request " + toString());

    this.piece = piece;
    this.off = off;
    this.len = len;
  }

  /**
   *  @since 0.9.1
   */
  public void read(DataInputStream din) throws IOException {
      piece.read(din, off, len);
  }

  /**
   *  The piece number this Request is for
   *
   *  @since 0.9.1
   */
  public int getPiece() {
      return piece.getPiece();
  }

  /**
   *  The PartialPiece this Request is for
   *
   *  @since 0.9.1
   */
  public PartialPiece getPartialPiece() {
      return piece;
  }

    @Override
  public int hashCode()
  {
    return piece.getPiece() ^ off ^ len;
  }

    @Override
  public boolean equals(Object o)
  {
    if (o instanceof Request)
      {
        Request req = (Request)o;
        return req.piece.equals(piece) && req.off == off && req.len == len;
      }

    return false;
  }

    @Override
  public String toString()
  {
    return "(" + piece.getPiece() + "," + off + "," + len + ")";
  }
}
