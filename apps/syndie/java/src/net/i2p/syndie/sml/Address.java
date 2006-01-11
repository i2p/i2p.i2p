package net.i2p.syndie.sml;

import java.util.*;
import net.i2p.data.DataHelper;

/** contains intermediary rendering state */
class Address {
    public String name;
    public String schema;
    public String location;
    public String protocol;
    public int hashCode() { return -1; }
    public boolean equals(Object o) {
        Address a = (Address)o;
        return DataHelper.eq(schema, a.schema) && DataHelper.eq(location, a.location) && DataHelper.eq(protocol, a.protocol) && DataHelper.eq(name, a.name);
    }
}
