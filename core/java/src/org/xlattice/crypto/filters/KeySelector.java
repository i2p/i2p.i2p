/* KeySelector.java */
package org.xlattice.crypto.filters;

/**
 * Given a key, populates arrays determining word and bit offsets into
 * a Bloom filter.
 * 
 * @author <A HREF="mailto:jddixon@users.sourceforge.net">Jim Dixon</A>
 *
 * BloomSHA1.java and KeySelector.java are BSD licensed from the xlattice
 * app - http://xlattice.sourceforge.net/
 * 
 * minor tweaks by jrandom, exposing unsynchronized access and 
 * allowing larger M and K.  changes released into the public domain.
 */
public class KeySelector {
   
    private int m;
    private int k;
    private byte[] b;
    private int offset; // index into b to select
    private int length; // length into b to select
    private int[] bitOffset;
    private int[] wordOffset;
    private BitSelector  bitSel;
    private WordSelector wordSel;
    
    public interface BitSelector {
        public void getBitSelectors();
    }
    public interface WordSelector {
        public void getWordSelectors();
    }
    /** AND with byte to expose index-many bits */
    public final static int[] UNMASK = { 
 // 0  1  2  3   4   5   6    7    8   9     10   11     12    13     14     15
    0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767};
    /** AND with byte to zero out index-many bits */
    public final static int[] MASK   = {
    ~0,~1,~3,~7,~15,~31,~63,~127,~255,~511,~1023,~2047,~4095,~8191,~16383,~32767};

    public final static int TWO_UP_15 = 32 * 1024;

    /** 
     * Creates a key selector for a Bloom filter.  When a key is presented
     * to the getOffsets() method, the k 'hash function' values are 
     * extracted and used to populate bitOffset and wordOffset arrays which
     * specify the k flags to be set or examined in the filter.  
     *
     * @param m    size of the filter as a power of 2
     * @param k    number of 'hash functions'
     * @param bitOffset array of k bit offsets (offset of flag bit in word)
     * @param wordOffset array of k word offsets (offset of word flag is in)
     */
    public KeySelector (int m, int k, int[] bitOffset, int [] wordOffset) {
        //if ( (m < 2) || (m > 20)|| (k < 1) 
        //             || (bitOffset == null) || (wordOffset == null)) {
        //    throw new IllegalArgumentException();
        //}
        this.m = m;
        this.k = k;
        this.bitOffset = bitOffset;
        this.wordOffset = wordOffset;
        bitSel  = new GenericBitSelector();
        wordSel = new GenericWordSelector();
    }
    
