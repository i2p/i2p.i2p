package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;

/**
 * Properties map that has its keySet ordered consistently (via the key's lexicographical ordering).
 * This is useful in environments where maps must stay the same order (e.g. for signature verification)
 * This does NOT support remove against the iterators / etc.  
 *
 */
public class OrderedProperties extends Properties {
    private final static Log _log = new Log(OrderedProperties.class);
    /** ordered set of keys (strings) stored in the properties */
    private TreeSet _order;
    /** simple key=value mapping of the actual data */
    private Map _data;

    /** lock this before touching _order or _data */
    private Object _lock = new Object();

    public OrderedProperties() {
        super();
        _order = new TreeSet();
        _data = new HashMap();
    }

    public boolean contains(Object value) {
        return containsValue(value);
    }

    public boolean containsKey(Object key) {
        synchronized (_lock) {
            return _data.containsKey(key);
        }
    }

    public boolean containsValue(Object value) {
        synchronized (_lock) {
            return _data.containsValue(value);
        }
    }

    public boolean equals(Object obj) {
        if ((obj != null) && (obj instanceof OrderedProperties)) {
            synchronized (_lock) {
                return _data.equals(obj);
            }
        } 
        
        return false;
    }

    public int hashCode() {
        synchronized (_lock) {
            return _data.hashCode();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public String getProperty(String key) {
        return getProperty((Object) key);
    }

    public Object get(Object key) {
        return getProperty(key);
    }

    private String getProperty(Object key) {
        if (key == null) return null;
        synchronized (_lock) {
            Object rv = _data.get(key);
            if ((rv != null) && (rv instanceof String)) return (String) rv;
            
            return null;
        }
    }

    public Object setProperty(String key, String val) {
        if ((key == null) || (val == null)) throw new IllegalArgumentException("Null values are not supported");
        synchronized (_lock) {
            _order.add(key);
            Object rv = _data.put(key, val);
            return rv;
        }
    }

    public Object put(Object key, Object val) {
        if ((key == null) || (val == null)) throw new NullPointerException("Null values or keys are not allowed");
        if (!(key instanceof String) || !(val instanceof String))
            throw new IllegalArgumentException("Key or value is not a string");
        return setProperty((String) key, (String) val);
    }

    public void putAll(Map data) {
        if (data == null) return;
        for (Iterator iter = data.keySet().iterator(); iter.hasNext();) {
            Object key = iter.next();
            Object val = data.get(key);
            put(key, val);
        }
    }

    public Object clone() {
        synchronized (_lock) {
            OrderedProperties rv = new OrderedProperties();
            rv.putAll(this);
            return rv;
        }
    }

    public void clear() {
        synchronized (_lock) {
            _order.clear();
            _data.clear();
        }
    }

    public int size() {
        synchronized (_lock) {
            return _order.size();
        }
    }

    public Object remove(Object key) {
        synchronized (_lock) {
            _order.remove(key);
            Object rv = _data.remove(key);
            return rv;
        }
    }

    public Set keySet() {
        synchronized (_lock) {
            return Collections.unmodifiableSortedSet((TreeSet) _order.clone());
        }
    }

    public Set entrySet() {
        synchronized (_lock) {
            return Collections.unmodifiableSet(buildEntrySet((TreeSet) _order.clone()));
        }
    }

    public Collection values() {
        synchronized (_lock) {
            Collection values = new ArrayList(_data.size());
            for (Iterator iter = _data.values().iterator(); iter.hasNext();) {
                values.add(iter.next());
            }
            return values;
        }
    }

    public Enumeration elements() {
        return Collections.enumeration(values());
    }

    public Enumeration keys() {
        return Collections.enumeration(keySet());
    }

    public Enumeration propertyNames() {
        return Collections.enumeration(keySet());
    }

    public void list(PrintStream out) { // nop
    }

    public void list(PrintWriter out) { // nop
    }

    public void load(InputStream in) { // nop
    }

    //public void save(OutputStream out, String header) {}
    public void store(OutputStream out, String header) { // nop
    }

    private Set buildEntrySet(Set data) {
        TreeSet ts = new TreeSet();
        for (Iterator iter = data.iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = getProperty(key);
            ts.add(new StringMapEntry(key, val));
        }
        return ts;
    }

    private static class StringMapEntry implements Map.Entry, Comparable {
        private Object _key;
        private Object _value;

        public StringMapEntry(String key, String val) {
            _key = key;
            _value = val;
        }

        public Object getKey() {
            return _key;
        }

        public Object getValue() {
            return _value;
        }

        public Object setValue(Object value) {
            Object old = _value;
            _value = value;
            return old;
        }

        public int compareTo(Object o) {
            if (o == null) return -1;
            if (o instanceof StringMapEntry) return ((String) getKey()).compareTo((String)((StringMapEntry) o).getKey());
            if (o instanceof String) return ((String) getKey()).compareTo((String)o);
            return -2;
        }

        public boolean equals(Object o) {
            if (o == null) return false;
            if (!(o instanceof StringMapEntry)) return false;
            StringMapEntry e = (StringMapEntry) o;
            return DataHelper.eq(e.getKey(), getKey()) && DataHelper.eq(e.getValue(), getValue());
        }
    }

    ///
    /// tests
    ///

    public static void main(String args[]) {
        test(new OrderedProperties());
        _log.debug("After ordered");
        //test(new Properties());
        //System.out.println("After normal");
        test2();
        testThrash();
    }

    private static void test2() {
        OrderedProperties p = new OrderedProperties();
        p.setProperty("a", "b");
        p.setProperty("c", "d");
        OrderedProperties p2 = new OrderedProperties();
        try {
            p2.putAll(p);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        _log.debug("After test2");
    }

    private static void test(Properties p) {
        for (int i = 0; i < 10; i++)
            p.setProperty(i + "asdfasdfasdf", "qwerasdfqwer");
        for (Iterator iter = p.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = p.getProperty(key);
            _log.debug("[" + key + "] = [" + val + "]");
        }
        p.remove(4 + "asdfasdfasdf");
        _log.debug("After remove");
        for (Iterator iter = p.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = p.getProperty(key);
            _log.debug("[" + key + "] = [" + val + "]");
        }
        try {
            p.put("nullVal", null);
            _log.debug("Null put did NOT fail!");
        } catch (NullPointerException npe) {
            _log.debug("Null put failed correctly");
        }
    }

    /**
     * Set 100 concurrent threads trying to do some operations against a single
     * OrderedProperties object a thousand times.  Hopefully this will help 
     * flesh out any synchronization issues.
     *
     */
    private static void testThrash() {
        OrderedProperties prop = new OrderedProperties();
        for (int i = 0; i < 100; i++)
            prop.setProperty(i + "", i + " value");
        _log.debug("Thrash properties built");
        for (int i = 0; i < 100; i++)
            thrash(prop, i);
    }

    private static void thrash(Properties props, int i) {
        I2PThread t = new I2PThread(new Thrash(props));
        t.setName("Thrash" + i);
        t.start();
    }

    private static class Thrash implements Runnable {
        private Properties _props;

        public Thrash(Properties props) {
            _props = props;
        }

        public void run() {
            int numRuns = 1000;
            _log.debug("Begin thrashing " + numRuns + " times");
            for (int i = 0; i < numRuns; i++) {
                Set keys = _props.keySet();
                //_log.debug("keySet fetched");
                int cur = 0;
                for (Iterator iter = keys.iterator(); iter.hasNext();) {
                    Object o = iter.next();
                    Object val = _props.get(o);
                    //_log.debug("Value " + cur + " fetched");
                    cur++;
                }
                //_log.debug("Values fetched");
                int size = _props.size();
                _log.debug("Size calculated");
            }
            _log.debug("Done thrashing " + numRuns + " times");
        }
    }
}