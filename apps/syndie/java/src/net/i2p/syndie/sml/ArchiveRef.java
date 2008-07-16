package net.i2p.syndie.sml;

import net.i2p.data.DataHelper;

/** contains intermediary rendering state */
class ArchiveRef {
    public String name;
    public String description;
    public String locationSchema;
    public String location;
    public int hashCode() { return -1; }
    public boolean equals(Object o) {
        ArchiveRef a = (ArchiveRef)o;
        return DataHelper.eq(name, a.name) && DataHelper.eq(description, a.description) 
               && DataHelper.eq(locationSchema, a.locationSchema) 
               && DataHelper.eq(location, a.location);
    }
}
