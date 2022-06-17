package net.i2p.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;

/**
 *  Efficient implementation of a SortedSet stored in a fixed-size array.
 *  Much more space-efficient than TreeSet.
 *  Doesn't do copying like CopyOnWriteArraySet.
 *  Unmodifiable, thread-safe.
 *  Null elements are not permitted.
 *
 *  The Collection constructors are not recommended for large sets
 *  as the duplicate check is O(n**2).
 *
 *  @since 0.9.55
 */
public class UnmodifiableSortedSet<E> extends ArraySet<E> implements SortedSet<E> {

    private final Comparator<? super E> comp;
    private final boolean initialized;

    public UnmodifiableSortedSet(SortedSet<? extends E> c) {
        this(c, null);
    }

    public UnmodifiableSortedSet(SortedSet<? extends E> c, Comparator<? super E> comparator) {
        super(c, c.size());
        comp = comparator;
        // no sort required
        initialized = true;
    }

    public UnmodifiableSortedSet(Set<? extends E> c) {
        this(c, null);
    }

    public UnmodifiableSortedSet(Set<? extends E> c, Comparator<? super E> comparator) {
        super(c, c.size());
        comp = comparator;
        int sz = size();
        if (sz > 1)
            Arrays.sort((E[]) _entries, 0, sz, comp);
        initialized = true;
    }

    /**
     *  Warning: O(n**2)
     */
    public UnmodifiableSortedSet(Collection<? extends E> c) {
        this(c, null);
    }

    /**
     *  Warning: O(n**2)
     */
    public UnmodifiableSortedSet(Collection<? extends E> c, Comparator<? super E> comparator) {
        super(c, c.size());
        comp = comparator;
        int sz = size();
        if (sz > 1)
            Arrays.sort((E[]) _entries, 0, sz, comp);
        initialized = true;
    }

    public Comparator<? super E> comparator() { return comp; }

    public E first() {
        if (isEmpty())
            throw new NoSuchElementException();
        return (E) _entries[0];
    }

    public E last() {
        int sz = size();
        if (sz <= 0)
            throw new NoSuchElementException();
        return (E) _entries[sz - 1];
    }

    /**
     *  @throws UnsupportedOperationException
     */
    public SortedSet<E> headSet(E toElement) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException
     */
    public SortedSet<E> subSet(E fromElement, E toElement) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException
     */
    public SortedSet<E> tailSet(E fromElement) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException
     */
    @Override
    public boolean add(E o) {
        if (initialized)
            throw new UnsupportedOperationException();
        // for Collection constructor via addAll()
        return super.add(o);
    }

    /**
     *  @throws UnsupportedOperationException
     */
    @Override
    public void addUnique(E o) {
        if (initialized)
            throw new UnsupportedOperationException();
        // for Collection constructor via addAll()
        super.addUnique(o);
    }

    /**
     *  @throws UnsupportedOperationException
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    /**
     *  Overridden to do binary search
     */
    @Override
    protected int indexOf(Object o) {
        // don't do this if comp is not initialized and array is not sorted
        if (! initialized)
            return super.indexOf(o);
        if (o != null) {
            int rv = Arrays.binarySearch((E[]) _entries, 0, size(), (E) o, comp);
            if (rv >= 0)
                return rv;
        }
        return -1;
    }

/*
    public static void main(String[] args) {
        String[] test = new String[] {"foo", "bar", "baz", "bar", "baf", "bar", "boo", "foo", "a" };
        java.util.List<String> list = Arrays.asList(test);
        Set<String> set = new UnmodifiableSortedSet(list);
        System.out.println(set.toString());
        Set<String> set2 = new java.util.HashSet<String>(list);
        set = new UnmodifiableSortedSet(set2);
        System.out.println(set.toString());
    }
*/
}
