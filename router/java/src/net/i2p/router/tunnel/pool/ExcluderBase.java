package net.i2p.router.tunnel.pool;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;

/**
 *  A Set of Hashes that automatically adds to the
 *  Set in the contains() check.
 *
 *  So we don't need to generate the exclude set up front.
 *  Less object churn and copying.
 *
 *  @since 0.9.58
 */
abstract class ExcluderBase implements Set<Hash> {
    protected final Set<Hash> s;

    /**
     *  Automatically check if peer is connected
     *  and add the Hash to the set if not.
     *
     *  @param set not copied, contents will be modified by all methods
     */
    protected ExcluderBase(Set<Hash> set) {
        s = set;
    }

    /**
     *  Automatically check if peer is allowed
     *  and add the Hash to the set if not.
     *
     *  @param o a Hash
     *  @return true if peer should be excluded
     */
    public abstract boolean contains(Object o);

    public boolean add(Hash h) { return s.add(h); }
    public boolean addAll(Collection<? extends Hash> c) { return s.addAll(c); }
    public void clear() { s.clear(); }
    public boolean containsAll(Collection<?> c) { return s.containsAll(c); }
    public boolean equals(Object o) { return s.equals(o); }
    public int hashCode() { return s.hashCode(); }
    public boolean isEmpty() { return s.isEmpty(); }
    public Iterator<Hash> iterator() { return s.iterator(); }
    public boolean remove(Object o) { return s.remove(o); }
    public boolean removeAll(Collection<?> c) { return s.removeAll(c); }
    public boolean retainAll(Collection<?> c) { return s.retainAll(c); }
    public int size() { return s.size(); }
    public Object[] toArray() { return s.toArray(); }
    public <Hash> Hash[] toArray(Hash[] a) { return s.toArray(a); }

    @Override
    public String toString() {
         return getClass().getSimpleName() +
                " (" + s.size() + ") " +
                (s.size() <= 10 ? s.toString() : "");
    }
}
