/*
 * Copyright 2010 Tom Gibara
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.tomgibara.crinch.hashing;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;

/**
 * <p>
 * A "minimal perfect hash" for Strings. After construction with an array of
 * <em>n</em> unique non-null strings, an instance of this class will return a
 * unique hash value <em>h</em> (0 &lt;= h &lt; n) for any string <em>s</em> in the
 * array. A negative has value will typically be returned for a string that is
 * not in the array.
 * </p>
 * 
 * <p>
 * However, the supplied array is <em>not</em> retained. This means that the
 * implementation cannot necessarily confirm that a string is not in the
 * supplied array. Where this implementation cannot distinguish that a string is
 * not in the array, a 'valid' hash value may be returned. Under no
 * circumstances will a hash value be returned that is greater than or equal to
 * <em>n</em>.
 * </p>
 * 
 * <p>
 * <strong>IMPORTANT NOTE:</strong> The array of strings supplied to the
 * constructor will be mutated: it is re-ordered so that
 * <code>hash(a[i]) == i</code>. Application code must generally use this
 * information to map hash values back onto the appropriate string value.
 * </p>
 * 
 * <p>
 * <strong>NOTE:</strong> Good performance of this algorithm is predicated on
 * string hash values being cached by the <code>String</code> class. Experience
 * indicates that is is a good assumption.
 * </p>
 * 
 * 
 * @author Tom Gibara
 */

public class PerfectStringHash implements Hash<String> {

    // statics

    /**
     * Comparator used to order the supplied string array. Hashcodes take
     * priority, we will do a binary search on those. Otherwise, lengths take
     * priority over character ordering because the hash algorithm prefers to
     * compare lengths, it's faster.
     */

    private static final Comparator<String> comparator = new Comparator<String>() {
        @Override
        public int compare(String s1, String s2) {
            final int h1 = s1.hashCode();
            final int h2 = s2.hashCode();
            if (h1 == h2) {
                final int d = s1.length() - s2.length();
                return d == 0 ? s1.compareTo(s2) : d;
            }
            return h1 < h2 ? -1 : 1;
        }
    };

    /**
     * Builds a (typically v. small) decision tree for distinguishing strings
     * that share the same hash value.
     * 
     * @param values
     *            the string values to distinguish
     * @param start
     *            the index from which the values should be read
     * @param length
     *            the number of string values that need to be distinguished
     * @param pivots
     *            the array that will hold our decision nodes
     * @param pivotIndex
     *            the index at which the tree should be written
     */
    private static void generatePivots(String[] values, int start, int length, int[] pivots, int pivotIndex) {
        final int capacity = Integer.highestOneBit(length - 1) << 1;
        final int depth = Integer.numberOfTrailingZeros(capacity);
        pivots[ pivotIndex << 1     ] = depth;
        pivots[(pivotIndex << 1) + 1] = length;
        pivotIndex++;
        //build the array
        for (int i = 0; i < depth; i++) {
            int step = capacity >> i;
        for (int j = (1 << (depth-i-1)) - 1; j < capacity; j += step) {
            final int part;
            final int comp;
            if (j >= length - 1) {
                part = Integer.MIN_VALUE;
                comp = 0;
            } else {
                final String v1 = values[start + j];
                final String v2 = values[start + j + 1];
                final int l1 = v1.length();
                final int l2 = v2.length();
                if (l1 == l2) {
                    int tPart = -1;
                    int tComp = -1;
                    for (int k = 0; k < l1; k++) {
                        final char c1 = v1.charAt(k);
                        final char c2 = v2.charAt(k);
                        if (c1 == c2) continue;
                        if (c1 < c2) { //must occur at some point because we have already checked that the two strings are unequal
                            tPart = k;
                            tComp = c1;
                        } else {
                            //shouldn't be possible - we've sorted the strings to avoid this case
                            throw new IllegalStateException();
                        }
                        break;
                    }
                    //check if we've been passed a duplicated value
                    if (tPart == -1) throw new IllegalArgumentException("duplicate value: " + v1);
                    part = tPart;
                    comp = tComp;
                } else {
                    part = -1;
                    comp = l1;
                }
            }
            pivots[ pivotIndex<<1     ] = part;
            pivots[(pivotIndex<<1) + 1] = comp;
            pivotIndex++;
        }
        }
    }

    // fields

    /**
     * The hashcodes of the supplied strings.
     */

    private final int[] hashes;

