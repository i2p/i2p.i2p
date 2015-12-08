package net.i2p.data.router;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.i2p.data.DataStructure;

/**
 * The sorting of addresses in RIs
 *
 * @since 0.9.16 moved from DataHelper
 */
class SortHelper {

    /**
     *  Sort based on the Hash of the DataStructure.
     *  Warning - relatively slow.
     *  WARNING - this sort order must be consistent network-wide, so while the order is arbitrary,
     *  it cannot be changed.
     *  Why? Just because it has to be consistent so signing will work.
     *  DEPRECATED - Only used by RouterInfo.
     *
     *  @return a new list
     */
    public static <T extends DataStructure> List<T> sortStructures(Collection<T> dataStructures) {
        if (dataStructures == null) return Collections.emptyList();

        // This used to use Hash.toString(), which is insane, since a change to toString()
        // would break the whole network. Now use Hash.toBase64().
        // Note that the Base64 sort order is NOT the same as the raw byte sort order,
        // despite what you may read elsewhere.

        //ArrayList<DataStructure> rv = new ArrayList(dataStructures.size());
        //TreeMap<String, DataStructure> tm = new TreeMap();
        //for (DataStructure struct : dataStructures) {
        //    tm.put(struct.calculateHash().toString(), struct);
        //}
        //for (DataStructure struct : tm.values()) {
        //    rv.add(struct);
        //}
        ArrayList<T> rv = new ArrayList<T>(dataStructures);
        sortStructureList(rv);
        return rv;
    }

    /**
     *  See above.
     *  DEPRECATED - Only used by RouterInfo.
     *
     *  @since 0.9
     */
    static void sortStructureList(List<? extends DataStructure> dataStructures) {
        Collections.sort(dataStructures, new DataStructureComparator());
    }

    /**
     * See sortStructures() comments.
     * @since 0.8.3
     */
    private static class DataStructureComparator implements Comparator<DataStructure>, Serializable {
        public int compare(DataStructure l, DataStructure r) {
            return l.calculateHash().toBase64().compareTo(r.calculateHash().toBase64());
        }
    }
}
