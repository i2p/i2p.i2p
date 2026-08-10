package org.klomp.snark;

import java.util.Arrays;

/**
 *  A full bitfield, immutable, to save memory and time.
 *  Overrides all methods except size().
 *
 *  @since 0.9.71
 */
public class CompleteBitField extends BitField {

  /**
   * Creates a new BitField that represents <code>size</code> set bits.
   */
  public CompleteBitField(int size) {
      super(size, 0);
  }

  @Override
  public byte[] getFieldBytes() {
      int arraysize = ((size-1)/8)+1;
      byte[] rv = new byte[arraysize];
      Arrays.fill(rv, (byte) 0xff);
      return rv;
  }

  @Override
  public void set(int bit) {}

  @Override
  public void clear(int bit) {}

  @Override
  public void setAll() {}

  @Override
  public boolean get(int bit) { return true; }

  @Override
  public int count() { return size; }

  @Override
  public boolean complete() { return true; }

  @Override
  public int hashCode() {
      return (size << 16) ^ size;
  }

  @Override
  public boolean equals(Object o) {
      if (o == null || !(o instanceof BitField))
          return false;
      BitField bf = (BitField) o;
      return size == bf.count() &&
             size == bf.size();
  }

  @Override
  public String toString() {
      return "CompleteBitField size" + size;
  }

}