    /**
     * Stores two ints for every string, an offset into the pivot array (-1 if
     * not necessary) and the depth of the decision tree that is rooted there.
     */

    private final int[] offsets;

    /**
     * Stores two ints for every decision, the index at which a character
     * comparison needs to be made, followed by the character value to be
     * compared against; or -1 to indicate a length comparison, followed by the
     * length to be compared against.
     */

    private final int[] pivots;

    /**
     * Cache a range object which indicates the range of hash values generated.
     */

    private final HashRange range;

    /**
     * Constructs a minimal perfect string hashing over the supplied strings.
     * 
     * @param values
     *            an array of unique non-null strings that will be reordered
     *            such that <code>hash(values[i]) == i</code>.
     */

    public PerfectStringHash(final String[] values) {
        final int length = values.length;
        if (length == 0) throw new IllegalArgumentException("No values supplied");

        final int[] hashes = new int[length];
        final int[] offsets = new int[2 * length];
        final int[] runLengths = new int[length];

        //sort values so that we can assume ordering by hashcode, length and char[]
        Arrays.sort(values, comparator);

        //pull the hashcodes into an array for analysis
        for (int i = 0; i < length; i++) hashes[i] = values[i].hashCode();

        //test for unique hashes
        int offset = 0;
        if (length > 1) {
            int previousHash = hashes[0];
            int runLength = 1;
            for (int i = 1; i <= length; i++) {
                int currentHash = i == length ? ~previousHash : hashes[i];
                if (currentHash == previousHash) {
                    runLength++;
                } else {
                    if (runLength > 1) {
                        final int firstIndex = i - runLength;
                        for (int j = i - 1; j >= firstIndex; j--) {
                            runLengths[j] = runLength;
                            //offset points to the first node in decision tree
                            offsets[ j<<1     ] = offset;
                            //adjustment is number of indices to first duplicate
                            offsets[(j<<1) + 1] = j - firstIndex;
                        }
                        //extra one for recording depth
                        offset += (Integer.highestOneBit(runLength - 1) << 1);
                        runLength = 1;
                    } else {
                        runLengths[i-1] = 1;
                        offsets[(i-1)<<1] = -1;
                    }
                }
                previousHash = currentHash;
            }
        }

        //shortcut for when all hashes are unique
        if (offset == 0) {
            this.hashes = hashes;
            this.offsets = null;
            this.pivots = null;
            this.range = new HashRange(0, length - 1);
            return;
        }

        //build the decision trees
        final int[] pivots = new int[offset * 2];
        for (int i = 0; i < length;) {
            final int runLength = runLengths[i];
            if (runLength > 1)  generatePivots(values, i, runLength, pivots, offsets[i << 1]);
            i += runLength;
        }

        //setup our state
        this.pivots = pivots;
        this.offsets = offsets;
        this.hashes = hashes;
        this.range = new HashRange(0, length - 1);
    }

    // hash generator methods

    @Override
    public HashRange getRange() {
        return range;
    }

    @Override
    public BigInteger hashAsBigInt(String value) {
        return BigInteger.valueOf(hash(value));
    }

    //TODO decide whether to throw an IAE if -1 is returned from hash
    @Override
    public int hashAsInt(String value) {
        return hash(value);
    }

    @Override
    public long hashAsLong(String value) {
        return hash(value);
    }

    /**
     * Generates a hashcode for the supplied string.
     * 
     * @param value
     *            any string, not null
     * @return a minimal hashcode for the supplied string, or -1
     */

    private int hash(String value) {
        final int h = value.hashCode();
        final int index = Arrays.binarySearch(hashes, h);
        final int[] pivots = this.pivots;
        if (pivots == null || index < 0) return index;

        final int offset = offsets[index << 1];
        if (offset == -1) return index;

        final int depth = pivots[(offset << 1)    ];
        final int count = pivots[(offset << 1) + 1];
        int i = 0;
        for (int d = 0; d < depth; d++) {
            final int t = (offset + (1 << d) + i) << 1;
            final int part = pivots[t    ];
            final int comp = pivots[t + 1];
            final boolean right;
            if (part == Integer.MIN_VALUE) { //easy case - no right value
                right = false;
            } else if (part == -1) { //compare length
                right = value.length() > comp;
            } else { //lengths are equal, compare character
                right = value.charAt(part) > (char) comp;
            }
            i <<= 1;
            if (right) i++;
        }
        return i >= count ? -1 : index + i - offsets[(index << 1) + 1];
    }

}
