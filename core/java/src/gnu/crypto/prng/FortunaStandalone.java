/* Fortuna.java -- The Fortuna PRNG.
   Copyright (C) 2004  Free Software Foundation, Inc.

This file is part of GNU Crypto.

GNU Crypto is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the
Free Software Foundation; either version 2, or (at your option) any
later version.

GNU Crypto is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; see the file COPYING.  If not, write to the

   Free Software Foundation Inc.,
   51 Franklin Street, Fifth Floor,
   Boston, MA 02110-1301
   USA

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version.  */


package gnu.crypto.prng;

import gnu.crypto.hash.Sha256Standalone;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.i2p.crypto.CryptixAESKeyCache;
import net.i2p.crypto.CryptixRijndael_Algorithm;

/**
 * The Fortuna continuously-seeded pseudo-random number generator. This
 * generator is composed of two major pieces: the entropy accumulator
 * and the generator function. The former takes in random bits and
 * incorporates them into the generator's state. The latter takes this
 * base entropy and generates pseudo-random bits from it.
 *
 * <p>There are some things users of this class <em>must</em> be aware of:
 *
 * <dl>
 * <dt>Adding Random Data</dt>
 * <dd>This class does not do any polling of random sources, but rather
 * provides an interface for adding random events. Applications that use
 * this code <em>must</em> provide this mechanism. We use this design
 * because an application writer who knows the system he is targeting
 * is in a better position to judge what random data is available.</dd>
 *
 * <dt>Storing the Seed</dt>
 * <dd>This class implements {@link Serializable} in such a way that it
 * writes a 64 byte seed to the stream, and reads it back again when being
 * deserialized. This is the extent of seed file management, however, and
 * those using this class are encouraged to think deeply about when, how
 * often, and where to store the seed.</dd>
 * </dl>
 *
 * <p><b>References:</b></p>
 *
 * <ul>
 * <li>Niels Ferguson and Bruce Schneier, <i>Practical Cryptography</i>,
 * pp. 155--184. Wiley Publishing, Indianapolis. (2003 Niels Ferguson and
 * Bruce Schneier). ISBN 0-471-22357-3.</li>
 * </ul>
 *
 * Modified by jrandom for I2P to use a standalone gnu-crypto SHA256, Cryptix's AES,
 * to strip out some unnecessary dependencies and increase the buffer size.
 * Renamed from Fortuna to FortunaStandalone so it doesn't conflict with the
 * gnu-crypto implementation, which has been imported into GNU/classpath
 *
 */
public class FortunaStandalone extends BasePRNGStandalone implements Serializable, RandomEventListenerStandalone
{

  private static final long serialVersionUID = 0xFACADE;

  private static final int SEED_FILE_SIZE = 64;
  static final int NUM_POOLS = 32;
  static final int MIN_POOL_SIZE = 64;
  final Generator generator;
  final Sha256Standalone[] pools;
  long lastReseed;
  int pool;
  int pool0Count;
  int reseedCount;
  static long refillCount = 0;
  static long lastRefill = System.currentTimeMillis();

  public static final String SEED = "gnu.crypto.prng.fortuna.seed";

  public FortunaStandalone()
  {
    super("Fortuna i2p");
    generator = new Generator();
    pools = new Sha256Standalone[NUM_POOLS];
    for (int i = 0; i < NUM_POOLS; i++)
      pools[i] = new Sha256Standalone();
    lastReseed = 0;
    pool = 0;
    pool0Count = 0;
    allocBuffer();
  }
  protected void allocBuffer() {
    buffer = new byte[4*1024*1024]; //256]; // larger buffer to reduce churn
  }

  public void seed(byte val[]) {
      Map props = new HashMap(1);
      props.put(SEED, (Object)val);
      init(props);
      fillBlock();
  }
  
  public void setup(Map attributes)
  {
    lastReseed = 0;
    reseedCount = 0;
    pool = 0;
    pool0Count = 0;
    generator.init(attributes);
  }

