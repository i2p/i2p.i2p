package net.i2p.client.naming;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 *  deprecated unused but can be instantiated through I2PAppContext
 */
public class PetNameDB {
    /** name (String) to PetName mapping */
    private final Map _names;
    private String _path;
    
    public PetNameDB() {
        _names = Collections.synchronizedMap(new HashMap());
    }

    public PetName getByName(String name) { 
        if ( (name == null) || (name.length() <= 0) ) return null;
        return (PetName)_names.get(name.toLowerCase()); 
    }
    public void add(PetName pn) { 
        if ( (pn == null) || (pn.getName() == null) ) return;
        _names.put(pn.getName().toLowerCase(), pn); 
    }
    public void clear() { _names.clear(); }
    public boolean contains(PetName pn) { return _names.containsValue(pn); }
    public boolean containsName(String name) { 
        if ( (name == null) || (name.length() <= 0) ) return false;
        return _names.containsKey(name.toLowerCase()); 
    }
    public boolean isEmpty() { return _names.isEmpty(); }
    public Iterator iterator() { return new ArrayList(_names.values()).iterator(); }
    public void remove(PetName pn) { 
        if (pn != null) _names.remove(pn.getName().toLowerCase());
    }
    public void removeName(String name) { 
        if (name != null) _names.remove(name.toLowerCase()); 
    }
    public int size() { return _names.size(); }
    public Set getNames() { return new HashSet(_names.keySet()); }
    public List getGroups() {
        List rv = new ArrayList();
        for (Iterator iter = iterator(); iter.hasNext(); ) {
            PetName name = (PetName)iter.next();
            for (int i = 0; i < name.getGroupCount(); i++)
                if (!rv.contains(name.getGroup(i)))
                    rv.add(name.getGroup(i));
        }
        return rv;
    }
    
    public PetName getByLocation(String location) { 
        if (location == null) return null;
        synchronized (_names) {
            for (Iterator iter = iterator(); iter.hasNext(); ) {
                PetName name = (PetName)iter.next();
                if ( (name.getLocation() != null) && (name.getLocation().trim().equals(location.trim())) )
                    return name;
            }
        }
        return null;
    }
    
    public void load(String location) throws IOException {
        _path = location;
        File f = new File(location);
        if (!f.exists()) return;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line = null;
            while ( (line = in.readLine()) != null) {
                PetName name = new PetName(line);
                if (name.getName() != null)
                    add(name);
            }
        } finally {
            in.close();
        }
    }
    
    public void store(String location) throws IOException {
        Writer out = null;
        try {
            out = new OutputStreamWriter(new FileOutputStream(location), "UTF-8");
            for (Iterator iter = iterator(); iter.hasNext(); ) {
                PetName name = (PetName)iter.next();
                if (name != null)
                    out.write(name.toString() + "\n");
            }
        } finally {
            out.close();
        }
    }
    
    public void store() throws IOException {
        if (_path != null) {
            store(_path);
        }
    }
}
