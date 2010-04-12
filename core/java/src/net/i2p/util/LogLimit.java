package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines the log limit for a particular set of logs
 *
 */
class LogLimit {
    private String _rootName;
    private int _limit;

    public LogLimit(String name, int limit) {
        _rootName = name;
        _limit = limit;
    }

    public String getRootName() {
        return _rootName;
    }

    public int getLimit() {
        return _limit;
    }
    
    public void setLimit(int limit) {
        _limit = limit;
    }

    public boolean matches(Log log) {
        String name = log.getName();
        if (name == null) return false;
        return name.startsWith(_rootName);
    }

    @Override
    public int hashCode() {
        return _rootName.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof LogLimit))
            return false;
        return _rootName.equals(((LogLimit) o).getRootName());
    }
}
