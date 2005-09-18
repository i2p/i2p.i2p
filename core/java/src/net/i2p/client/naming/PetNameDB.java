package net.i2p.client.naming;

import java.io.*;
import java.util.*;


/**
 *
 */
public class PetNameDB {
    /** name (String) to PetName mapping */
    private Map _names;
    
    public PetNameDB() {
        _names = Collections.synchronizedMap(new HashMap());
    }

    public PetName get(String name) { return (PetName)_names.get(name); } 
    public boolean exists(String name) { return _names.containsKey(name); }
    public void set(String name, PetName pn) { _names.put(name, pn); }
    public void remove(String name) { _names.remove(name); }
    public Set getNames() { return new HashSet(_names.keySet()); }
    public List getGroups() {
        List rv = new ArrayList();
        for (Iterator iter = new HashSet(_names.values()).iterator(); iter.hasNext(); ) {
            PetName name = (PetName)iter.next();
            for (int i = 0; i < name.getGroupCount(); i++)
                if (!rv.contains(name.getGroup(i)))
                    rv.add(name.getGroup(i));
        }
        return rv;
    }
    
    public String getNameByLocation(String location) { 
        if (location == null) return null;
        synchronized (_names) {
            for (Iterator iter = _names.values().iterator(); iter.hasNext(); ) {
                PetName name = (PetName)iter.next();
                if ( (name.getLocation() != null) && (name.getLocation().trim().equals(location.trim())) )
                    return name.getName();
            }
        }
        return null;
    }
    
    public void load(String location) throws IOException {
        File f = new File(location);
        if (!f.exists()) return;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line = null;
            while ( (line = in.readLine()) != null) {
                PetName name = new PetName(line);
                if (name.getName() != null)
                    _names.put(name.getName(), name);
            }
        } finally {
            in.close();
        }
    }
    public void store(String location) throws IOException {
        Writer out = null;
        try {
            out = new OutputStreamWriter(new FileOutputStream(location), "UTF-8");
            for (Iterator names = getNames().iterator(); names.hasNext(); ) {
                PetName name = get((String)names.next());
                if (name != null)
                    out.write(name.toString() + "\n");
            }
        } finally {
            out.close();
        }
    }
}
