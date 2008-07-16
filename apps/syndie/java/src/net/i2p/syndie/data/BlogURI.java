package net.i2p.syndie.data;

import java.util.Comparator;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 *
 */
public class BlogURI {
    private Hash _blogHash;
    private long _entryId;
    
    public static final Comparator COMPARATOR = new NewestFirstComparator();
    
    public BlogURI() {
        this(null, -1);
    }
    public BlogURI(Hash blogHash, long entryId) {
        _blogHash = blogHash;
        _entryId = entryId;
    }
    public BlogURI(String uri) {
        if (uri.startsWith("blog://")) {
            int off = "blog://".length();
            _blogHash = new Hash(Base64.decode(uri.substring(off, off+44))); // 44 chars == base64(32 bytes)
            int entryStart = uri.indexOf('/', off+1);
            if (entryStart < 0) {
                _entryId = -1;
            } else {
                try {
                    _entryId = Long.parseLong(uri.substring(entryStart+1).trim());
                } catch (NumberFormatException nfe) {
                    _entryId = -1;
                }
            }
        } else if (uri.startsWith("entry://")) {
            int off = "entry://".length();
            _blogHash = new Hash(Base64.decode(uri.substring(off, off+44))); // 44 chars == base64(32 bytes)
            int entryStart = uri.indexOf('/', off+1);
            if (entryStart < 0) {
                _entryId = -1;
            } else {
                try {
                    _entryId = Long.parseLong(uri.substring(entryStart+1).trim());
                } catch (NumberFormatException nfe) {
                    _entryId = -1;
                }
            }
        } else {
            _blogHash = null;
            _entryId = -1;
        }
    }
    
    public Hash getKeyHash() { return _blogHash; }
    public long getEntryId() { return _entryId; }
    
    public void setKeyHash(Hash hash) { _blogHash = hash; }
    public void setEntryId(long id) { _entryId = id; }
    
    public String toString() {
        if ( (_blogHash == null) || (_blogHash.getData() == null) )
            return "";
        StringBuffer rv = new StringBuffer(64);
        rv.append("blog://").append(Base64.encode(_blogHash.getData()));
        rv.append('/');
        if (_entryId >= 0)
            rv.append(_entryId);
        return rv.toString();
    }
    
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj.getClass() != getClass()) return false;
        return DataHelper.eq(_entryId, ((BlogURI)obj)._entryId) && 
               DataHelper.eq(_blogHash, ((BlogURI)obj)._blogHash);
    }
    public int hashCode() {
        int rv = (int)((_entryId >>> 32) & 0x7FFFFFFF);
        rv += (_entryId & 0x7FFFFFFF);
        
        if (_blogHash != null)
            rv += _blogHash.hashCode();
        return rv;
    }
    
    public static void main(String args[]) {
        test("http://asdf/");
        test("blog://Vq~AlW-r7OM763okVUFIDvVFzxOjpNNsAx0rFb2yaE8=");
        test("blog://Vq~AlW-r7OM763okVUFIDvVFzxOjpNNsAx0rFb2yaE8=/");
        test("blog://Vq~AlW-r7OM763okVUFIDvVFzxOjpNNsAx0rFb2yaE8=/123456789");
        test("entry://Vq~AlW-r7OM763okVUFIDvVFzxOjpNNsAx0rFb2yaE8=/");
        test("entry://Vq~AlW-r7OM763okVUFIDvVFzxOjpNNsAx0rFb2yaE8=/123456789");
    }
    private static void test(String uri) {
        BlogURI u = new BlogURI(uri);
        if (!u.toString().equals(uri))
            System.err.println("Not a match: [" + uri + "] != [" + u.toString() + "]");
    }

    /**
     * Order the BlogURIs by entryId, with the highest entryId first
     */
    private static class NewestFirstComparator implements Comparator {        
        public int compare(Object lhs, Object rhs) {
            BlogURI l = (BlogURI)lhs;
            BlogURI r = (BlogURI)rhs;
            if (l.getEntryId() > r.getEntryId())
                return -1;
            else if (l.getEntryId() < r.getEntryId())
                return 1;
            else // same date, compare by blog hash (aka randomly)
                return DataHelper.compareTo(l.getKeyHash().getData(), r.getKeyHash().getData());
        }
    }
}