  public void fillBlock()
  {
    long start = System.currentTimeMillis();
    if (pool0Count >= MIN_POOL_SIZE
        && System.currentTimeMillis() - lastReseed > 100)
      {
        reseedCount++;
        //byte[] seed = new byte[0];
        for (int i = 0; i < NUM_POOLS; i++)
          {
            if (reseedCount % (1 << i) == 0) {
              generator.addRandomBytes(pools[i].digest());
            }
          }
        lastReseed = System.currentTimeMillis();
      }
    generator.nextBytes(buffer);
    long now = System.currentTimeMillis();
    long diff = now-lastRefill;
    lastRefill = now;
    long refillTime = now-start;
    System.out.println("Refilling " + (++refillCount) + " after " + diff + " for the PRNG took " + refillTime);
  }

    @Override
  public void addRandomByte(byte b)
  {
    pools[pool].update(b);
    if (pool == 0)
      pool0Count++;
    pool = (pool + 1) % NUM_POOLS;
  }

    @Override
  public void addRandomBytes(byte[] buf, int offset, int length)
  {
    pools[pool].update(buf, offset, length);
    if (pool == 0)
      pool0Count += length;
    pool = (pool + 1) % NUM_POOLS;
  }

  public void addRandomEvent(RandomEventStandalone event)
  {
    if (event.getPoolNumber() < 0 || event.getPoolNumber() >= pools.length)
      throw new IllegalArgumentException("pool number out of range: "
                                         + event.getPoolNumber());
    pools[event.getPoolNumber()].update(event.getSourceNumber());
    pools[event.getPoolNumber()].update((byte) event.getData().length);
    byte data[] = event.getData();
    pools[event.getPoolNumber()].update(data, 0, data.length); //event.getData());
    if (event.getPoolNumber() == 0)
      pool0Count += event.getData().length;
  }

  // Reading and writing this object is equivalent to storing and retrieving
  // the seed.

  private void writeObject(ObjectOutputStream out) throws IOException
  {
    byte[] seed = new byte[SEED_FILE_SIZE];
    generator.nextBytes(seed);
    out.write(seed);
  }

  private void readObject(ObjectInputStream in) throws IOException
  {
    byte[] seed = new byte[SEED_FILE_SIZE];
    in.readFully(seed);
    generator.addRandomBytes(seed);
  }

  /**
   * The Fortuna generator function. The generator is a PRNG in its own
   * right; Fortuna itself is basically a wrapper around this generator
   * that manages reseeding in a secure way.
   */
  public static class Generator extends BasePRNGStandalone implements Cloneable
  {

    private static final int LIMIT = 1 << 20;

    private final Sha256Standalone hash;
    private final byte[] counter;
    private final byte[] key;
    /** current encryption key built from the keying material */
    private Object cryptixKey;
    private CryptixAESKeyCache.KeyCacheEntry cryptixKeyBuf;
    private boolean seeded;

    public Generator ()
    {
      super("Fortuna.generator.i2p");
      this.hash = new Sha256Standalone();
      counter = new byte[16]; //cipher.defaultBlockSize()];
      buffer = new byte[16]; //cipher.defaultBlockSize()];
      int keysize = 32;
      key = new byte[keysize];
      cryptixKeyBuf = CryptixAESKeyCache.createNew();
    }

        @Override
    public final byte nextByte()
    {
      byte[] b = new byte[1];
      nextBytes(b, 0, 1);
      return b[0];
    }

        @Override
    public final void nextBytes(byte[] out, int offset, int length)
    {
      if (!seeded)
        throw new IllegalStateException("generator not seeded");

      int count = 0;
      do
        {
          int amount = Math.min(LIMIT, length - count);
          super.nextBytes(out, offset+count, amount);
          count += amount;

          for (int i = 0; i < key.length; i += counter.length)
            {
              //fillBlock(); // inlined
              CryptixRijndael_Algorithm.blockEncrypt(counter, buffer, 0, 0, cryptixKey);
              incrementCounter();
              int l = Math.min(key.length - i, 16);//cipher.currentBlockSize());
              System.arraycopy(buffer, 0, key, i, l);
            }
          resetKey();
        }
      while (count < length);
      //fillBlock(); // inlined
      CryptixRijndael_Algorithm.blockEncrypt(counter, buffer, 0, 0, cryptixKey);
      incrementCounter();
      ndx = 0;
    }

