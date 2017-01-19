package net.i2p.router.news;

import net.i2p.data.DataHelper;

/**
 *  One CRL.
 *  Any String fields may be null.
 *
 *  @since 0.9.26
 */
public class CRLEntry {
    public String data;
    public String id;
    public long updated;

    @Override
    public boolean equals(Object o) {
        if(o == null)
            return false;
        if(!(o instanceof CRLEntry))
            return false;
        CRLEntry e = (CRLEntry) o;
        return updated == e.updated &&
               DataHelper.eq(id, e.id) &&
               DataHelper.eq(data, e.data);
    }
    
    @Override
    public int hashCode() {
        return (int) updated;
    }
}
