/*
 * NonUniqueProperties.java
 *
 * Created on April 9, 2005, 10:46 PM
 */

package net.i2p.aum;

import java.*;
import java.util.*;

/**
 * similar in some ways to Properties, except that duplicate keys
 * are allowed
 */
public class DupHashtable extends Hashtable {
    
    /** Creates a new instance of NonUniqueProperties */
    public DupHashtable() {
        super();
    }

    /** Adds a value to be stored against key */
    public void put(String key, String value) {

        if (!containsKey(key)) {
            put(key, new Vector());
        }

        ((Vector)get(key)).addElement(value);
    }

    /** retrieves a Vector of values for key, or empty vector if none */
    public Vector get(String key) {
        if (!containsKey(key)) {
            return new Vector();
        } else {
            return (Vector)super.get(key);
        }
    }

    /** returns the i-th value for given key, or dflt if key not found */
    public String get(String key, int idx, String dflt) {
        if (containsKey(key)) {
            return get(key, idx);
        } else {
            return dflt;
        }
    }

    /** returns the i-th value for given key
     * @throws ArrayIndexOutOfBoundsException if idx is out of range
     */
    public String get(String key, int idx) {
        return (String)((Vector)get(key)).get(idx);
    }

    /** returns the number of values for a given key */
    public int numValuesFor(String key) {
        if (!containsKey(key)) {
            return 0;
        } else {
            return ((Vector)get(key)).size();
        }
    }
}

