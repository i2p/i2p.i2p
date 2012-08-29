package net.i2p.router.util;

/*
 * Modified from:
 * http://www.lockergnome.com/awarberg/2007/04/22/random-iterator-in-java/
 * No license, free to use
 */

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

import net.i2p.util.RandomSource;

/**
 *
 *
 * This is some Java code I wrote for a school project to save some time when iterating in
 * random order over a part of list (until some condition becomes true):
 *
 * Here is a sample on how to use the code:
 *
    <pre>
        for(Iterator<Object> iter = new RandomIterator<Object>(myObjList); iter.hasNext();){
            Object o = iter.next();
            if(someCondition(o) )
                return o; // iteration stopped early
        }
    </pre>
 *
 * I wrote it to replace a Collection.shuffle call and this code gave us an overall increase in program execution speed of about 25%.
 * As the javadoc description says, you are better off calling Collection.shuffle if you need to iterate over the entire list. But if you may stop early this class can save you some time, as it did in our case.
 *
 * Provides a random iteration over the given list.
 *
 * This effect can be achieved by using Collections.shuffle,
 * which shuffles the entire collection in linear time.
 *
 * If the iteration process may end before all items
 * are processed, this class may give a speed increase
 * because the shuffling process is performed as items are requested
 * rather than in the beginning.
 *
 * I2P changes:
 *<pre>
 *   - Use BitSet instead of boolean[]
 *   - Use I2P RandomSource
 *   - Done check in next(), throw NSEE
 *   - Ensure lower and upper bounds are always clear
 *   - Replace unbounded loop in next(). It is now O(N) time, but now
 *     the iterator will tend to "clump" results and thus is not truly random.
 *     *** This class is not recommended for small Lists,
 *     *** or for iterating through a large portion of a List.
 *     *** Use Collections.shuffle() instead.
 *   - Add test code
 *</pre>
 *
 */
public class RandomIterator<E> implements Iterator<E> {
    /**
     * Mapping indicating which items were served (by index).
     * if served[i] then the item with index i in the list
     * has already been served.
     *
     * Note it is possible to save memory here by using
     * BitSet rather than a boolean array, however it will
     * increase the running time slightly.
     */
    private final BitSet served;

    /** The amount of items served so far */
    private int servedCount = 0;
    private final List<E> list;
    private final int LIST_SIZE;

    /**
    * The random number generator has a great influence
    * on the running time of this iterator.
    *
    * See, for instance,
    * <a href="http://www.qbrundage.com/michaelb/pubs/essays/random_number_generation" title="http://www.qbrundage.com/michaelb/pubs/essays/random_number_generation" target="_blank">http://www.qbrundage.com/michaelb/pubs/e&#8230;</a>
    * for some implementations, which are faster than java.util.Random.
    */
    private final Random rand = RandomSource.getInstance();

    /** Used to narrow the range to take random indexes from */
    private int lower, upper;

    private static final boolean isAndroid = System.getProperty("java.vendor").contains("Android");

    static {
        if (isAndroid)
            testAndroid();
    }

    public RandomIterator(List<E> list){
        this.list = list;
        LIST_SIZE = list.size();
        served = new BitSet(LIST_SIZE);
        upper = LIST_SIZE - 1;
    }

    public boolean hasNext() {
        return servedCount < LIST_SIZE;
    }

    public E next() {
        if (!hasNext())
            throw new NoSuchElementException();
        int range = upper - lower + 1;

        // This has unbounded behavior, even with lower/upper
        //int index;
        //do {
        //    index = lower + rand.nextInt(range);
        //} while (served.get(index));

        // This tends to "clump" results, escpecially toward the end of the iteration.
        // It also tends to leave the first and last few elements until the end.
        int start = lower + rand.nextInt(range);
        int index;
        if ((start % 2) == 0)  // coin flip
            index = served.nextClearBit(start);
        else
            index = previousClearBit(start);
        if (index < 0)
            throw new NoSuchElementException("shouldn't happen");
        servedCount++;
        served.set(index);

        // check if the range from which random values
        // are taken can be reduced
        // I2P - ensure lower and upper are always clear
        if (hasNext()) {
            if (index == lower)
                // workaround for Android ICS bug - see below
                lower = isAndroid ? nextClearBit(lower) : served.nextClearBit(lower);
            else if (index == upper)
                upper = previousClearBit(upper - 1);
        }
        return list.get(index);
    }

    /** just like nextClearBit() */
    private int previousClearBit(int n) {
        for (int i = n; i >= lower; i--) {
            if (!served.get(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     *  Workaround for bug in Android (ICS only?)
     *  http://code.google.com/p/android/issues/detail?id=31036
     *  @since 0.9.2
     */
    private int nextClearBit(int n) {
        for (int i = n; i <= upper; i++) {
            if (!served.get(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    public void remove() {
        throw new UnsupportedOperationException();
    }

    public static void main(String[] args) {
        testAndroid();
        test(0);
        test(1);
        test(2);
        test(1000);
    }

    private static void test(int n) {
        System.out.println("testing with " + n);
        List<Integer> l = new ArrayList(n);
        for (int i = 0; i < n; i++) {
            l.add(Integer.valueOf(i));
        }
        for (Iterator<Integer> iter = new RandomIterator(l); iter.hasNext(); ) {
            System.out.println(iter.next().toString());
        }
    }

    /**
     *  Test case from android ticket above
     *  @since 0.9.2
     */
    private static void testAndroid() {
        System.out.println("checking for Android bug");
        BitSet theBitSet = new BitSet(864);
        for (int exp =0; exp < 864; exp++) {
            int act = theBitSet.nextClearBit(0);
            if (exp != act) {
                System.out.println("Has Android bug!");
                System.out.println(String.format("Test failed for: exp=%d, act=%d", exp, act));
                return;
            }
            theBitSet.set(exp);
        }
    }
}
