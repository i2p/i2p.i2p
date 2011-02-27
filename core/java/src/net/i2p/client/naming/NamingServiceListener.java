package net.i2p.client.naming;

import java.util.Properties;

import net.i2p.data.Destination;

public interface NamingServiceListener {

    /** also called when a NamingService is added or removed */
    public void configurationChanged(NamingService ns);

    /**
     *  @param options NamingService-specific, can be null
     */
    public void entryAdded(NamingService ns, String hostname, Destination dest, Properties options);

    /**
     *  @param dest null if unchanged
     *  @param options NamingService-specific, can be null
     */
    public void entryChanged(NamingService ns, String hostname, Destination dest, Properties options);

    public void entryRemoved(NamingService ns, String hostname);
}

