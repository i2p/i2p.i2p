/* PeerID - All public information concerning a peer.
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

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.Destination;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

public class PeerID implements Comparable
{
  private final byte[] id;
  private final Destination address;
  private final int port;

  private final int hash;

  public PeerID(byte[] id, Destination address)
  {
    this.id = id;
    this.address = address;
    this.port = 6881;

    hash = calculateHash();
  }

  /**
   * Creates a PeerID from a BDecoder.
   */
  public PeerID(BDecoder be)
    throws IOException
  {
    this(be.bdecodeMap().getMap());
  }

  /**
   * Creates a PeerID from a Map containing BEncoded peer id, ip and
   * port.
   */
  public PeerID(Map m)
    throws InvalidBEncodingException, UnknownHostException
  {
    BEValue bevalue = (BEValue)m.get("peer id");
    if (bevalue == null)
      throw new InvalidBEncodingException("peer id missing");
    id = bevalue.getBytes();

    bevalue = (BEValue)m.get("ip");
    if (bevalue == null)
      throw new InvalidBEncodingException("ip missing");
    address = I2PSnarkUtil.getDestinationFromBase64(bevalue.getString());
    if (address == null)
        throw new InvalidBEncodingException("Invalid destination [" + bevalue.getString() + "]");

    port = 6881;

    hash = calculateHash();
  }

  public byte[] getID()
  {
    return id;
  }

  public Destination getAddress()
  {
    return address;
  }

  public int getPort()
  {
    return port;
  }

  private int calculateHash()
  {
    int b = 0;
    for (int i = 0; i < id.length; i++)
      b ^= id[i];
    return (b ^ address.hashCode()) ^ port;
  }

  /**
   * The hash code of a PeerID is the exclusive or of all id bytes.
   */
  public int hashCode()
  {
    return hash;
  }

  /**
   * Returns true if and only if this peerID and the given peerID have
   * the same 20 bytes as ID.
   */
  public boolean sameID(PeerID pid)
  {
    boolean equal = true;
    for (int i = 0; equal && i < id.length; i++)
      equal = id[i] == pid.id[i];
    return equal;
  }

  /**
   * Two PeerIDs are equal when they have the same id, address and port.
   */
  public boolean equals(Object o)
  {
    if (o instanceof PeerID)
      {
        PeerID pid = (PeerID)o;

        return port == pid.port
          && address.equals(pid.address)
          && sameID(pid);
      }
    else
      return false;
  }

  /**
   * Compares port, address and id.
   */
  public int compareTo(Object o)
  {
    PeerID pid = (PeerID)o;

    int result = port - pid.port;
    if (result != 0)
      return result;

    result = address.hashCode() - pid.address.hashCode();
    if (result != 0)
      return result;

    for (int i = 0; i < id.length; i++)
      {
        result = id[i] - pid.id[i];
        if (result != 0)
          return result;
      }

    return 0;
  }

  /**
   * Returns the String "id@address" where id is the base64 encoded id
   * and address is the base64 dest (was the base64 hash of the dest) which
   * should match what the bytemonsoon tracker reports on its web pages.
   */
  public String toString()
  {
    int nonZero = 0;
    for (int i = 0; i < id.length; i++) {
        if (id[i] != 0) {
            nonZero = i;
            break;
        }
    }
    return Base64.encode(id, nonZero, id.length-nonZero).substring(0,4) + "@" + address.toBase64().substring(0,6);
  }

  /**
   * Encode an id as a hex encoded string and remove leading zeros.
   */
  public static String idencode(byte[] bs)
  {
    boolean leading_zeros = true;

    StringBuilder sb = new StringBuilder(bs.length*2);
    for (int i = 0; i < bs.length; i++)
      {
        int c = bs[i] & 0xFF;
        if (leading_zeros && c == 0)
          continue;
        else
          leading_zeros = false;

        if (c < 16)
          sb.append('0');
        sb.append(Integer.toHexString(c));
      }

    return sb.toString();
  }

}
