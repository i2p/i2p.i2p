package net.i2p.router.util;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.ConcurrentModificationException;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 *  A small, fast Set with a maximum size, backed by a fixed-size array.
 *  Unsynchronized, not thread-safe.
 *  Null elements are not permitted.
 *  Not appropriate for large Sets.
 *
 *  @since 0.9.25
 */
public class ArraySet<E> extends AbstractSet<E> implements Set<E> {
    public static final int MAX_CAPACITY = 32;
    private final Object[] _entries;
    private final boolean _throwOnFull;
    private int _size;
    private int _overflowIndex;
    private transient int modCount;

    /**
     *  A fixed capacity of MAX_CAPACITY.
     *  Adds over capacity will throw a SetFullException.
     */
    public ArraySet() {
        this(MAX_CAPACITY);
    }

    /**
     *  A fixed capacity of MAX_CAPACITY.
     *  Adds over capacity will throw a SetFullException.
     *  @throws SetFullException if more than MAX_CAPACITY unique elements in c.
     */
    public ArraySet(Collection<? extends E> c) {
        this();
        addAll(c);
    }

    /**
     *  Adds over capacity will throw a SetFullException.
     *
     *  @param capacity the maximum size
     *  @throws IllegalArgumentException if capacity less than 1 or more than MAX_CAPACITY.
     */
    public ArraySet(int capacity) {
        this(capacity, true);
    }

    /**
     *  If throwOnFull is false,
     *  adds over capacity will overwrite starting at slot zero.
     *  This breaks the AbstractCollection invariant that
     *  "a Collection will always contain the specified element after add() returns",
     *  but it prevents unexpected exceptions.
     *  If throwOnFull is true, adds over capacity will throw a SetFullException.
     *
     *  @param capacity the maximum size
     *  @throws IllegalArgumentException if capacity less than 1 or more than MAX_CAPACITY.
     */
    public ArraySet(int capacity, boolean throwOnFull) {
        if (capacity <= 0 || capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("bad capacity");
        _entries = new Object[capacity];
        _throwOnFull = throwOnFull;
    }

    /**
     *  @return -1 if not found or if o is null
     */
    private int indexOf(Object o) {
        if (o != null) {
            for (int i = 0; i < _size; i++) {
                if (o.equals(_entries[i]))
                    return i;
            }
        }
        return -1;
    }

    /**
     *  @throws SetFullException if throwOnFull was true in constructor
     *  @throws NullPointerException if o is null
     */
    @Override
    public boolean add(E o) {
        if (o == null)
            throw new NullPointerException();
        int i = indexOf(o);
        if (i >= 0) {
            _entries[i] = o;
            return false;
        }
        if (_size >= _entries.length) {
            if (_throwOnFull)
                throw new SetFullException();
            i = _overflowIndex++;
            if (i >= _entries.length) {
                i = 0;
                _overflowIndex = 0;
            }
        } else {
            modCount++;
            i = _size++;
        }
        _entries[i] = o;
        return true;
    }

    @Override
    public void clear() {
        if (_size != 0) {
            modCount++;
            for (int i = 0; i < _size; i++) {
                _entries[i] = null;
            }
            _size = 0;
        }
    }

    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    @Override
    public boolean isEmpty() {
        return _size <= 0;
    }

    @Override
    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0)
            return false;
        modCount++;
        _size--;
        for (int j = i; j < _size; j++) {
            _entries[j] = _entries[j + 1];
        }
        _entries[_size] = null;
        return true;
    }

    public int size() {
        return _size;
    }

    /**
     *  Supports remove.
     *  Supports comodification checks.
     */
    public Iterator<E> iterator() {
        return new ASIterator();
    }

    public static class SetFullException extends IllegalStateException {
        private static final long serialVersionUID = 9087390587254111L;
    }

    /**
     * Modified from CachedIteratorArrayList
     */
    private class ASIterator implements Iterator<E>, Serializable {
        /**
         * Index of element to be returned by subsequent call to next.
         */
        int cursor = 0;

        /**
         * Index of element returned by most recent call to next or
         * previous.  Reset to -1 if this element is deleted by a call
         * to remove.
         */
        int lastRet = -1;

        /**
         * The modCount value that the iterator believes that the backing
         * List should have.  If this expectation is violated, the iterator
         * has detected concurrent modification.
         */
        int expectedModCount = modCount;
        
        public boolean hasNext() {
            return cursor != _size;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            checkForComodification();
            try {
                int i = cursor;
                E next = (E) _entries[i];
                lastRet = i;
                cursor = i + 1;
                return next;
            } catch (IndexOutOfBoundsException e) {
                checkForComodification();
                throw new NoSuchElementException();
            }
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();

            try {
                ArraySet.this.remove(lastRet);
                if (lastRet < cursor)
                    cursor--;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }

        final void checkForComodification() {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }
    }

    /**
     *  About 3x faster than HashSet.
     */
/****
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void main(String[] args) {
        if (args.length > 0) {
            System.out.println("Test with overwrite");
            Set s = new ArraySet(4, false);
            for (int i = 0; i < args.length; i++) {
                System.out.println("Added " + args[i] + "? " + s.add(args[i]));
                System.out.println("Size is now " + s.size());
            }
            // toString tests the iterator
            System.out.println("Set now contains" + s);
            for (int i = 0; i < args.length; i++) {
                System.out.println("Removed " + args[i] + "? " + s.remove(args[i]));
                System.out.println("Size is now " + s.size());
            }
            System.out.println("\nTest with throw on full");
            s = new ArraySet(4);
            for (int i = 0; i < args.length; i++) {
                System.out.println("Added " + args[i] + "? " + s.add(args[i]));
                System.out.println("Size is now " + s.size());
            }
            // toString tests the iterator
            System.out.println("Set now contains" + s);
            for (int i = 0; i < args.length; i++) {
                System.out.println("Removed " + args[i] + "? " + s.remove(args[i]));
                System.out.println("Size is now " + s.size());
            }
        }

        //java.util.List c = java.util.Arrays.asList(new String[] {"foo", "bar", "baz", "splat", "barf", "baz", "moose", "bear", "cat", "dog"} );
        java.util.List c = java.util.Arrays.asList(new String[] {"foo", "bar"} );
        long start = System.currentTimeMillis();
        Set s = new java.util.HashSet(c);
        int runs = 10000000;
        for (int i = 0; i < runs; i++) {
            s = new java.util.HashSet(s);
        }
        System.out.println("HashSet took " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        s = new ArraySet(c);
        for (int i = 0; i < runs; i++) {
            s = new ArraySet(s);
        }
        System.out.println("ArraySet took " + (System.currentTimeMillis() - start));
    }
****/
}