    /** 
     * Extracts the k bit offsets from a key, suitable for general values 
     * of m and k.
     */
    public class GenericBitSelector implements BitSelector {
        /** Do the extraction */
        public void getBitSelectors() {
            int curBit = 8 * offset; 
            int curByte;
            for (int j = 0; j < k; j++) {
                curByte = curBit / 8;
                int bitsUnused = ((curByte + 1) * 8) - curBit;    // left in byte

//              // DEBUG
//              System.out.println (
//                  "this byte = " + btoh(b[curByte])
//                  + ", next byte = " + btoh(b[curByte + 1])
//                  + "; curBit=" + curBit + ", curByte= " + curByte
//                  + ", bitsUnused=" + bitsUnused);
//              // END
                if (bitsUnused > 5) {
                    bitOffset[j] = ((0xff & b[curByte])
                                        >> (bitsUnused - 5)) & UNMASK[5];
//                  // DEBUG
//                  System.out.println(
//                      "    before shifting: " + btoh(b[curByte])
//                  + "\n    after shifting:  " 
//                          + itoh( (0xff & b[curByte]) >> (bitsUnused - 5))
//                  + "\n    mask:            " + itoh(UNMASK[5]) );
//                  // END
                } else if (bitsUnused == 5) {
                    bitOffset[j] = b[curByte] & UNMASK[5];
                } else {
                    bitOffset[j] = (b[curByte]          & UNMASK[bitsUnused])
                              | (((0xff & b[curByte + 1]) >> 3) 
                                                        &   MASK[bitsUnused]);
//                  // DEBUG
//                  System.out.println(
//                    "    contribution from first byte:  "
//                    + itoh(b[curByte] & UNMASK[bitsUnused])
//                + "\n    second byte: " + btoh(b[curByte + 1])
//                + "\n    shifted:     " + itoh((0xff & b[curByte + 1]) >> 3)
//                + "\n    mask:        " + itoh(MASK[bitsUnused])
//                + "\n    contribution from second byte: "
//                    + itoh((0xff & b[curByte + 1] >> 3) & MASK[bitsUnused]));
//                  // END
                }
//              // DEBUG
//              System.out.println ("    bitOffset[j] = " + bitOffset[j]);
//              // END
                curBit += 5;
            }
        } 
    }
    /** 
     * Extracts the k word offsets from a key.  Suitable for general
     * values of m and k.
     */
    public class GenericWordSelector implements WordSelector {
        /** Extract the k offsets into the word offset array */
        public void getWordSelectors() {
            int stride = m - 5;
            //assert true: stride<16;
            int curBit = (k * 5) + (offset * 8); 
            int curByte;
            for (int j = 0; j < k; j++) {
                curByte = curBit / 8;
                int bitsUnused = ((curByte + 1) * 8) - curBit;    // left in byte

//              // DEBUG
//              System.out.println (
//                  "curr 3 bytes: " + btoh(b[curByte]) 
//                  + (curByte < 19 ?
//                      " " + btoh(b[curByte + 1]) : "") 
//                  + (curByte < 18 ?
//                      " " + btoh(b[curByte + 2]) : "")
//                  + "; curBit=" + curBit + ", curByte= " + curByte
//                  + ", bitsUnused=" + bitsUnused);
//              // END

                if (bitsUnused > stride) {
                    // the value is entirely within the current byte
                    wordOffset[j] = ((0xff & b[curByte]) 
                                        >> (bitsUnused - stride)) 
                                                & UNMASK[stride];
                } else if (bitsUnused == stride) {
                    // the value fills the current byte
                    wordOffset[j] = b[curByte] & UNMASK[stride];
                } else {    // bitsUnused < stride
                    // value occupies more than one byte
                    // bits from first byte, right-aligned in result
                    wordOffset[j] = b[curByte] & UNMASK[bitsUnused];
//                  // DEBUG
//                  System.out.println("    first byte contributes "
//                          + itoh(wordOffset[j]));
//                  // END
                    // bits from second byte
                    int bitsToGet = stride - bitsUnused;
                    if (bitsToGet >= 8) {
                        // 8 bits from second byte
                        wordOffset[j] |= (0xff & b[curByte + 1]) << bitsUnused;
//                      // DEBUG
//                      System.out.println("    second byte contributes "
//                          + itoh(
//                          (0xff & b[curByte + 1]) << bitsUnused
//                      ));
//                      // END
                        
                        // bits from third byte
                        bitsToGet -= 8;
                        if (bitsToGet > 0) {
                            wordOffset[j] |= 
                                ((0xff & b[curByte + 2]) >> (8 - bitsToGet))
                                                    << (stride - bitsToGet) ;
//                          // DEBUG
//                          System.out.println("    third byte contributes " 
//                              + itoh(
//                              (((0xff & b[curByte + 2]) >> (8 - bitsToGet))
//                                                  << (stride - bitsToGet))
//                              ));
//                          // END
                        }
                    } else {
                        // all remaining bits are within second byte
                        wordOffset[j] |= ((b[curByte + 1] >> (8 - bitsToGet))
                                            & UNMASK[bitsToGet])
                                                << bitsUnused;
//                      // DEBUG
//                      System.out.println("    second byte contributes "
//                          + itoh(
//                          ((b[curByte + 1] >> (8 - bitsToGet))
//                              & UNMASK[bitsToGet])
//                                      << bitsUnused
//                          ));
//                      // END
                    }
                }
//              // DEBUG
//              System.out.println (
//                  "    wordOffset[" + j + "] = " + wordOffset[j]
//                  + ", "                     + itoh(wordOffset[j])
//              );
//              // END
                curBit += stride;
            }
        } 
    }
    /**
     * Given a key, populate the word and bit offset arrays, each
     * of which has k elements.
     * 
     * @param key cryptographic key used in populating the arrays
     */
    public void getOffsets (byte[] key) { getOffsets(key, 0, key.length); }
    public void getOffsets (byte[] key, int off, int len) {
        if (key == null) {
            throw new IllegalArgumentException("null key");
        }
        if (len < 20) {
            throw new IllegalArgumentException(
                "key must be at least 20 bytes long");
        }
        b = key;
        offset = off;
        length = len;
//      // DEBUG
//      System.out.println("KeySelector.getOffsets for " 
//                                          + BloomSHA1.keyToString(b));
//      // END
        bitSel.getBitSelectors();
        wordSel.getWordSelectors();
    }

    // DEBUG METHODS ////////////////////////////////////////////////
    String itoh(int i) {
        return BloomSHA1.itoh(i);
    }
    String btoh(byte b) {
        return BloomSHA1.btoh(b);
    }
}


