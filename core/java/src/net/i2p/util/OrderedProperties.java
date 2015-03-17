package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Properties map that has its keySet ordered consistently (via the key's lexicographical ordering).
 * This is useful in environments where maps must stay the same order (e.g. for signature verification)
 * This does NOT support remove against the iterators / etc.  
 *
 * @author zzz Rewritten
 *
 * Now unsorted until the keyset or entryset is requested.
 * The class is unsynchronized.
 * The keySet() and entrySet() methods return ordered sets.
 * Others - such as the enumerations values(), keys(), propertyNames() - do not.
 */
public class OrderedProperties extends Properties {

    public OrderedProperties() {
        super();
    }

    @Override
    public Set keySet() {
        return Collections.unmodifiableSortedSet(new TreeSet(super.keySet()));
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        TreeSet<Map.Entry<Object, Object>> rv = new TreeSet(new EntryComparator());
        rv.addAll(super.entrySet());
        return Collections.unmodifiableSortedSet(rv);
    }

    private static class EntryComparator implements Comparator<Map.Entry> {
         public int compare(Map.Entry l, Map.Entry r) {
             return ((String)l.getKey()).compareTo(((String)r.getKey()));
        }
    }
}
