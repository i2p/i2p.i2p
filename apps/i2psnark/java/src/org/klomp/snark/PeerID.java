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

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 *  Store the address information about a peer.
 *  Prior to 0.8.1, an instantiation required a peer ID, and full Destination address.
 *  Starting with 0.8.1, to support compact tracker responses,
 *  a PeerID can be instantiated with a Destination Hash alone.
 *  The full destination lookup is deferred until getAddress() is called,
 *  and the PeerID is not required.
 *  Equality is now determined solely by the dest hash.
 */
public class PeerID implements Comparable
{
  private byte[] id;
  private Destination address;
  private final int port;
  private byte[] destHash;
  /** whether we have tried to get the dest from the hash - only do once */
  private boolean triedDestLookup;
  private final int hash;
  private final I2PSnarkUtil util;

  public PeerID(byte[] id, Destination address)
  {
    this.id = id;
    this.address = address;
    this.port = 6881;
    this.destHash = address.calculateHash().getData();
    hash = calculateHash();
    util = null;
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
    this.destHash = address.calculateHash().getData();
    hash = calculateHash();
    util = null;
  }

  /**
   * Creates a PeerID from a destHash
   * @param util for eventual destination lookup
   * @since 0.8.1
   */
  public PeerID(byte[] dest_hash, I2PSnarkUtil util) throws InvalidBEncodingException
  {
    // id and address remain null
    port = 6881;
    if (dest_hash.length != 32)
        throw new InvalidBEncodingException("bad hash length");
    destHash = dest_hash;
    hash = DataHelper.hashCode(dest_hash);
    this.util = util;
  }

  public byte[] getID()
  {
    return id;
  }

  /** for connecting out to peer based on desthash @since 0.8.1 */
  public void setID(byte[] xid)
  {
    id = xid;
  }

  /**
   *  Get the destination.
   *  If this PeerId was instantiated with a destHash,
   *  and we have not yet done so, lookup the full destination, which may take
   *  up to 10 seconds.
   *  @return Dest or null if unknown
   */
  public synchronized Destination getAddress()
  {
    if (address == null && destHash != null && !triedDestLookup) {
        String b32 = Base32.encode(destHash) + ".b32.i2p";
        address = util.getDestination(b32);
        triedDestLookup = true;
    }
    return address;
  }

  public int getPort()
  {
    return port;
  }

  /** @since 0.8.1 */
  public byte[] getDestHash()
  {
    return destHash;
  }

  private int calculateHash()
  {
    return DataHelper.hashCode(destHash);
  }

  /**
   * The hash code of a PeerID is the hashcode of the desthash
   */
    @Override
  public int hashCode()
  {
    return hash;
  }

  /**
   * Returns true if and only if this peerID and the given peerID have
   * the same destination hash
   */
  public boolean sameID(PeerID pid)
  {
    return DataHelper.eq(destHash, pid.getDestHash());
  }

  /**
   * Two PeerIDs are equal when they have the same dest hash
   */
    @Override
  public boolean equals(Object o)
  {
    if (o instanceof PeerID)
      {
        PeerID pid = (PeerID)o;

        return sameID(pid);
      }
    else
      return false;
  }

  /**
   * Compares port, address and id.
   * @deprecated unused? and will NPE now that address can be null?
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
    @Override
  public String toString()
  {
    if (id == null || address == null)
        return "unkn@" + Base64.encode(destHash).substring(0, 6);
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
