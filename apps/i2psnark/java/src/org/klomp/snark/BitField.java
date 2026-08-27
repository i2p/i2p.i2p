/* BitField - Container of a byte array representing set and unset bits.
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

import java.util.Arrays;


/**
 * Container of a byte array representing set and unset bits.
 */
public class BitField
{

  private final byte[] bitfield;
  protected final int size;
  private int count;

  /**
   * Creates a new BitField that represents <code>size</code> unset bits.
   */
  public BitField(int size)
  {
    this.size = size;
    int arraysize = ((size-1)/8)+1;
    bitfield = new byte[arraysize];
  }

  /**
   * Creates a new BitField that represents <code>size</code> bits
   * as set by the given byte array. This will make a copy of the array.
   * Extra bytes will be ignored.
   *
   * @throws IndexOutOfBoundsException if give byte array is not large
   * enough.
   */
  public BitField(byte[] bitfield, int size)
  {
    this.size = size;
    int arraysize = ((size-1)/8)+1;
    this.bitfield = new byte[arraysize];
    
    // XXX - More correct would be to check that unused bits are
    // cleared or clear them explicitly ourselves.
    System.arraycopy(bitfield, 0, this.bitfield, 0, arraysize);

    for (int i = 0; i < size; i++)
      if (get(i))
        this.count++;
  }

  /**
   * For CompleteBitField. All methods except size() must be overridden.
   *
   * @since 0.9.71
   */
  protected BitField(int size, int dummy) {
    this.size = size;
    bitfield = null;
  }

  /**
   * This returns the actual byte array used.  Changes to this array
   * affect this BitField.  Note that some bits at the end of the byte
   * array are supposed to be always unset if they represent bits
   * bigger then the size of the bitfield.
   *
   * Caller should synch on this and copy!
   */
  public byte[] getFieldBytes()
  {
    return bitfield;
  }

  /**
   * Return the size of the BitField. The returned value is one bigger
   * then the last valid bit number (since bit numbers are counted
   * from zero).
   */
  public int size()
  {
    return size;
  }

  /**
   * Sets the given bit to true.
   *
   * @throws IndexOutOfBoundsException if bit is smaller then zero
   * bigger then size (inclusive).
   */
  public void set(int bit)
  {
    if (bit < 0 || bit >= size)
      throw new IndexOutOfBoundsException(Integer.toString(bit));
    int index = bit/8;
    int mask = 128 >> (bit % 8);
    synchronized(this) {
        if ((bitfield[index] & mask) == 0) {
            count++;
            bitfield[index] |= mask;
        }
    }
  }

  /**
   * Sets the given bit to false.
   *
   * @throws IndexOutOfBoundsException if bit is smaller then zero
   * bigger then size (inclusive).
   * @since 0.9.22
   */
  public void clear(int bit)
  {
    if (bit < 0 || bit >= size)
      throw new IndexOutOfBoundsException(Integer.toString(bit));
    int index = bit/8;
    int mask = 128 >> (bit % 8);
    synchronized(this) {
        if ((bitfield[index] & mask) != 0) {
            count--;
            bitfield[index] &= ~mask;
        }
    }
  }

  /**
   * Sets all bits to true.
   *
   * @since 0.9.21
   */
  public void setAll() {
      Arrays.fill(bitfield, (byte) 0xff);
      count = size;
  }

  /**
   * Return true if the bit is set or false if it is not.
   *
   * @throws IndexOutOfBoundsException if bit is smaller then zero
   * bigger then size (inclusive).
   */
  public boolean get(int bit)
  {
    if (bit < 0 || bit >= size)
      throw new IndexOutOfBoundsException(Integer.toString(bit));

    int index = bit/8;
    int mask = 128 >> (bit % 8);
    return (bitfield[index] & mask) != 0;
  }

  /**
   * Return the number of set bits.
   */
  public int count()
  {
    return count;
  }

  /**
   * Return true if all bits are set.
   */
  public boolean complete()
  {
    return count >= size;
  }

  /** @since 0.9.33 */
  @Override
  public int hashCode() {
      return (count << 16) ^ size;
  }

  /** @since 0.9.33 */
  @Override
  public boolean equals(Object o) {
      if (o == null || !(o instanceof BitField))
          return false;
      BitField bf = (BitField) o;
      return count == bf.count() &&
             size == bf.size() &&
             (count == size || Arrays.equals(bitfield, bf.getFieldBytes()));
  }

  @Override
  public String toString()
  {
    // generate a compact representation
    StringBuilder sb = new StringBuilder("BitField(");
    sb.append(size).append(")[");
    int first = -1;
    int last = -2;
    for (int i = 0; i < size; i++) {
        if (get(i)) {
            if (i == last + 1) {
                last++;
            } else  {
                first = i;
                last = i;
                sb.append(i);
            }
        } else if (last >= 0) {
            if (last != first) {
                sb.append('-');
                sb.append(i - 1);
            }
            sb.append(' ');
            first = -1;
            last = -1;
        }
    }
    if (last >= 0 && last != first) {
        sb.append('-');
        sb.append(last);
    }
    sb.append("]");
    return sb.toString();
  }

/*
  // toString() test
  public static void main(String[] args) {
      BitField bf = new BitField(128);
      net.i2p.util.RandomSource r = net.i2p.util.RandomSource.getInstance();
      for (int i = 0; i < 96; i++) {
          bf.set(r.nextInt(128));
      }
      // set last two to test final output
      bf.set(126);
      bf.set(127);
      // old way
      StringBuilder sb = new StringBuilder(1024);
      for (int i = 0; i < 128; i++) {
          if (bf.get(i)) {
              sb.append(i);
              sb.append(' ');
          }
      }
      System.out.println(sb);
      // new way
      System.out.println(bf.toString());
  }
*/
}
