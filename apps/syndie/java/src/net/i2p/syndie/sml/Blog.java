package net.i2p.syndie.sml;

import java.util.List;

import net.i2p.data.DataHelper;

/** contains intermediary rendering state */
class Blog {
    public String name;
    public String hash;
    public String tag;
    public long entryId;
    public List locations;
    public int hashCode() { return -1; }
    public boolean equals(Object o) {
        Blog b = (Blog)o;
        return DataHelper.eq(hash, b.hash) && DataHelper.eq(tag, b.tag) && DataHelper.eq(name, b.name) 
               && DataHelper.eq(entryId, b.entryId) && DataHelper.eq(locations, b.locations);
    }
}