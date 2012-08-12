package gnu.crypto.prng;

// ----------------------------------------------------------------------------
// Copyright (C) 2001, 2002, Free Software Foundation, Inc.
//
// This file is part of GNU Crypto.
//
// GNU Crypto is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2, or (at your option)
// any later version.
//
// GNU Crypto is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; see the file COPYING.  If not, write to the
//
//    Free Software Foundation Inc.,
//    51 Franklin Street, Fifth Floor,
//    Boston, MA 02110-1301
//    USA
//
// Linking this library statically or dynamically with other modules is
// making a combined work based on this library.  Thus, the terms and
// conditions of the GNU General Public License cover the whole
// combination.
//
// As a special exception, the copyright holders of this library give
// you permission to link this library with independent modules to
// produce an executable, regardless of the license terms of these
// independent modules, and to copy and distribute the resulting
// executable under terms of your choice, provided that you also meet,
// for each linked independent module, the terms and conditions of the
// license of that module.  An independent module is a module which is
// not derived from or based on this library.  If you modify this
// library, you may extend this exception to your version of the
// library, but you are not obligated to do so.  If you do not wish to
// do so, delete this exception statement from your version.
// ----------------------------------------------------------------------------

import java.util.Map;

/**
 * <p>An abstract class to facilitate implementing PRNG algorithms.</p>
 *
 * Modified slightly by jrandom for I2P (removing unneeded exceptions)
 * @version $Revision: 1.1 $
 */
public abstract class BasePRNGStandalone implements IRandomStandalone {

   // Constants and variables
   // -------------------------------------------------------------------------

   /** The canonical name prefix of the PRNG algorithm. */
   protected String name;

   /** Indicate if this instance has already been initialised or not. */
   protected boolean initialised;

   /** A temporary buffer to serve random bytes. */
   protected byte[] buffer;

   /** The index into buffer of where the next byte will come from. */
   protected int ndx;

   // Constructor(s)
   // -------------------------------------------------------------------------

   /**
    * <p>Trivial constructor for use by concrete subclasses.</p>
    *
    * @param name the canonical name of this instance.
    */
   protected BasePRNGStandalone(String name) {
      super();

      this.name = name;
      initialised = false;
      buffer = new byte[0];
   }

   // Class methods
   // -------------------------------------------------------------------------

   // Instance methods
   // -------------------------------------------------------------------------

   // IRandomStandalone interface implementation ----------------------------------------

   public String name() {
      return name;
   }

   public void init(Map attributes) {
      this.setup(attributes);

      ndx = 0;
      initialised = true;
   }

   public byte nextByte() throws IllegalStateException {//, LimitReachedException {
      if (!initialised) {
         throw new IllegalStateException();
      }
      return nextByteInternal();
   }

   public void nextBytes(byte[] out) throws IllegalStateException {//, LimitReachedException {
      nextBytes(out, 0, out.length);
   }

   public void nextBytes(byte[] out, int offset, int length)
   throws IllegalStateException //, LimitReachedException
   {
      if (!initialised)
         throw new IllegalStateException("not initialized");

      if (length == 0)
         return;

      if (offset < 0 || length < 0 || offset + length > out.length)
         throw new ArrayIndexOutOfBoundsException("offset=" + offset + " length="
                                                  + length + " limit=" + out.length);

      if (buffer == null)
          throw new IllegalStateException("Random is shut down - do you have a static ref?");
      if (ndx >= buffer.length) {
         fillBlock();
         ndx = 0;
      }
      int count = 0;
      while (count < length) {
         int amount = Math.min(buffer.length - ndx, length - count);
         System.arraycopy(buffer, ndx, out, offset+count, amount);
         count += amount;
         ndx += amount;
         if (ndx >= buffer.length) {
            fillBlock();
            ndx = 0;
         }
      }
   }

   public void addRandomByte(byte b) {
      throw new UnsupportedOperationException("random state is non-modifiable");
   }

   public void addRandomBytes(byte[] buffer) {
      addRandomBytes(buffer, 0, buffer.length);
   }

   public void addRandomBytes(byte[] buffer, int offset, int length) {
      throw new UnsupportedOperationException("random state is non-modifiable");
   }

   // Instance methods
   // -------------------------------------------------------------------------

   public boolean isInitialised() {
      return initialised;
   }

   private byte nextByteInternal() {//throws LimitReachedException {
      if (buffer == null)
          throw new IllegalStateException("Random is shut down - do you have a static ref?");
      if (ndx >= buffer.length) {
         this.fillBlock();
         ndx = 0;
      }

      return buffer[ndx++];
   }

   // abstract methods to implement by subclasses -----------------------------

  @Override
  public Object clone() throws CloneNotSupportedException
  {
    return super.clone();
  }

   public abstract void setup(Map attributes);

   public abstract void fillBlock(); //throws LimitReachedException;
}
