package net.i2p.syndie.sml;

import java.util.*;
import net.i2p.data.DataHelper;

/** contains intermediary rendering state */
class Link {
    public String schema;
    public String location;
    public int hashCode() { return -1; }
    public boolean equals(Object o) {
        Link l = (Link)o;
        return DataHelper.eq(schema, l.schema) && DataHelper.eq(location, l.location);
    }
}
