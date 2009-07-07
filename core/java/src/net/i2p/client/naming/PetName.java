package net.i2p.client.naming;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.data.DataHelper;

/**
 *
 */
public class PetName {
    private String _name;
    private String _network;
    private String _protocol;
    private List _groups;
    private boolean _isPublic;
    private String _location;
    
    public PetName() {
        this(null, null, null, null);
    }
    public PetName(String name, String network, String protocol, String location) {
        _name = name;
        _network = network;
        _protocol = protocol;
        _location = location;
        _groups = new ArrayList();
        _isPublic = false;
    }
    /**
     * @param dbLine name:network:protocol:isPublic:group1,group2,group3:location
     */
    public PetName(String dbLine) {
        _groups = new ArrayList();
        StringTokenizer tok = new StringTokenizer(dbLine, ":\n", true);
        int tokens = tok.countTokens();
        //System.out.println("Tokens: " + tokens);
        if (tokens < 7) {
            return;
        }
        String s = tok.nextToken();
        if (":".equals(s)) {
            _name = null;
        } else {
            _name = s;
            s = tok.nextToken(); // skip past the :
        }
        s = tok.nextToken();
        if (":".equals(s)) {
            _network = null;
        } else {
            _network = s;
            s = tok.nextToken(); // skip past the :
        }
        s = tok.nextToken();
        if (":".equals(s)) {
            _protocol = null;
        } else {
            _protocol = s;
            s = tok.nextToken(); // skip past the :
        }
        s = tok.nextToken();
        if (":".equals(s)) {
            _isPublic = false;
        } else {
            if ("true".equals(s))
                _isPublic = true;
            else
                _isPublic = false;
            s = tok.nextToken(); // skip past the :
        }
        s = tok.nextToken();
        if (":".equals(s)) {
            // noop
        } else {
            StringTokenizer gtok = new StringTokenizer(s, ",");
            while (gtok.hasMoreTokens())
                _groups.add(gtok.nextToken().trim());
            s = tok.nextToken(); // skip past the :
        }
        while (tok.hasMoreTokens()) {
            if (_location == null)
                _location = tok.nextToken();
            else
                _location = _location + tok.nextToken();
        }
    }
    
    public String getName() { return _name; }
    public String getNetwork() { return _network; }
    public String getProtocol() { return _protocol; }
    public String getLocation() { return _location; }
    public boolean getIsPublic() { return _isPublic; }
    public int getGroupCount() { return _groups.size(); }
    public String getGroup(int i) { return (String)_groups.get(i); }
    
    public void setName(String name) { _name = name; }
    public void setNetwork(String network) { _network = network; }
    public void setProtocol(String protocol) { _protocol = protocol; }
    public void setLocation(String location) { _location = location; }
    public void setIsPublic(boolean pub) { _isPublic = pub; }
    public void addGroup(String name) { 
        if ( (name != null) && (name.length() > 0) && (!_groups.contains(name)) )
            _groups.add(name);
    }
    public void removeGroup(String name) { _groups.remove(name); }
    public void setGroups(String groups) {
        if (groups != null) {
            _groups.clear();
            StringTokenizer tok = new StringTokenizer(groups, ", \t");
            while (tok.hasMoreTokens())
                addGroup(tok.nextToken().trim());
        } else {
            _groups.clear();
        }
    }
    public boolean isMember(String group) {
        for (int i = 0; i < getGroupCount(); i++)
            if (getGroup(i).equals(group))
                return true;
        return false;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        if (_name != null) buf.append(_name.trim());
        buf.append(':');
        if (_network != null) buf.append(_network.trim());
        buf.append(':');
        if (_protocol != null) buf.append(_protocol.trim());
        buf.append(':').append(_isPublic).append(':');
        if (_groups != null) {
            for (int i = 0; i < _groups.size(); i++) {
                buf.append(((String)_groups.get(i)).trim());
                if (i + 1 < _groups.size())
                    buf.append(',');
            }
        }
        buf.append(':');
        if (_location != null) buf.append(_location.trim());
        return buf.toString();
    }
    
    @Override
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof PetName) ) return false;
        PetName pn = (PetName)obj;
        return DataHelper.eq(_name, pn._name) &&
               DataHelper.eq(_location, pn._location) &&
               DataHelper.eq(_network, pn._network) &&
               DataHelper.eq(_protocol, pn._protocol);
    }
    @Override
    public int hashCode() {
        int rv = 0;
        rv += DataHelper.hashCode(_name);
        rv += DataHelper.hashCode(_location);
        rv += DataHelper.hashCode(_network);
        rv += DataHelper.hashCode(_protocol);
        return rv;
    }
    
    public static void main(String args[]) {
        test("a:b:c:true:e:f");
        test("a:::true::d");
        test("a:::true::");
        test("a:b::true::");
        test(":::trye::");
        test("a:b:c:true:e:http://foo.bar");
    }
    private static void test(String line) {
        PetName pn = new PetName(line);
        String val = pn.toString();
        System.out.println("OK? " + val.equals(line) + ": " + line + " [" + val + "]");
    }
}