        @Override
    public final void addRandomByte(byte b)
    {
      addRandomBytes(new byte[] { b });
    }

        @Override
    public final void addRandomBytes(byte[] seed, int offset, int length)
    {
      hash.update(key, 0, key.length);
      hash.update(seed, offset, length);
      byte[] newkey = hash.digest();
      System.arraycopy(newkey, 0, key, 0, Math.min(key.length, newkey.length));
      //hash.doFinal(key, 0);
      resetKey();
      incrementCounter();
      seeded = true;
    }

    public final void fillBlock()
    {
      ////i2p: this is not being checked as a microoptimization
      //if (!seeded)
      //  throw new IllegalStateException("generator not seeded");
      CryptixRijndael_Algorithm.blockEncrypt(counter, buffer, 0, 0, cryptixKey);
      incrementCounter();
    }

    public void setup(Map attributes)
    {
      seeded = false;
      Arrays.fill(key, (byte) 0);
      Arrays.fill(counter, (byte) 0);
      byte[] seed = (byte[]) attributes.get(SEED);
      if (seed != null)
        addRandomBytes(seed);
    }

    /**
     * Resets the cipher's key. This is done after every reseed, which
     * combines the old key and the seed, and processes that throigh the
     * hash function.
     */
    private final void resetKey()
    {
      try {
          cryptixKey = CryptixRijndael_Algorithm.makeKey(key, 16, cryptixKeyBuf);
      } catch (InvalidKeyException ike) {
          throw new Error("hrmf", ike);
      }
    }

    /**
     * Increment `counter' as a sixteen-byte little-endian unsigned integer
     * by one.
     */
    private final void incrementCounter()
    {
      for (int i = 0; i < counter.length; i++)
        {
          counter[i]++;
          if (counter[i] != 0)
            break;
        }
    }
  }
  
/*****
  public static void main(String args[]) {
      byte in[] = new byte[16];
      byte out[] = new byte[16];
      byte key[] = new byte[32];
      try {
          CryptixAESKeyCache.KeyCacheEntry buf = CryptixAESKeyCache.createNew();
          Object cryptixKey = CryptixRijndael_Algorithm.makeKey(key, 16, buf);
          long beforeAll = System.currentTimeMillis();
          for (int i = 0; i < 256; i++) {
              //long before =System.currentTimeMillis();
              for (int j = 0; j < 1024; j++)
                CryptixRijndael_Algorithm.blockEncrypt(in, out, 0, 0, cryptixKey);
              //long after = System.currentTimeMillis();
              //System.out.println("encrypting 16KB took " + (after-before));
          }
          long after = System.currentTimeMillis();
          System.out.println("encrypting 4MB took " + (after-beforeAll));
      } catch (Exception e) { e.printStackTrace(); }
      
      try {
        CryptixAESKeyCache.KeyCacheEntry buf = CryptixAESKeyCache.createNew();
        Object cryptixKey = CryptixRijndael_Algorithm.makeKey(key, 16, buf);
        byte data[] = new byte[4*1024*1024];
        long beforeAll = System.currentTimeMillis();
        //CryptixRijndael_Algorithm.ecbBulkEncrypt(data, data, cryptixKey);
          long after = System.currentTimeMillis();
          System.out.println("encrypting 4MB took " + (after-beforeAll));
      } catch (Exception e) { e.printStackTrace(); }
****/ /*
      FortunaStandalone f = new FortunaStandalone();
      java.util.HashMap props = new java.util.HashMap();
      byte initSeed[] = new byte[1234];
      new java.util.Random().nextBytes(initSeed);
      long before = System.currentTimeMillis();
      props.put(SEED, (byte[])initSeed);
      f.init(props);
      byte buf[] = new byte[8*1024];
      for (int i = 0; i < 64*1024; i++) {
        f.nextBytes(buf);
      }
      long time = System.currentTimeMillis() - before;
      System.out.println("512MB took " + time + ", or " + (8*64d)/((double)time/1000d) +"MBps");
       */
/*****
  }
*****/
}
